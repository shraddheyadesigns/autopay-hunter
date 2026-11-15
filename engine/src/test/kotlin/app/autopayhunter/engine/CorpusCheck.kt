package app.autopayhunter.engine

import java.io.File
import java.time.LocalDate

/**
 * Runs the whole test inbox and checks two things:
 *  1. every single SMS was read the way we expect (the tag after the sender), and
 *  2. the subscriptions that come out are exactly right.
 * Plain Kotlin so it runs anywhere; a JUnit wrapper can call run() and assert it's empty.
 */
object CorpusCheck {
    private val failures = mutableListOf<String>()
    private fun check(ok: Boolean, what: String) { if (!ok) failures += what }

    fun run(corpusPath: String, today: LocalDate = LocalDate.of(2026, 11, 15), quiet: Boolean = false): List<String> {
        failures.clear()
        val items = CorpusFormat.parse(File(corpusPath).readText())
        val result = AutoPayEngine(today).scan(items.map { it.sms })
        if (!quiet) print(Report.render(result))

        // ---------- 1. Every SMS ----------
        for (it in items) {
            val exp = it.expected ?: continue
            val got = result.dispositions[it.sms.id]
            val ok = when (exp) {
                "IGNORE" -> got is Disposition.Skipped
                "SUSPECT" -> got is Disposition.Unreadable
                else -> got is Disposition.Used && got.kind.name == exp
            }
            check(ok, "SMS ${it.sms.id} [${it.section}] expected $exp, got $got\n      ${it.sms.body.lines().first().take(110)}")
        }

        // Copy rules: no em dashes anywhere the user reads.
        result.subscriptions.forEach { s ->
            (listOf(s.headline) + s.detail + s.cancel.steps + listOfNotNull(s.cancel.warning)).forEach { check(!it.contains('—'), "em dash in: $it") }
        }

        // ---------- 2. The subscriptions ----------
        when {
            corpusPath.contains("holdout") -> holdoutRecords(result)
            corpusPath.contains("corpus") -> { corpusRecords(result); userMarks(items.map { it.sms }, today) }
        }
        return failures.toList()
    }

    private fun inr(r: Int) = Money(r * 100L)
    private fun d(m: Int, day: Int, y: Int = 2026) = LocalDate.of(y, m, day)
    private fun find(subs: List<Subscription>, key: String, rail: Rail? = null) = subs.filter { it.merchant.key == key && (rail == null || it.rail == rail) }
    private fun one(subs: List<Subscription>, key: String, rail: Rail? = null): Subscription? {
        val f = find(subs, key, rail)
        check(f.size == 1, "expected exactly one $key${rail?.let { " on $it" } ?: ""}, found ${f.size}")
        return f.firstOrNull()
    }

