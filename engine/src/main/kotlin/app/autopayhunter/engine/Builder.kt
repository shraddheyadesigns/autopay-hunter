package app.autopayhunter.engine

import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A bundle of events that are all about the same subscription. */
internal class Group(val events: MutableList<Event>, val mandateKey: String?) {
    val merchant: Merchant get() = events.mapNotNull { it.merchant }.groupBy { it.key }.maxBy { (_, v) -> v.size + if (v[0].known) 100 else 0 }.value.first()
    val rail: Rail get() = events.map { it.rail }.groupBy { it }.maxBy { (r, v) ->
        v.size + when (r) { Rail.UNKNOWN -> 0; Rail.ACCOUNT -> 50; else -> 100 }
    }.key
    val instrument: Instrument? get() = events.mapNotNull { it.instrument }.maxByOrNull { (if (it.last4 != null) 2 else 0) + (if (it.bank != null) 1 else 0) }
    val start: LocalDate get() = events.minOf { it.date }
    val closedOn: LocalDate? get() {
        val sorted = events.sortedBy { it.sms.receivedAt }
        var closed: LocalDate? = null
        for (e in sorted) when (e.kind) {
            EventKind.REVOKED, EventKind.EXPIRED -> closed = e.date
            EventKind.CREATED, EventKind.RESUMED -> closed = null
            else -> {}
        }
        return closed
    }
    val hasMandateEvidence get() = events.any { it.kind != EventKind.DEBITED || it.viaMandate }
}

internal object Grouper {

    fun group(events: List<Event>): List<Group> {
        val groups = mutableListOf<Group>()
        val byId = LinkedHashMap<String, Group>()

        // 1. Same mandate ID = same subscription, no question.
        for (e in events.filter { it.mandateId != null }) {
            val key = e.mandateId!!.substringBefore('@')
            byId.getOrPut(key) { Group(mutableListOf(), key).also { groups += it } }.events += e
        }
        // 2. Everything else joins a mandate whose merchant, rail and account match, and whose life covers the date.
        val loose = mutableListOf<Event>()
        for (e in events.filter { it.mandateId == null }.sortedBy { it.sms.receivedAt }) {
            val candidates = byId.values.filter { g -> compatible(e, g) && covers(g, e.date) }
            val pick = candidates.maxByOrNull { g -> if (!g.start.isAfter(e.date.plusDays(3))) g.start.toEpochDay() else -g.start.toEpochDay() }
            if (pick != null) pick.events += e else loose += e
        }
        // 3. Leftovers group by merchant + rail + account. They're either mandates whose SMS
        //    carry no ID (e.g. "as per standing instruction") or just repeat charges.
        val looseGroups = mutableListOf<Group>()
        for (e in loose) {
            val g = looseGroups.firstOrNull { compatible(e, it) && sameAccountStrict(e, it) } ?: looseGroups.firstOrNull { compatible(e, it) }
            if (g != null) g.events += e else looseGroups += Group(mutableListOf(e), null)
        }
        groups += looseGroups
        return groups.filter { g -> g.events.any { it.merchant != null } }
    }

    private fun compatible(e: Event, g: Group): Boolean {
        val m = e.merchant ?: return false
        if (g.events.none { it.merchant?.key == m.key }) return false
        if (!railsMatch(e.rail, g.rail)) return false
        return instrumentsMatch(e.instrument, g.instrument)
    }

    private fun sameAccountStrict(e: Event, g: Group) = e.instrument?.last4 != null && e.instrument.last4 == g.instrument?.last4

    private fun railsMatch(a: Rail, b: Rail) = a == b || a == Rail.UNKNOWN || b == Rail.UNKNOWN ||
        (a == Rail.ACCOUNT && b != Rail.CARD) || (b == Rail.ACCOUNT && a != Rail.CARD)

    private fun instrumentsMatch(a: Instrument?, b: Instrument?): Boolean {
        if (a == null || b == null) return true
        if (a.bank != null && b.bank != null && a.bank.key != b.bank.key) return false
        if (a.last4 != null && b.last4 != null) return a.last4 == b.last4
        return true
    }

