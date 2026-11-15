package app.autopayhunter.engine

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A money amount found in an SMS, with a hint about what it means. */
data class FoundAmount(val money: Money, val start: Int, val role: AmountRole)

enum class AmountRole { CHARGE, LIMIT, BALANCE }

internal object Amounts {
    private const val NUM = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private val INR = Regex("""(?i)(?:(?<![a-z])rs\.?|(?<![a-z])inr|₹|(?<![a-z])rupees)\s*$NUM""")
    private val FOREIGN = Regex("""(?i)(?<![a-z])(usd|eur|gbp|aed|sgd|aud|cad)\s*$NUM""")
    private val DOLLAR = Regex("""\$\s?$NUM""")
    /** SBI writes "debited by 119.0" with no currency at all. */
    private val BARE_DEBIT = Regex("""(?i)\b(?:debited|credited) by\s+$NUM(?=\s)""")

    private val LIMIT_BEFORE = Regex("""(?i)(?<![a-z])(?:up\s?to|upto|max(?:imum)?(?:\s+amount)?|limit|ceiling|not exceeding|capped at)\b[^0-9]{0,12}$""")
    private val BALANCE_BEFORE = Regex("""(?i)(?<![a-z])(?:avlbal|avl|avail|available|bal|balance|outstanding|amt due|amount due|total due|min(?:imum)?(?: amt| amount)? due|below|above|credit limit)\b[^0-9]{0,14}$""")

    fun find(text: String): List<FoundAmount> {
        val hits = mutableListOf<Triple<Int, Long, String>>()
        INR.findAll(text).forEach { m -> parse(m.groupValues[1])?.let { hits += Triple(m.range.first, it, "INR") } }
        FOREIGN.findAll(text).forEach { m -> parse(m.groupValues[2])?.let { hits += Triple(m.range.first, it, m.groupValues[1].uppercase()) } }
        DOLLAR.findAll(text).forEach { m -> parse(m.groupValues[1])?.let { hits += Triple(m.range.first, it, "USD") } }
        BARE_DEBIT.findAll(text).forEach { m ->
            val start = m.groups[1]!!.range.first
            if (hits.none { kotlin.math.abs(it.first - start) < 6 }) parse(m.groupValues[1])?.let { hits += Triple(start, it, "INR") }
        }
        return hits.sortedBy { it.first }.distinctBy { it.first }.map { (start, minor, cur) ->
            val before = text.substring(maxOf(0, start - 30), start)
            val role = when {
                BALANCE_BEFORE.containsMatchIn(before) -> AmountRole.BALANCE
                LIMIT_BEFORE.containsMatchIn(before) -> AmountRole.LIMIT
                else -> AmountRole.CHARGE
            }
            FoundAmount(Money(minor, cur), start, role)
        }
    }

    private fun parse(s: String): Long? {
        val clean = s.replace(",", "").trimEnd('.')
        val v = clean.toBigDecimalOrNull() ?: return null
        if (v.signum() <= 0) return null
        return v.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()
    }
}

object Format {
    private val SYMBOL = mapOf("INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£")

    /** ₹1,49,999 with Indian grouping. Paise only when they aren't zero. */
    fun money(m: Money): String {
        val sym = SYMBOL[m.currency] ?: (m.currency + " ")
        val whole = m.minor / 100
        val frac = m.minor % 100
        val grouped = if (m.isInr) indian(whole) else western(whole)
        return sym + grouped + if (frac != 0L) ".%02d".format(frac) else ""
    }

    fun minor(minor: Long, currency: String) = money(Money(minor, currency))

    private fun western(n: Long) = "%,d".format(Locale.US, n)

    private fun indian(n: Long): String {
        val s = n.toString()
        if (s.length <= 3) return s
        val last3 = s.takeLast(3)
        val rest = s.dropLast(3)
        return rest.reversed().chunked(2).joinToString(",").reversed() + "," + last3
    }

    /** "3 Nov", or "3 Nov 2027" when the year would be ambiguous. */
    fun date(d: LocalDate, today: LocalDate): String {
        val mon = d.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        val far = kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, d)) > 300
        return if (far || (d.year != today.year && d.isBefore(today))) "${d.dayOfMonth} $mon ${d.year}" else "${d.dayOfMonth} $mon"
    }
}

internal object Norm {
    /** "OPENAI *CHATGPT SUBSCR" -> "OPENAI CHATGPT SUBSCR" */
    fun spaced(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]+"), " ").trim()
    fun squashed(s: String) = s.uppercase().replace(Regex("[^A-Z0-9]+"), "")

    private val SMALL_WORDS = setOf("of", "and", "for", "the", "in", "on", "by")

    /** "SPOTIFY INDIA" -> "Spotify India", but keeps short acronyms like "MSEDCL". */
    fun title(s: String): String = s.trim().split(Regex("\\s+")).mapIndexed { i, w ->
        when {
            w.length in 2..6 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() } && !w.matches(Regex("[A-Z][a-z]+")) && isAcronymish(w) -> w
            i > 0 && w.lowercase() in SMALL_WORDS -> w.lowercase()
            else -> w.lowercase().replaceFirstChar { it.titlecase() }
        }
    }.joinToString(" ")

    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U')
    /** Words with few vowels are usually acronyms (MSEDCL, ACT, HDFC); words like GAANA aren't. */
    private fun isAcronymish(w: String): Boolean {
        val letters = w.filter { it.isLetter() }
        if (letters.length <= 3) return true
        return letters.count { it in VOWELS } * 3 < letters.length
    }
}