    /** The "I cancelled it, did it actually stop?" loop, plus hiding and trial dates the user sets. */
    private fun userMarks(sms: List<Sms>, today: LocalDate) {
        val base = AutoPayEngine(today).scan(sms).subscriptions
        val netflix = find(base, "netflix").first()
        val chatgpt = find(base, "chatgpt").first()
        val googlePlayLike = find(base, "swiggy-one").first()
        val marks = mapOf(
            netflix.id to UserMark(cancelledOn = d(11, 13)),      // cancelled after the last charge: should be clean
            chatgpt.id to UserMark(cancelledOn = d(11, 1)),       // "cancelled" 1 Nov, but charged 14 Nov: must be caught
            googlePlayLike.id to UserMark(trialEndsOn = d(11, 17)), // user says the trial ends 17 Nov
        )
        val after = AutoPayEngine(today, marks).scan(sms).subscriptions
        find(after, "netflix").first().let { s ->
            check(s.status == Status.CANCELLED && s.closedBy == ClosedBy.USER, "netflix marked cancelled by user")
            check(s.flags.none { it is Flag.ChargedAfterCancel }, "netflix: no charge after the user's cancel")
            check(s.headline.startsWith("You cancelled it"), "netflix headline says the user cancelled it: ${s.headline}")
        }
        find(after, "chatgpt").first().let { s ->
            val f = s.flags.filterIsInstance<Flag.ChargedAfterCancel>().firstOrNull()
            check(f != null && f.chargedOn == d(11, 14), "chatgpt charged on 14 Nov after user said cancelled on 1 Nov")
            check(s.section == Section.NEEDS_ATTENTION, "chatgpt moves to needs attention")
        }
        find(after, "swiggy-one").first().let { s ->
            check(s.trial?.endsOn == d(11, 17) && s.flags.any { it is Flag.TrialEndsSoon }, "swiggy one trial end set by user, ending soon")
        }
        // Stable ids: the same scan twice gives the same ids, so marks stick.
        check(base.map { it.id } == AutoPayEngine(today).scan(sms.shuffled(java.util.Random(7))).subscriptions.map { it.id }, "ids stable across scans and SMS order")
        // "Not a subscription" hides it.
        val hidden = AutoPayEngine(today, mapOf(chatgpt.id to UserMark(notASubscription = true))).scan(sms).subscriptions
        check(find(hidden, "chatgpt").isEmpty(), "chatgpt hidden after 'not a subscription'")
    }

    private fun holdoutRecords(result: ScanResult) {
        val subs = result.subscriptions
        check(subs.none { it.merchant.name.contains("Will Be", true) || it.merchant.name.contains("Shortly", true) }, "no sentence fragments as merchant names")
        one(subs, "cultfit")?.let { s -> check(s.confidence == Confidence.MEDIUM && s.rail == Rail.CARD && s.amount == inr(3500), "cult.fit on HSBC from repeat charges") }
        one(subs, "apple")?.let { s -> check(s.instrument?.last4 == "31005", "amex 5-digit ending kept, got ${s.instrument?.last4}") }
        check(find(subs, "netflix").size == 2 && find(subs, "netflix").all { s -> s.flags.any { it is Flag.AlsoPaidElsewhere } }, "two Netflix, both flagged as paying twice")
        check(find(subs, "hdfc-ergo").isEmpty(), "a 'renew now' reminder is not an autopay")
        one(subs, "amazon-prime")?.let { s ->
            check(s.nextCharge == d(3, 12, 2027) && s.cancel.title == "Cancel inside Amazon Prime", "prime renewal SMS from Amazon itself: next 12 Mar 2027, cancel inside Prime. Got ${s.nextCharge}, ${s.cancel.title}")
        }
        one(subs, "cc-bill")?.let { s -> check(s.section == Section.BILLS_AND_INVESTMENTS && s.cancel.warning?.contains("late fees") == true, "card bill autopay is a bill, with a late-fee warning") }
        one(subs, "rd")?.let { s -> check(s.amount == inr(3000) && s.frequency == Frequency.MONTHLY && s.section == Section.BILLS_AND_INVESTMENTS, "RD ₹3,000 monthly in bills") }
        check(subs.none { it.merchant.name.contains("Tea", true) }, "daily chai is not a subscription")
        one(subs, "google-play")?.let { s -> check(s.section == Section.MAYBE, "single Google Play charge goes to 'might be'") }
        one(subs, "tinder")?.let { s -> check(s.status == Status.CANCELLED, "tinder cancelled") }
        check(result.suspects.size == 1, "holdout: only ALTBalaji unreadable, got ${result.suspects.size}")
    }

