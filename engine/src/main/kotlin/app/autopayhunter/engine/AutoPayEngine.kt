package app.autopayhunter.engine

import java.time.LocalDate

/**
 * The whole engine in one call:
 *
 *   val result = AutoPayEngine(today = LocalDate.now()).scan(smsList)
 *
 * Pure function of (messages, today, user marks). No network, no storage, no AI.
 */
class AutoPayEngine(
    private val today: LocalDate,
    private val marks: Map<String, UserMark> = emptyMap(),
) {
    fun scan(messages: List<Sms>): ScanResult {
        val dispositions = LinkedHashMap<String, Disposition>()
        val events = mutableListOf<Event>()
        val suspects = mutableListOf<Suspect>()
        val seen = HashSet<String>()
        var personal = 0

        for (sms in messages.sortedBy { it.receivedAt }) {
            val sender = Senders.parse(sms.sender)
            if (!sender.personal && !seen.add("${sender.header}|${sms.receivedAt.toLocalDate()}|${sms.body.trim()}")) {
                dispositions[sms.id] = Disposition.Skipped(SkipReason.DUPLICATE)
                continue
            }
            when (val r = Extractor.read(sms, sender)) {
                is Read.Ok -> { events += r.event; dispositions[sms.id] = Disposition.Used(r.event.kind) }
                is Read.Skip -> { if (r.reason == SkipReason.PERSONAL) personal++; dispositions[sms.id] = Disposition.Skipped(r.reason) }
                is Read.Unreadable -> { suspects += Suspect(sms, r.why); dispositions[sms.id] = Disposition.Unreadable(r.why) }
            }
        }

        val builder = Builder(today, marks)
        var subs = Grouper.group(events).mapNotNull { builder.build(it) }
        subs = markDoubles(subs)

        val explain = Explain(today)
        subs = subs.map { s ->
            val withText = s.copy(headline = explain.headline(s))
            withText.copy(detail = explain.detail(withText), cancel = Cancel.guide(withText))
        }.sortedWith(order())

        return ScanResult(
            today = today,
            subscriptions = subs,
            suspects = suspects,
            dispositions = dispositions,
            stats = ScanStats(messages.size, personal, messages.size - personal, events.size),
            totals = totals(subs),
        )
    }

    /** Same service paid from two places is worth a loud line. */
    private fun markDoubles(subs: List<Subscription>): List<Subscription> {
        val live = subs.filter { it.status == Status.ACTIVE || it.status == Status.TRIAL }
        return subs.map { s ->
            if (s !in live || !s.merchant.known || s.merchant.kind != MerchantKind.SUBSCRIPTION) return@map s
            val other = live.firstOrNull { it.id != s.id && it.merchant.key == s.merchant.key } ?: return@map s
            s.copy(flags = s.flags + Flag.AlsoPaidElsewhere(other.id, other.instrument?.label() ?: other.rail.name))
        }
    }

    private fun order(): Comparator<Subscription> = compareBy<Subscription> { it.section.ordinal }
        .thenBy { s ->
            when {
                s.flags.any { it is Flag.ChargedAfterCancel } -> 0
                s.flags.any { it is Flag.TrialEndsSoon } -> 1
                s.flags.any { it is Flag.PaymentFailing } -> 2
                else -> 3
            }
        }
        .thenBy { if (it.yearlyCost?.isInr == false) 1 else 0 }
        .thenByDescending { it.yearlyCost?.minor ?: it.amount?.minor ?: 0 }

    private fun totals(subs: List<Subscription>): Totals {
        val active = subs.filter { it.status == Status.ACTIVE && it.confidence != Confidence.LOW }
        fun sum(list: List<Subscription>) = list.mapNotNull { it.yearlyCost }.groupBy { it.currency }.mapValues { (_, v) -> v.sumOf { it.minor } }
        return Totals(
            subscriptionsYearly = sum(active.filter { it.merchant.kind != MerchantKind.ESSENTIAL }),
            billsYearly = sum(active.filter { it.merchant.kind == MerchantKind.ESSENTIAL }),
            someEstimated = active.any { it.yearlyCost == null || it.confidence != Confidence.HIGH || it.flags.any { f -> f is Flag.VariableAmount } },
        )
    }
}
