package app.autopayhunter.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Turns a record into words a person reads in two seconds.
 * Rules: lead with money and time, say what we don't know, no jargon (no "UMN", no "SI").
 */
internal class Explain(private val today: LocalDate) {

    private fun d(x: LocalDate) = when (ChronoUnit.DAYS.between(today, x)) {
        0L -> "today"
        1L -> "tomorrow"
        -1L -> "yesterday"
        else -> Format.date(x, today)
    }
    private fun on(x: LocalDate) = when (ChronoUnit.DAYS.between(today, x)) {
        0L, 1L, -1L -> d(x)
        else -> "on ${d(x)}"
    }
    private fun m(x: Money) = Format.money(x)

    private fun per(f: Frequency?) = when (f) {
        null -> null
        Frequency.MONTHLY -> "a month"
        Frequency.YEARLY -> "a year"
        Frequency.WEEKLY -> "a week"
        Frequency.DAILY -> "a day"
        else -> "every ${f.every}"
    }

    fun headline(s: Subscription): String {
        val cac = s.flags.filterIsInstance<Flag.ChargedAfterCancel>().firstOrNull()
        val fail = s.flags.filterIsInstance<Flag.PaymentFailing>().firstOrNull()
        val variable = s.flags.any { it is Flag.VariableAmount } || s.events.any { it.variableAmount }
        val price = s.amount?.let { a ->
            val p = per(s.frequency)
            when {
                variable && p != null -> "About ${m(a)} $p, changes with each bill"
                p != null -> "${m(a)} $p"
                else -> m(a)
            }
        }
        return when (s.status) {
            Status.CANCELLED -> {
                val verb = when (s.closedBy) {
                    ClosedBy.EXPIRED -> "Ended"
                    ClosedBy.USER -> "You cancelled it"
                    else -> "Cancelled"
                }
                if (cac != null) "$verb ${on(s.statusSince!!)}, but charged ${m(cac.charge)} ${on(cac.chargedOn)}"
                else "$verb ${on(s.statusSince!!)}. No charge since"
            }
            Status.TRIAL -> {
                val t = s.trial!!
                val p = per(s.frequency)
                if (t.endsOn != null) "Free trial ends ${d(t.endsOn)}." + (t.price?.let { " Then ${m(it)}${p?.let { x -> " $x" } ?: ""}" } ?: "")
                else "Free trial since ${d(t.startedOn)}. Can turn into " + (t.price?.let { "up to ${m(it)}${p?.let { x -> " $x" } ?: ""}" } ?: "a paid plan")
            }
            Status.PAUSED -> "Paused" + (s.pausedUntil?.let { " until ${d(it)}" } ?: "") + (price?.let { ". Normally $it" } ?: "")
            Status.QUIET -> if (s.lastCharged != null) "No charge since ${d(s.lastCharged)}, but it can still charge you"
                else "Set up ${on(s.firstSeen)} and never charged, but it's still open"
            Status.PROBABLY_ENDED -> "Last charged ${d(s.lastCharged ?: s.firstSeen)}${price?.let { " ($it)" } ?: ""}. Looks like it stopped"
            Status.ACTIVE -> {
                val base = price ?: "Amount not clear yet"
                when {
                    fail != null -> "$base. Last payment failed ${on(fail.failedOn)}"
                    s.frequency == null && s.nextCharge != null && s.nextChargeConfirmed -> "${s.amount?.let { m(it) } ?: base}. Next charge ${d(s.nextCharge)}"
                    s.frequency == null && s.lastCharged != null -> "$base, charged ${on(s.lastCharged)}. Not sure how often yet"
                    s.nextCharge != null -> {
                        val days = ChronoUnit.DAYS.between(today, s.nextCharge)
                        val word = when {
                            s.nextChargeConfirmed && days in 0..1 -> "Charges ${d(s.nextCharge)}"
                            s.nextChargeConfirmed -> "Next charge ${d(s.nextCharge)}"
                            days in 0..1 -> "Likely charges ${d(s.nextCharge)}"
                            s.frequency == Frequency.YEARLY -> "Renews around ${d(s.nextCharge)}"
                            else -> "Next around ${d(s.nextCharge)}"
                        }
                        "$base. $word"
                    }
                    else -> base
                }
            }
        }
    }

