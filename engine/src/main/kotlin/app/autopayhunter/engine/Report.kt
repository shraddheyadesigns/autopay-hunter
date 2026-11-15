package app.autopayhunter.engine

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Plain-text view of a scan. Used by the CLI, the tests, and a "copy debug report" button later. */
object Report {
    private val SECTION_TITLES = mapOf(
        Section.NEEDS_ATTENTION to "Needs you now",
        Section.TRIALS to "Free trials",
        Section.SUBSCRIPTIONS to "Subscriptions",
        Section.BILLS_AND_INVESTMENTS to "Bills, EMIs and investments",
        Section.MAYBE to "Might be subscriptions",
        Section.QUIET to "Not charging lately, but still open",
        Section.PAUSED to "Paused",
        Section.PROBABLY_ENDED to "Probably ended",
        Section.CANCELLED to "Cancelled",
    )

    fun render(r: ScanResult, showEvidence: Boolean = false): String = buildString {
        appendLine("AutoPay Hunter, scan of ${Format.date(r.today, r.today)} ${r.today.year}")
        appendLine("Read ${r.stats.bankMessagesRead} messages from banks and companies. Skipped ${r.stats.personalSkipped} from people without reading them.")
        // Totals in whole units: nobody needs paise in a yearly figure.
        fun whole(v: Long) = (v + 50) / 100 * 100
        val subs = r.totals.subscriptionsYearly.entries.joinToString(" + ") { (c, v) -> Format.minor(whole(v), c) }
        val bills = r.totals.billsYearly.entries.joinToString(" + ") { (c, v) -> Format.minor(whole(v), c) }
        if (subs.isNotEmpty()) appendLine("Subscriptions cost you $subs a year${if (r.totals.someEstimated) " (some estimated)" else ""}.")
        if (bills.isNotEmpty()) appendLine("Bills, EMIs and SIPs on autopay: $bills a year.")
        appendLine()

        for (section in Section.entries) {
            val list = r.section(section)
            if (list.isEmpty()) continue
            appendLine("== ${SECTION_TITLES[section]} (${list.size}) ==")
            for (s in list) {
                appendLine("* ${s.merchant.name}: ${s.headline}")
                s.detail.forEach { appendLine("    - $it") }
                val c = s.cancel
                if (s.status != Status.CANCELLED || s.flags.any { it is Flag.ChargedAfterCancel }) {
                    appendLine("    > ${c.title}" + (c.url?.let { " ($it)" } ?: "") + (c.openApp?.let { " [opens ${it.name}]" } ?: ""))
                    c.warning?.let { appendLine("    ! $it") }
                }
                if (showEvidence) s.events.forEach { e ->
                    appendLine("      ${e.sms.receivedAt.format(STAMP)}  ${e.kind.name.padEnd(12)} ${e.amount?.let { Format.money(it) } ?: ""}")
                }
            }
            appendLine()
        }
        if (r.suspects.isNotEmpty()) {
            appendLine("== We couldn't read these (${r.suspects.size}) ==")
            r.suspects.forEach { appendLine("? ${it.sms.sender}: ${it.why}\n    \"${it.sms.body.replace('\n', ' ').take(140)}\"") }
            appendLine()
        }
    }

    private val STAMP = DateTimeFormatter.ofPattern("dd MMM yy HH:mm")
}

/** The hand-written test inbox format (see testdata/corpus.txt). */
object CorpusFormat {
    data class Item(val sms: Sms, val expected: String?, val section: String)

    private val HEADER = Regex("""^@\s*(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s*\|\s*([^|]+?)\s*(?:\|\s*(\S+)\s*)?$""")

    fun parse(text: String): List<Item> {
        val out = mutableListOf<Item>()
        var section = ""
        var head: MatchResult? = null
        val body = StringBuilder()
        fun flush() {
            val h = head ?: return
            val (date, time, sender, exp) = h.destructured
            out += Item(
                Sms("c%03d".format(out.size + 1), sender, body.toString().trimEnd(), LocalDateTime.parse("${date}T$time")),
                exp.ifBlank { null }, section,
            )
            head = null; body.clear()
        }
        for (line in text.lines()) {
            when {
                line.startsWith("#") && head == null -> if (line.startsWith("# ----------")) section = line.trim('#', ' ', '-')
                line.startsWith("@") -> { flush(); head = HEADER.find(line.trim()) ?: error("Bad header: $line") }
                line.isBlank() -> flush()
                head != null -> { if (body.isNotEmpty()) body.append('\n'); body.append(line) }
            }
        }
        flush()
        return out
    }
}

/**
 * Masks what identifies a person but keeps what the parser needs (wording, amounts, dates),
 * so a real unreadable SMS can be shared to add support for its format.
 */
object Anonymizer {
    fun clean(body: String): String {
        var s = body
        // UMNs and mandate IDs: keep the @app part, hide the rest.
        s = s.replace(Regex("""(?i)\b([a-z0-9]{12,})(@[a-z]+)""")) { "x".repeat(8) + it.groupValues[2] }
        // Person-looking UPI IDs: phone numbers or names before the @.
        s = s.replace(Regex("""(?i)\b([0-9]{10}|[a-z]+\.?[a-z]*[0-9]{2,})@([a-z]+)""")) { "someone@" + it.groupValues[2] }
        // Masked account and card digits.
        s = s.replace(Regex("""(?i)([xX*•]{1,})([0-9]{3,6})\b""")) { it.groupValues[1] + "0".repeat(it.groupValues[2].length) }
        s = s.replace(Regex("""(?i)(card|cc|dc|a/c|acct|account|ac)(\s*(?:no\.?|ending(?: with)?)?\s*)([0-9]{4})\b""")) { it.groupValues[1] + it.groupValues[2] + "0000" }
        // Long numbers: reference numbers, phone numbers, IDs.
        s = s.replace(Regex("""\b[0-9]{6,}\b""")) { "0".repeat(it.value.length) }
        s = s.replace(Regex("""(?i)\b([a-z]{2,6}[0-9][a-z0-9]{6,})\b""")) { it.value.take(4) + "0".repeat(it.value.length - 4) }
        // Balances say a lot about a person and nothing about the format.
        s = s.replace(Regex("""(?i)((?:avl|avail|available|new)\s*(?:bal|balance|limit)\s*[:\-]?\s*(?:rs\.?|inr|₹)\s*)[0-9,]+(?:\.[0-9]{1,2})?""")) { it.groupValues[1] + "0.00" }
        return s
    }
}