    /** A charge 45 days after a cancel still belongs to it: that's exactly the case we must catch. */
    private fun covers(g: Group, d: LocalDate): Boolean {
        if (d.isBefore(g.start.minusDays(3))) return false
        val closed = g.closedOn ?: return true
        return !d.isAfter(closed.plusDays(45))
    }
}

internal class Builder(private val today: LocalDate, private val marks: Map<String, UserMark>) {

    private fun Money.tiny() = isInr && minor <= 500

    fun build(g: Group): Subscription? {
        val evs = g.events.sortedBy { it.sms.receivedAt }
        val merchant = g.merchant
        val id = idFor(g)
        val mark = marks[id]
        if (mark?.notASubscription == true) return null

        // A setup that failed and nothing else happened isn't a subscription.
        if (evs.all { it.kind == EventKind.FAILED_SETUP }) return null

        val debits = evs.filter { it.kind == EventKind.DEBITED && it.amount != null }
        val realDebits = debits.filter { !it.amount!!.tiny() }
        val upcomings = evs.filter { it.kind == EventKind.UPCOMING }
        val explicit = g.hasMandateEvidence

        // A mandate set up with a real amount usually takes its first payment that day, so its date counts too.
        val createdWithPrice = evs.firstOrNull { it.kind == EventKind.CREATED && it.amount?.tiny() == false }
        // Repeat charges with no mandate SMS: only keep them when they really look like a subscription.
        val recurrenceFreq = inferFrequency(realDebits.map { it.date } + upcomings.map { it.date } + listOfNotNull(createdWithPrice?.date))
        if (!explicit && !keepRecurrence(merchant, g, realDebits, recurrenceFreq)) return null

        // ---------- Lifecycle ----------
        var open = true
        var closedOn: LocalDate? = null
        var pausedUntil: LocalDate? = null
        var pausedOn: LocalDate? = null
        var closedBy: ClosedBy? = null
        for (e in evs) when (e.kind) {
            EventKind.CREATED, EventKind.RESUMED -> { open = true; closedOn = null; closedBy = null; pausedUntil = null; pausedOn = null }
            EventKind.REVOKED -> { open = false; closedOn = e.date; closedBy = ClosedBy.REVOKED }
            EventKind.EXPIRED -> { open = false; closedOn = e.date; closedBy = ClosedBy.EXPIRED }
            EventKind.PAUSED -> { pausedOn = e.date; pausedUntil = e.pausedUntil }
            else -> {}
        }
        // A mandate past its validity date can't charge any more.
        val validTo = evs.lastOrNull { it.validTo != null }?.validTo
        if (open && validTo != null && validTo.isBefore(today)) { open = false; closedOn = validTo; closedBy = ClosedBy.EXPIRED }
        var userCancelled = false
        if (open && mark?.cancelledOn != null) { closedOn = mark.cancelledOn; open = false; userCancelled = true; closedBy = ClosedBy.USER }
        val paused = open && pausedOn != null && (pausedUntil == null || !pausedUntil.isBefore(today))

        // ---------- Frequency and amount ----------
        val stated = evs.lastOrNull { it.frequency != null }?.frequency
        val frequency = stated ?: recurrenceFreq
        val variable = evs.any { it.variableAmount } || isVariable(realDebits)
        val lastReal = realDebits.lastOrNull()
        val newerUpcoming = upcomings.lastOrNull { it.amount != null && (lastReal == null || !it.date.isBefore(lastReal.date)) }
        val lifecycleCharge = evs.lastOrNull { it.kind in setOf(EventKind.CREATED, EventKind.MODIFIED) && it.amount != null && !it.amount.tiny() }?.amount
        val limit = evs.lastOrNull { it.maxAmount != null && it.kind != EventKind.DEBITED }?.maxAmount
        val amount: Money? = when {
            variable && realDebits.isNotEmpty() -> lastReal!!.amount
            newerUpcoming != null -> newerUpcoming.amount
            lastReal != null -> lastReal.amount
            lifecycleCharge != null -> lifecycleCharge
            else -> limit
        }

        // ---------- Trial ----------
        val created = evs.firstOrNull { it.kind == EventKind.CREATED }
        val firstDebit = debits.firstOrNull()
        val startedTiny = created != null && (
            created.amount?.tiny() == true || created.trialHint ||
                (firstDebit != null && firstDebit.amount!!.tiny() && ChronoUnit.DAYS.between(created.date, firstDebit.date) in 0..2)
            )
        val convertedYet = created != null && realDebits.any { !it.date.isBefore(created.date) }
        val trial: Trial? = if (open && startedTiny && !convertedYet) {
            val announced = upcomings.lastOrNull { !it.date.isBefore(today) && it.amount?.tiny() == false }
            val endsOn = mark?.trialEndsOn ?: announced?.date
            val price = announced?.amount ?: limit ?: lifecycleCharge
            Trial(created!!.date, endsOn, price, listOf(7L, 14L, 30L).map { created.date.plusDays(it) }.filter { !it.isBefore(today) })
                .let { t -> if (mark?.trialEndsOn != null) t.copy(likelyEnds = emptyList()) else t }
        } else null

        // ---------- Dates ----------
        val lastCharged = lastReal?.date
        val upcomingNext = upcomings.lastOrNull { !it.date.isBefore(today) }
        val nextConfirmed = upcomingNext != null && open && !paused
        var next: LocalDate? = when {
            !open || paused -> null
            upcomingNext != null -> upcomingNext.date
            trial != null -> trial.endsOn
            frequency != null && (lastCharged ?: created?.date) != null -> {
                var d = roll(lastCharged ?: created!!.date, frequency)
                var guard = 0
                while (d.isBefore(today) && guard++ < 400) d = roll(d, frequency)
                d
            }
            else -> null
        }

        // ---------- Status ----------
        val lastActivity = listOfNotNull(lastCharged, created?.date, upcomings.lastOrNull()?.date,
            evs.lastOrNull { it.kind == EventKind.MODIFIED || it.kind == EventKind.RESUMED }?.date).maxOrNull() ?: g.start
        val status = when {
            !open -> Status.CANCELLED
            paused -> Status.PAUSED
            trial != null -> Status.TRIAL
            explicit -> if (isQuiet(lastActivity, frequency)) Status.QUIET else Status.ACTIVE
            else -> if (endedRecurrence(lastCharged ?: g.start, frequency)) Status.PROBABLY_ENDED else Status.ACTIVE
        }
        if (status == Status.QUIET || status == Status.PROBABLY_ENDED) next = null
        val statusSince = when (status) {
            Status.CANCELLED -> closedOn
            Status.PAUSED -> pausedOn
            Status.TRIAL -> trial?.startedOn
            Status.QUIET, Status.PROBABLY_ENDED -> lastCharged ?: lastActivity
            Status.ACTIVE -> g.start
        }

        // ---------- Flags ----------
        val flags = mutableListOf<Flag>()
        if (closedOn != null) {
            val closeEvent = evs.lastOrNull { it.kind == EventKind.REVOKED || it.kind == EventKind.EXPIRED }
            val after = debits.filter { d ->
                if (closeEvent != null && !userCancelled) d.sms.receivedAt.isAfter(closeEvent.sms.receivedAt) && !d.date.isBefore(closedOn)
                else d.date.isAfter(closedOn)
            }
            after.lastOrNull()?.let { d ->
                val scheduled = upcomings.any { u ->
                    (closeEvent == null || u.sms.receivedAt.isBefore(closeEvent.sms.receivedAt)) &&
                        kotlin.math.abs(ChronoUnit.DAYS.between(u.date, d.date)) <= 1
                }
                flags += Flag.ChargedAfterCancel(closedOn, d.amount!!, d.date, scheduled)
            }
        }
        trial?.endsOn?.let { end ->
            val days = ChronoUnit.DAYS.between(today, end)
            if (days in 0..3) flags += Flag.TrialEndsSoon(end, trial.price)
        }
        if (open && !paused) {
            val lastPayment = evs.lastOrNull { (it.kind == EventKind.DEBITED && it.amount?.tiny() == false) || it.kind == EventKind.DEBIT_FAILED }
            if (lastPayment?.kind == EventKind.DEBIT_FAILED) flags += Flag.PaymentFailing(lastPayment.date)
        }
        if (!variable && realDebits.size >= 2) {
            // Find the most recent point where the price changed, e.g. 189, 189, 299, 299 -> 189 to 299.
            val idx = (realDebits.size - 1 downTo 1).firstOrNull { realDebits[it].amount != realDebits[it - 1].amount }
            if (idx != null) {
                val a = realDebits[idx - 1].amount!!; val b = realDebits[idx].amount!!
                if (a.currency == b.currency && b.minor > a.minor * 105 / 100 && ChronoUnit.DAYS.between(realDebits[idx].date, today) <= 180) {
                    flags += Flag.PriceWentUp(a, b, realDebits[idx].date)
                }
            }
        }
        if (limit != null && amount != null && !variable && limit.currency == amount.currency &&
            limit.minor >= amount.minor * 3 && limit.minor - amount.minor >= 50_000 && trial == null
        ) flags += Flag.HighLimit(limit)
        if (variable && realDebits.size >= 2) {
            val same = realDebits.mapNotNull { it.amount }.filter { it.currency == amount?.currency }
            if (same.size >= 2) flags += Flag.VariableAmount(same.minBy { it.minor }, same.maxBy { it.minor })
        }

        // ---------- Money per year ----------
        val yearly: Money? = run {
            val f = frequency ?: return@run null
            val base = if (variable && realDebits.size >= 2) {
                val recent = realDebits.takeLast(3).mapNotNull { it.amount }.filter { it.currency == lastReal?.amount?.currency }
                Money(recent.sumOf { it.minor } / recent.size, recent.first().currency)
            } else (trial?.price ?: amount) ?: return@run null
            val y = base * f.perYear
            // Estimates shouldn't look precise: ₹21,493.92 becomes ₹21,500.
            if (variable) Money((y.minor + 5_000) / 10_000 * 10_000, y.currency) else y
        }

        val confidence = when {
            explicit -> Confidence.HIGH
            merchant.known && realDebits.size >= 2 && recurrenceFreq != null -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        val section = when {
            flags.any { it is Flag.ChargedAfterCancel || it is Flag.TrialEndsSoon || it is Flag.PaymentFailing } -> Section.NEEDS_ATTENTION
            status == Status.TRIAL -> Section.TRIALS
            status == Status.CANCELLED -> Section.CANCELLED
            status == Status.PAUSED -> Section.PAUSED
            status == Status.QUIET -> Section.QUIET
            status == Status.PROBABLY_ENDED -> Section.PROBABLY_ENDED
            confidence == Confidence.LOW -> Section.MAYBE
            merchant.kind == MerchantKind.ESSENTIAL -> Section.BILLS_AND_INVESTMENTS
            else -> Section.SUBSCRIPTIONS
        }

        val upiApp = evs.mapNotNull { it.upiApp }.lastOrNull()
        val draft = Subscription(
            id = id, merchant = merchant, rail = g.rail, instrument = g.instrument, upiApp = upiApp,
            mandateId = g.mandateKey, amount = amount, maxAmount = limit,
            frequency = frequency, frequencyStated = stated != null,
            firstSeen = g.start, lastCharged = lastCharged, nextCharge = next, nextChargeConfirmed = nextConfirmed,
            status = status, statusSince = statusSince, closedBy = if (open) null else closedBy, pausedUntil = if (paused) pausedUntil else null,
            trial = trial, yearlyCost = yearly, confidence = confidence, flags = flags, section = section,
            events = evs, headline = "", detail = emptyList(),
            cancel = CancelGuide("", emptyList(), null, null, null, null, false),
        )
        return draft
    }

    private fun keepRecurrence(m: Merchant, g: Group, real: List<Event>, freq: Frequency?): Boolean {
        if (m.kind == MerchantKind.ONE_OFF) return false
        val n = real.map { it.date }.distinct().size
        if (m.known && m.kind == MerchantKind.SUBSCRIPTION) return n >= 1
        if (m.known) return n >= 2 && freq != null
        if (g.rail != Rail.CARD && looksLikePerson(g.events.firstNotNullOfOrNull { it.merchantRaw })) return false
        // Daily payments to an unknown shop are a chai habit, not a subscription.
        return n >= 3 && freq != null && freq != Frequency.DAILY && !isVariable(real, 1.15)
    }

    private val BUSINESS = Regex("""(?i)\b(ltd|pvt|private|limited|llp|inc|india|services|store|mart|enterprises?|traders|solutions|tech|technologies|foods?|cafe|restaurant|hospital|clinic|school|college|academy|bank|finance|insurance|club|gym|fitness|studio|media|digital|online|network|broadband|fibernet|power|electric|gas|telecom|com|net|app|pay)\b""")
    private fun looksLikePerson(raw: String?): Boolean {
        if (raw == null) return true
        val local = raw.substringBefore('@')
        if (raw.contains('@')) return local.matches(Regex("""\d{10}""")) || !local.contains(Regex("""[a-zA-Z]{3,}"""))
        return raw.matches(Regex("""[A-Za-z]+(?: [A-Za-z.]+){1,3}""")) && !BUSINESS.containsMatchIn(raw)
    }

    private fun isVariable(real: List<Event>, ratio: Double = 1.10): Boolean {
        val amounts = real.mapNotNull { it.amount }
        if (amounts.size < 3 || amounts.map { it.currency }.distinct().size > 1) return false
        val lo = amounts.minOf { it.minor }; val hi = amounts.maxOf { it.minor }
        // Two prices in a row (a price rise) is not "variable"; many different values is.
        return hi > lo * ratio && amounts.map { it.minor }.distinct().size >= 3
    }

    private fun isQuiet(last: LocalDate, f: Frequency?): Boolean {
        val gap = ChronoUnit.DAYS.between(last, today)
        return if (f != null) gap > f.days * 2 + 10 else gap > 120
    }

    private fun endedRecurrence(last: LocalDate, f: Frequency?): Boolean {
        val gap = ChronoUnit.DAYS.between(last, today)
        return if (f != null) gap > f.days * 3 / 2 + 7 else gap > 45
    }

    companion object {
        fun inferFrequency(dates: List<LocalDate>): Frequency? {
            val ds = dates.sorted().fold(mutableListOf<LocalDate>()) { acc, d ->
                if (acc.isEmpty() || ChronoUnit.DAYS.between(acc.last(), d) > 3) acc += d; acc
            }
            if (ds.size < 2) return null
            val gaps = ds.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
            val median = gaps[gaps.size / 2]
            val f = when (median) {
                in 1..2 -> Frequency.DAILY
                in 5..9 -> Frequency.WEEKLY
                in 12..17 -> Frequency.FORTNIGHTLY
                in 26..35 -> Frequency.MONTHLY
                in 55..66 -> Frequency.BIMONTHLY
                in 84..98 -> Frequency.QUARTERLY
                in 170..195 -> Frequency.HALF_YEARLY
                in 350..380 -> Frequency.YEARLY
                else -> null
            } ?: return null
            // Most gaps must agree, or it's just someone shopping now and then.
            val agree = gaps.count { kotlin.math.abs(it - f.days) <= maxOf(4, f.days / 6) || it % f.days <= 4 }
            return if (agree * 3 >= gaps.size * 2) f else null
        }

        fun roll(from: LocalDate, f: Frequency): LocalDate = when (f) {
            Frequency.DAILY -> from.plusDays(1)
            Frequency.WEEKLY -> from.plusWeeks(1)
            Frequency.FORTNIGHTLY -> from.plusWeeks(2)
            Frequency.MONTHLY -> from.plusMonths(1)
            Frequency.BIMONTHLY -> from.plusMonths(2)
            Frequency.QUARTERLY -> from.plusMonths(3)
            Frequency.HALF_YEARLY -> from.plusMonths(6)
            Frequency.YEARLY -> from.plusYears(1)
        }

        fun idFor(g: Group): String {
            val i = g.instrument
            val raw = g.mandateKey?.let { "m:$it" } ?: "k:${g.merchant.key}:${g.rail}:${i?.bank?.key}:${i?.last4}"
            val md = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
            return md.take(6).joinToString("") { "%02x".format(it) }
        }
    }
}