    fun detail(s: Subscription): List<String> {
        val out = mutableListOf<String>()
        val where = s.instrument?.label()
        val upiApp = s.upiApp?.let { ", set up in ${it.name}" } ?: ""
        out += when {
            s.rail == Rail.UNKNOWN -> "This came from ${s.merchant.name}, not your bank, so we don't know which card or account pays."
            s.confidence != Confidence.HIGH && s.rail == Rail.CARD -> "Charged to your ${where ?: "card"}"
            s.rail == Rail.UPI -> "UPI AutoPay from your ${where ?: "bank account"}$upiApp"
            s.rail == Rail.CARD -> "Card autopay on your ${where ?: "card"}"
            s.rail == Rail.NACH -> "Bank mandate (NACH) on your ${where ?: "bank account"}"
            else -> "Autopay from your ${where ?: "bank account"}"
        }

        for (f in s.flags) out += when (f) {
            is Flag.ChargedAfterCancel ->
                if (f.alreadyScheduled) "This charge was announced before you cancelled, so it may be the last one. We'll check next month."
                else "Money left after the cancel. Ask ${s.instrument?.bank?.name ?: "your bank"} to reverse it, and keep the cancel SMS as proof."
            is Flag.TrialEndsSoon -> {
                val days = ChronoUnit.DAYS.between(today, f.endsOn)
                val price = f.price?.let { " the ${m(it)} charge" } ?: " being charged"
                if (days <= 1) "Cancel today to avoid$price." else "Cancel before ${d(f.endsOn)} to avoid$price."
            }
            is Flag.PaymentFailing -> "If you meant to stop it, cancel it. A failed autopay can still go through when they retry."
            is Flag.PriceWentUp -> "Price went up from ${m(f.from)} to ${m(f.to)} ${on(f.on)}."
            is Flag.HighLimit -> "This mandate allows up to ${m(f.limit)} per charge, more than you usually pay."
            is Flag.AlsoPaidElsewhere -> "You also pay ${s.merchant.name} from your ${f.otherInstrument}. Paying twice?"
            is Flag.VariableAmount -> "Recent bills ranged from ${m(f.low)} to ${m(f.high)}."
        }

        if (s.status == Status.ACTIVE) s.yearlyCost?.let { y ->
            if (s.frequency != Frequency.YEARLY) out += if (s.flags.any { it is Flag.VariableAmount }) "That's about ${m(y)} a year." else "That's ${m(y)} a year."
        }
        s.trial?.let { t ->
            if (t.endsOn == null && t.likelyEnds.isNotEmpty()) {
                out += "We don't know when the trial ends. Most last 7 to 30 days, so it could turn paid as soon as ${d(t.likelyEnds.first())}."
            }
        }
        when (s.confidence) {
            Confidence.MEDIUM -> out += if (s.rail == Rail.CARD)
                "Found from repeat charges. There's no autopay SMS, so your card is probably saved with ${s.merchant.name}."
            else "Found from repeat payments. We haven't seen the SMS from when it was set up."
            Confidence.LOW -> out += "Only seen once or twice. Might not be a subscription."
            Confidence.HIGH -> {}
        }
        return out
    }
}

internal object Cancel {
    private fun warningFor(m: Merchant) = if (m.key == "cc-bill") {
        "If this stops, pay your card bill yourself each month, or you'll pay late fees and interest."
    } else when (m.category) {
        Category.LOANS -> "This looks like a loan EMI. Stopping it doesn't close the loan, and a missed EMI can bring bounce charges and hurt your credit score."
        Category.INSURANCE -> "Stopping this can let your insurance policy lapse. Make sure you pay another way."
        Category.INVESTMENTS -> "This stops future SIP instalments. What you've already invested stays."
        Category.UTILITIES, Category.TELECOM -> "The bill doesn't stop with the autopay. You'll need to pay it yourself each time."
        else -> null
    }

