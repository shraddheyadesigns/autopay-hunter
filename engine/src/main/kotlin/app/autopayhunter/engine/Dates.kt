package app.autopayhunter.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class FoundDate(val date: LocalDate, val start: Int, val end: Int)

/**
 * Indian bank SMS write dates a dozen ways: 05-11-26, 05/11/2026, 03Aug26,
 * 12-Mar-26, 2026-06-12:09:14:51, 15 Nov 2026, Nov 15, 2026. Always day first.
 */
internal object Dates {
    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private const val MON = "(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?"

    /** 2026-06-12, and Bank of Baroda's 2026:11:07 */
    private val ISO = Regex("""(?<!\d)(20\d{2})([-:])(\d{1,2})\2(\d{1,2})(?!\d)""")
    private val NUMERIC = Regex("""(?<![\d/.\-])(\d{1,2})[-/.](\d{1,2})[-/.](\d{4}|\d{2})(?![\d])""")
    private val DAY_MON = Regex("""(?i)(?<![\d])(\d{1,2})(?:st|nd|rd|th)?[-\s]?$MON(?:[-\s,]*(\d{4}|\d{2})(?![\d:]))?""")
    private val MON_DAY = Regex("""(?i)(?<![a-z])$MON\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})""")

    fun findAll(text: String, ref: LocalDate): List<FoundDate> {
        val out = mutableListOf<FoundDate>()
        fun taken(r: IntRange) = out.any { r.first <= it.end && it.start <= r.last }

        ISO.findAll(text).forEach { m ->
            val (y, _, mo, d) = m.destructured
            make(y.toInt(), mo.toInt(), d.toInt())?.let { out += FoundDate(it, m.range.first, m.range.last) }
        }
        NUMERIC.findAll(text).forEach { m ->
            if (taken(m.range)) return@forEach
            val (d, mo, y) = m.destructured
            val year = fullYear(y)
            val date = make(year, mo.toInt(), d.toInt()) ?: make(year, d.toInt(), mo.toInt())
            date?.let { out += FoundDate(it, m.range.first, m.range.last) }
        }
        DAY_MON.findAll(text).forEach { m ->
            if (taken(m.range)) return@forEach
            val d = m.groupValues[1].toInt()
            val mo = MONTHS[m.groupValues[2].lowercase().take(3)] ?: return@forEach
            val y = m.groupValues[3]
            val date = if (y.isNotEmpty()) make(fullYear(y), mo, d) else nearest(mo, d, ref)
            date?.let { out += FoundDate(it, m.range.first, m.range.last) }
        }
        MON_DAY.findAll(text).forEach { m ->
            if (taken(m.range)) return@forEach
            val mo = MONTHS[m.groupValues[1].lowercase().take(3)] ?: return@forEach
            make(m.groupValues[3].toInt(), mo, m.groupValues[2].toInt())?.let { out += FoundDate(it, m.range.first, m.range.last) }
        }
        return out.sortedBy { it.start }
    }

    private fun fullYear(y: String) = if (y.length == 2) 2000 + y.toInt() else y.toInt()

    private fun make(y: Int, m: Int, d: Int): LocalDate? =
        if (y !in 2000..2099 || m !in 1..12 || d !in 1..31) null
        else runCatching { LocalDate.of(y, m, d) }.getOrNull()

    /** "12 Mar" with no year: pick the year that lands closest to when the SMS came. */
    private fun nearest(m: Int, d: Int, ref: LocalDate): LocalDate? =
        listOf(ref.year - 1, ref.year, ref.year + 1).mapNotNull { make(it, m, d) }
            .minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(ref, it)) }
}
