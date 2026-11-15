package com.shady.autonope

import app.autopayhunter.engine.Anonymizer
import app.autopayhunter.engine.Disposition
import app.autopayhunter.engine.Report
import app.autopayhunter.engine.ScanResult
import app.autopayhunter.engine.SkipReason
import app.autopayhunter.engine.Sms
import java.time.format.DateTimeFormatter

/**
 * Test-build helpers: the "look for a company" search and the text that goes on the clipboard.
 * Plain Kotlin, no Android, so it can be checked next to the engine.
 */
object Debug {
    data class Hit(val sms: Sms, val what: String)

    private val STAMP = DateTimeFormatter.ofPattern("dd MMM yy")
    const val LIMIT = 200

    fun stamp(sms: Sms): String = sms.receivedAt.format(STAMP)

    fun describe(d: Disposition?): String = when (d) {
        is Disposition.Used -> "Read as ${d.kind.name.lowercase().replace('_', ' ')}"
        is Disposition.Skipped -> "Skipped: ${d.reason.label}"
        is Disposition.Unreadable -> "Couldn't read: ${d.why}"
        null -> "Not looked at"
    }

    /** Every SMS that mentions the word, newest first. SMS from people stay out, same as the scan. */
    fun search(query: String, inbox: List<Sms>, result: ScanResult): List<Hit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return inbox.asSequence()
            .filter { (result.dispositions[it.id] as? Disposition.Skipped)?.reason != SkipReason.PERSONAL }
            .filter { it.body.contains(q, ignoreCase = true) || it.sender.contains(q, ignoreCase = true) }
            .sortedByDescending { it.receivedAt }
            .take(LIMIT)
            .map { Hit(it, describe(result.dispositions[it.id])) }
            .toList()
    }

    /** Full report with evidence dates, and the unreadable SMS masked instead of shown raw. */
    fun reportForCopy(r: ScanResult): String =
        Report.render(r.copy(suspects = emptyList()), showEvidence = true).trimEnd() + "\n\n" + unreadableForCopy(r)

    fun unreadableForCopy(r: ScanResult): String = buildString {
        appendLine("== Couldn't read (${r.suspects.size}), masked ==")
        r.suspects.forEach {
            appendLine("? ${stamp(it.sms)}  ${it.sms.sender}: ${it.why}")
            appendLine("    ${mask(it.sms.body)}")
        }
    }

    fun hitsForCopy(query: String, hits: List<Hit>): String = buildString {
        appendLine("== Search \"${query.trim()}\" (${hits.size}), masked ==")
        hits.forEach { h ->
            appendLine("${stamp(h.sms)}  ${h.sms.sender}  [${h.what}]")
            appendLine("    ${mask(h.sms.body)}")
        }
    }

    private fun mask(body: String) = Anonymizer.clean(body).replace('\n', ' ')
}