    private fun corpusRecords(result: ScanResult) {
        val subs = result.subscriptions
        fun one(key: String, rail: Rail? = null) = one(subs, key, rail)
        fun find(key: String) = find(subs, key)

        one("netflix")?.let { s ->
            check(s.status == Status.ACTIVE, "netflix active")
            check(s.amount == inr(649) && s.frequency == Frequency.MONTHLY, "netflix ₹649 monthly")
            check(s.rail == Rail.CARD && s.instrument?.last4 == "4321" && s.instrument.bank?.key == "hdfc", "netflix on HDFC card 4321")
            check(s.nextCharge == d(12, 12) && !s.nextChargeConfirmed, "netflix next 12 Dec (predicted)")
            check(s.confidence == Confidence.HIGH, "netflix high confidence")
            check(s.cancel.url == "https://www.sihub.in/managesi/hdfc", "netflix cancel link from the bank's own SMS, got ${s.cancel.url}")
        }
        one("spotify", Rail.UPI)?.let { s ->
            check(s.status == Status.ACTIVE && s.amount == inr(119), "spotify upi active ₹119")
            check(s.upiApp?.key == "phonepe", "spotify set up in PhonePe")
            check(s.instrument?.last4 == "5678", "spotify upi from SBI 5678")
            check(s.flags.any { it is Flag.AlsoPaidElsewhere }, "spotify upi flagged as paid twice")
            check(s.events.count { it.kind == EventKind.DEBITED } == 6, "spotify upi has all 6 debits linked")
        }
        one("spotify", Rail.CARD)?.let { s ->
            check(s.flags.any { it is Flag.AlsoPaidElsewhere }, "spotify card flagged as paid twice")
        }
        one("youtube-premium")?.let { s ->
            check(s.amount == inr(299) && s.frequency == Frequency.MONTHLY, "youtube ₹299 monthly, got ${s.amount}")
            check(s.flags.any { it is Flag.PriceWentUp && it.from == inr(189) && it.to == inr(299) }, "youtube price went up 189 -> 299")
            check(s.upiApp?.key == "gpay", "youtube set up in Google Pay")
        }
        one("amazon-prime")?.let { s ->
            check(s.frequency == Frequency.YEARLY && s.amount == inr(1499), "prime ₹1,499 yearly")
            check(s.nextCharge == d(3, 12, 2027), "prime renews 12 Mar 2027, got ${s.nextCharge}")
            check(s.cancel.url == "https://www.sihub.in/managesi/icici", "prime cancel via ICICI SI Hub")
        }
        check(find("amazon").isEmpty(), "normal Amazon shopping is not a subscription")
        one("google-one")?.let { s ->
            check(s.status == Status.ACTIVE && s.upiApp?.key == "paytm", "google one active, set up in Paytm")
            check(s.nextCharge == d(11, 21), "google one next 21 Nov, got ${s.nextCharge}")
        }
        one("cultfit")?.let { s ->
            check(s.status == Status.CANCELLED && s.statusSince == d(9, 2), "cult cancelled 2 Sep")
            check(s.flags.none { it is Flag.ChargedAfterCancel }, "cult: no charge after cancel")
            check(s.section == Section.CANCELLED, "cult in cancelled")
        }
        one("linkedin")?.let { s ->
            val f = s.flags.filterIsInstance<Flag.ChargedAfterCancel>().firstOrNull()
            check(f != null && f.charge == inr(1899) && f.chargedOn == d(10, 30) && !f.alreadyScheduled, "linkedin charged ₹1,899 on 30 Oct after cancel")
            check(s.section == Section.NEEDS_ATTENTION, "linkedin needs attention")
        }
        one("chatgpt")?.let { s ->
            check(s.status == Status.ACTIVE && s.amount == inr(1999) && s.frequency == Frequency.MONTHLY, "chatgpt ₹1,999 monthly")
            check(s.confidence == Confidence.MEDIUM, "chatgpt medium (no mandate SMS)")
        }
        one("claude")?.let { s ->
            check(s.amount == Money(2000, "USD") && s.yearlyCost == Money(24000, "USD"), "claude \$20/month, \$240/year, got ${s.amount} ${s.yearlyCost}")
        }
        one("hdfc-mf")?.let { s ->
            check(s.rail == Rail.NACH && s.section == Section.BILLS_AND_INVESTMENTS && s.amount == inr(5000), "SIP ₹5,000 NACH in bills")
            check(s.events.count { it.kind == EventKind.DEBITED } == 7, "SIP all 7 debits linked")
        }
        one("bajaj-finance")?.let { s ->
            check(s.rail == Rail.NACH && s.amount == inr(2345) && s.frequency == Frequency.MONTHLY, "bajaj EMI ₹2,345 monthly")
            check(s.cancel.warning?.contains("loan") == true, "bajaj cancel warns it's a loan")
        }
        one("msedcl")?.let { s ->
            check(s.flags.any { it is Flag.VariableAmount }, "msedcl variable")
            check(s.amount == inr(1710) && s.frequency == Frequency.MONTHLY, "msedcl last ₹1,710 monthly, got ${s.amount} ${s.frequency}")
            check(s.section == Section.BILLS_AND_INVESTMENTS, "msedcl in bills")
        }
        one("airtel")?.let { s ->
            check(s.nextCharge == d(11, 18) && s.nextChargeConfirmed, "airtel next 18 Nov confirmed")
        }
        one("zee5")?.let { s ->
            check(s.status == Status.TRIAL && s.trial?.endsOn == d(11, 16) && s.trial.price == inr(299), "zee5 trial ends 16 Nov, ₹299")
            check(s.section == Section.NEEDS_ATTENTION, "zee5 trial ending tomorrow needs attention")
        }
        one("swiggy-one")?.let { s ->
            check(s.status == Status.TRIAL && s.trial != null && s.trial.endsOn == null && s.trial.price == inr(149), "swiggy one trial, unknown end, up to ₹149")
        }
        check(find("swiggy").isEmpty(), "Swiggy food orders are not a subscription")
        one("audible")?.let { s ->
            check(s.flags.any { it is Flag.PaymentFailing }, "audible payment failing")
            check(s.section == Section.NEEDS_ATTENTION, "audible needs attention")
        }
        one("gaana")?.let { s -> check(s.status == Status.QUIET, "gaana quiet but open, got ${s.status}") }
        one("jiohotstar")?.let { s ->
            check(s.status == Status.PAUSED && s.pausedUntil == d(12, 31), "jiohotstar paused till 31 Dec")
        }
        one("hdfc-ergo")?.let { s ->
            check(s.amount == inr(12450) && s.frequency == Frequency.YEARLY && s.nextCharge == d(1, 20, 2027), "ergo ₹12,450 yearly, next 20 Jan 2027")
        }
        one("times-prime")?.let { s ->
            check(s.rail == Rail.CARD && s.instrument?.bank?.key == "canara" && s.frequency == Frequency.YEARLY, "times prime on Canara RuPay, yearly")
        }
        check(subs.none { it.merchant.name.contains("Ramesh", true) }, "rent to a person is not a subscription")
        check(subs.size == 21, "expected 21 subscriptions, got ${subs.size}: ${subs.joinToString { it.merchant.name }}")
        check(result.suspects.size == 1 && result.suspects[0].sms.body.contains("ACT Fibernet"), "only the ACT SMS is unreadable, got ${result.suspects.size}")
        check(result.stats.personalSkipped == 1, "one personal SMS skipped")
        one("linkedin")?.let { s -> check(s.cancel.title.startsWith("Stop LinkedIn"), "charged-after-cancel offers dispute steps, got ${s.cancel.title}") }
        one("chatgpt")?.let { s -> check(s.cancel.title == "Cancel inside ChatGPT", "no-mandate card sub cancels inside the app, got ${s.cancel.title}") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull() ?: "testdata/corpus.txt"
        val f = run(path, quiet = "--quiet" in args)
        if (f.isEmpty()) println("ALL CHECKS PASSED") else {
            println("${f.size} FAILED:"); f.forEach { println("  x $it") }
            kotlin.system.exitProcess(1)
        }
    }
}