    fun guide(s: Subscription): CancelGuide {
        val m = s.merchant.name
        val bank = s.instrument?.bank
        val b = bank?.name ?: "your bank"
        val warning = warningFor(s.merchant)
        val merchantUrl = s.merchant.manageUrl
        val cac = s.flags.filterIsInstance<Flag.ChargedAfterCancel>().firstOrNull()

        // Already cancelled, still charged: the job now is to stop the company and get the money back.
        if (cac != null) return CancelGuide(
            title = "Stop $m and get the money back",
            steps = listOf(
                "Cancel your plan inside $m too, in case they bill your ${if (s.rail == Rail.CARD) "card" else "account"} directly.",
                "Raise a dispute with $b for the ${Format.money(cac.charge)} charge ${Format.date(cac.chargedOn, cac.chargedOn)}.",
                "Keep the cancel SMS from ${Format.date(cac.cancelledOn, cac.cancelledOn)} as proof.",
            ),
            openApp = null, url = null, merchantUrl = merchantUrl, warning = null, verified = false,
        )

        // We only know about it from the company itself, or from repeat card charges with no mandate.
        if (s.rail == Rail.UNKNOWN || (s.rail == Rail.CARD && s.confidence != Confidence.HIGH)) return CancelGuide(
            title = "Cancel inside $m",
            steps = listOfNotNull(
                "Open $m and go to your account or billing settings.",
                "Cancel the plan. Look for a confirmation email or SMS.",
                if (s.rail == Rail.CARD) "If charges continue, ask $b to block payments to $m." else null,
            ),
            openApp = null, url = null, merchantUrl = merchantUrl, warning = warning, verified = false,
        )

        return when (s.rail) {
            Rail.UPI -> CancelGuide(
                title = "Cancel in any UPI app",
                steps = listOf(
                    s.upiApp?.let { "Open any UPI app. This one was set up in ${it.name}, but every UPI app can now show it." }
                        ?: "Open any UPI app you use, like Google Pay, PhonePe, Paytm or BHIM.",
                    "Go to AutoPay. It's usually under your profile or settings.",
                    "Find $m and tap Cancel.",
                    "Confirm with your UPI PIN.",
                ),
                openApp = s.upiApp,
                url = "https://upihelp.npci.org.in",
                merchantUrl = merchantUrl, warning = warning, verified = false,
            )
            Rail.CARD -> {
                val fromSms = s.events.flatMap { it.trustedLinks }
                    .lastOrNull { Regex("(?i)(sihub|managesi|mandate|/si\\b|/si$|opt|standing|autopay|recurring)").containsMatchIn(it) }
                val url = fromSms ?: bank?.siPortal
                val cardName = s.instrument?.label() ?: "card"
                CancelGuide(
                    title = "Cancel the autopay on your $cardName",
                    steps = if (url != null) listOf(
                        "Open $b's standing instructions page.",
                        "Log in with your card number and the OTP.",
                        "Find $m and tap Cancel or Delete.",
                    ) else listOf(
                        "Open the $b app or net banking.",
                        "Under your card, look for Standing instructions, E-mandates or Recurring payments.",
                        "Find $m and cancel it.",
                    ),
                    openApp = null, url = url, merchantUrl = merchantUrl, warning = warning, verified = false,
                )
            }
            else -> CancelGuide(
                title = if (s.rail == Rail.NACH) "Cancel the bank mandate" else "Cancel the bank autopay",
                steps = listOf(
                    "Open $b's app or net banking.",
                    "Find Mandates, eNACH or Auto debits.",
                    "Pick $m and ask to cancel. Banks can take a few days.",
                    "Or ask $m to cancel it from their side.",
                ),
                openApp = null, url = null, merchantUrl = merchantUrl, warning = warning, verified = false,
            )
        }
    }
}
