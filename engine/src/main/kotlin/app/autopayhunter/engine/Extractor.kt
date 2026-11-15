package app.autopayhunter.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal sealed class Read {
    data class Ok(val event: Event) : Read()
    data class Skip(val reason: SkipReason) : Read()
    data class Unreadable(val why: String) : Read()
}

/**
 * Reads one SMS. Rules, not AI: every decision here can be explained and tested,
 * and nothing ever leaves the phone.
 */
internal object Extractor {

    // ---------- Gates: things we drop before reading closely ----------

    /**
     * Only SMS whose point IS a code. Many debit alerts end with "Never share your OTP", and those
     * must still be read, so we look for the code itself, not the word.
     */
    private val OTP = Regex(
        """(?i)(\b\d{4,8}\b\s+is\s+(?:your|the)\s+(?:otp|one[- ]time password|verification code|passcode)|\b(?:otp|one[- ]time password|verification code|passcode)\b[^.]{0,60}?\b(?:is|:|-)\s*\d{4,8}\b|\buse\s+(?:otp\s+)?\d{4,8}\b)""",
    )
    private val IPO = Regex("""(?i)\b(ipo|asba|allotment)\b""")
    private val PROMO = Regex(
        """(?i)\b(cashback|win|offer|discount|click|download|apply now|t&c|never miss|hurry|limited time|register now|enable (?:upi )?autopay|set up (?:upi )?autopay|activate (?:upi )?autopay|get (?:rs\.?|₹|inr)\s?\d+|\d+% off|off on)\b""",
    )
    private val TXN_VERB = Regex(
        """(?i)\b(debited|credited|spent|charged|deducted|sent|has been|have been|successfully|will be|revoked|cancelled|paused|failed|registered|created|is now active)\b""",
    )
    private val STATEMENT = Regex("""(?i)\b(statement|total (?:amt|amount) due|min(?:imum)? (?:amt|amount) due|due by|due date|payment due)\b""")
    private val ADVISORY = Regex("""(?i)\b(ensure|maintain|keep)\s+(sufficient|adequate|enough)\s+(balance|funds)""")
    private val CREDIT = Regex("""(?i)\b(credited|reversed|reversal|refund(?:ed)?|received)\b""")

    /** Any of these means "this SMS is about something that repeats". Used to catch misses. */
    val AUTOPAY_WORDS = Regex(
        """(?i)(mandate|autopay|auto pay|auto-pay|auto debit|autodebit|auto-debit|auto-?paid|standing instruction|\bsi id\b|\be-?nach\b|\bnach\b|\bach d|\bumn\b|\bumrn\b|recurring|subscription|membership|pre-?debit|renew|free trial)""",
    )
    private val MANDATE_CTX = Regex(
        """(?i)(mandate|autopay|auto pay|auto-pay|auto debit|autodebit|auto-debit|standing instruction|\bsi\b|recurring|subscription|\bnach\b|e-?nach|\bach\b|\bumn\b|\bumrn\b)""",
    )
    /** Words that only appear when a real mandate exists (not "subscription", which card descriptors use). */
    private val STRICT_CTX = Regex(
        """(?i)(mandate|autopay|auto pay|auto-pay|auto debit|autodebit|auto-debit|standing instruction|\bsi\b|\bnach\b|e-?nach|\bach\b|\bumn\b|\bumrn\b)""",
    )

    // ---------- What happened ----------

    private val FAILED_SETUP = Regex(
        """(?i)((mandate|autopay|auto pay|standing instruction|\bsi\b|nach|subscription).{0,80}?(registration|creation|setup|set up|set-up|request|create|register).{0,60}?(failed|declined|unsuccessful|rejected|could not be)|(failed to|unable to|could not) (create|register|set ?up).{0,40}(mandate|autopay|standing instruction)|(registration|creation) (has )?(failed|been declined))""",
    )
    private val DEBIT_FAILED = Regex(
        """(?i)((debit|payment|transaction|txn|deduction|charge|presentation).{0,60}?(failed|declined|unsuccessful|could not be (processed|completed)|bounced|dishono?u?red|returned unpaid|rejected)|insufficient (balance|funds))""",
    )
    private val REVOKED = Regex("""(?i)\b(revoked|cancelled|canceled|deleted|deactivated|discontinued|terminated|opted[ -]out|withdrawn)\b""")
    private val EXPIRED = Regex("""(?i)\b(expired|has expired|completed|has ended|validity ended)\b""")
    private val PAUSED = Regex("""(?i)\b(paused|put on hold|on hold|suspended)\b""")
    private val RESUMED = Regex("""(?i)\b(resumed|reactivated|re-activated|unpaused)\b""")
    private val UPCOMING_DEBIT = Regex(
        """(?i)(will be (auto[- ]?)?(debited|charged|deducted|processed|presented|paid)|is scheduled|scheduled (to be|for)|due for (auto[- ]?)?debit|upcoming (debit|payment|charge|autopay)|pre-?debit notification|going to be (debited|charged)|shall be (debited|charged)|set to be (debited|charged))""",
    )
    /** "Will renew" is an autopay only when it doesn't also ask you to go and pay. */
    private val UPCOMING_RENEW = Regex("""(?i)(will (be )?(auto[- ]?)?renew(ed)?|renews on|(is )?(set|due) for (auto[- ]?)?renewal)""")
    private val PAY_BY_HAND = Regex("""(?i)\b(renew now|pay now|click|renew at|renew here|to renew|pay at|pay before|pay by|make (the )?payment)\b""")
    private val MODIFIED = Regex("""(?i)\b(modified|amended|revised|updated|changed)\b""")
    private val CREATED = Regex(
        """(?i)(successfully (created|registered|set ?up|activated|approved|authori[sz]ed|added)|(is|has been|was) (successfully )?(created|registered|set ?up|activated|approved|authori[sz]ed)|is now active|(mandate|instruction|\bsi\b|autopay|e-?nach|nach)\W{0,3}(created|registered|activated|set up)\b|registration (is |was )?successful|(created|registered|set up) successfully)""",
    )
    private val DEBITED = Regex(
        """(?i)\b(debited|deducted|spent|charged|paid|sent|withdrawn|purchase|txn of|transaction of|debit of)\b|\bdr\.?(?=\s+(?:from|to)\b)|\b(?:has been|was) used (?:for|at)\b|\b(?:autopay|auto-?debit|auto pay)\b.{0,40}?\b(?:successful|processed|executed)\b""",
    )
    /** A plain payment that failed moved no money. Not ours unless it's an autopay. */
    private val TXN_FAILED = Regex("""(?i)\b(failed|declined|unsuccessful|could not be processed)\b""")

    /** Instruction text like "To revoke, use your UPI app" must not count as "revoked". */
    private val INSTRUCTION = Regex(
        """(?i)(\bto (revoke|cancel|pause|opt[- ]?out|manage|view|modify|change|update|stop|block|deactivate|report|dispute|know|check|learn|unsubscribe|avoid|continue|enjoy)\b[^.;\n]*|\byou (can|may) [^.;\n]*|\bcan be (cancelled|revoked|paused|modified)[^.;\n]*|\bif not (you|u|done by you)\b[^.;\n]*|\bnot (you|u|done by you)\b\??[^.;\n]*|\bcall \d[^.;\n]*|\bsms block[^.;\n]*|\breport (it )?at[^.;\n]*|\bfor (any )?(queries|dispute|help)[^.;\n]*|\bmanage at[^.;\n]*|\bvisit [^\s]+)""",
    )

    // ---------- Fields ----------

    // "(?:\b|(?<=one))card" so "OneCard XX1212" counts as a card too.
    private val CARD_WORDS = Regex("""(?i)(credit card|debit card|(?:\b|(?<=one))card\s*(no\.?|number|ending|xx|x{2,}|\*{2,}|[0-9]{4})|card ?member|card ?holder|rupay autopay|bank card)""")
    /** 4 digits for most cards, 5 for Amex ("card ** 31005"). */
    private val CARD_LAST4 = Regex("""(?i)(?:\b|(?<=one))card\s*(?:no\.?|number)?\s*(?:ending(?:\s*with|\s*in)?)?\s*[:\-]?\s*[xX*•.]*\s*([0-9]{4,5})\b""")
    private val ACCOUNT_LAST = Regex("""(?i)(?<![a-z])(?:a/?c|acct|account|ac)\b\.?\s*(?:no\.?|number)?\s*[:\-]?\s*[xX*•.]*\s*([0-9]{3,6})\b""")
    private val NACH_WORDS = Regex("""(?i)(\be-?nach\b|\bnach\b|\bach\s?d\b|\bach-d|\bach dr\b|\bach\b|\bumrn\b|\becs\b)""")
    private val UPI_WORDS = Regex("""(?i)(\bupi\b|upi-mandate|\bumn\b|\bvpa\b|\bp2m\b|[a-z0-9._-]@[a-z]{2,}\b)""")

    private val UMN = Regex("""(?i)\bumn\b\s*(?:id|no\.?|number)?\s*[:\-]?\s*([a-z0-9]{6,}(?:@[a-z0-9]+)?)""")
    private val UMRN = Regex("""(?i)\bumrn\b\s*(?:no\.?)?\s*[:\-]?\s*([a-z0-9]{8,})""")
    private val MANDATE_ID = Regex("""(?i)\b(?:si\s*id|sihub\s*id|mandate\s*(?:id|ref(?:erence)?|no\.?|number)|mid)\b\s*[:\-]?\s*([a-z0-9]{6,})""")
    private val ACH_REF = Regex("""(?i)\bach\s*d-?\s*[a-z0-9 &.]+?-([a-z0-9]{8,})""")

    private val FREQ = Regex(
        """(?i)\b(daily|weekly|fortnightly|bi-?monthly|monthly|quarterly|half[- ]?yearly|semi[- ]?annual(?:ly)?|yearly|annual(?:ly)?|every month|per month|every year|per year|per annum|as[ -]?presented|one[- ]?time|adhoc|ad-hoc|on demand)\b""",
    )
    private val TRIAL = Regex("""(?i)(free trial|\btrial\b|first month free|free for \d+)""")
    private val PAUSE_UNTIL = Regex("""(?i)\b(?:till|until|upto|up to)\b""")
    private val VALID_TO = Regex("""(?i)\b(?:to|till|until|upto|valid till|valid upto|validity)\b""")

    private val END = """(?=\s+(?:for|from|on|via|under|as|with|is|are|has|have|was|will|would|shall|using|vide|and|by|mandate|umn|ref|of)\b|\s*\(|[,;]|\.\s|\.$|$)"""
    private val MERCHANT_PATTERNS = listOf(
        Regex("""(?im)\b(?:merchant|payee|biller|beneficiary)\s*(?:name)?\s*[:\-]\s*([^\n,;]+)"""),
        Regex("""(?i)\bUPI/P2[MA]/\d+/([^\n/]+)"""),
        Regex("""(?i)\b(?:mandate|autopay|si)\s+(?:created|registered|modified|updated)\s*:\s*([^,\n]+)"""),
        Regex("""(?i)\bin favou?r of\s+(.+?)$END"""),
        Regex("""(?i)\btowards\s+(.+?)$END"""),
        Regex("""(?i)\btrf to\s+(.+?)(?=\s+ref(?:no)?\b|\.\s|\.$|$)"""),
        Regex("""(?i);\s*([^;]+?)\s+credited\b"""),
        Regex("""(?i)\binfo:\s*(.+?)(?=\s+avl|\.\s|$)"""),
        Regex("""(?i)\bfor\s+(?!(?:rs|inr|usd|max|maximum|upto|up|rupay|upi|your|the|a|an|ipo|registering|any|queries)\b|₹)([A-Za-z][A-Za-z0-9 .&'*+-]*?)(?=\s*,|\s*\(|\s+(?:mandate|autopay|subscription|is|has|was|on|of|with|frequency|failed|via|under|from|as)\b|\.\s|\.$|$)"""),
        Regex("""(?i)\bto\s+(?!(?:your|the|a/?c|account|opt|revoke|cancel|view|manage|pause|block|report|you|rs|inr|be|change|update|know|check|learn|stop|get|keep|ensure|use|avoid|continue|enjoy|modify|unsubscribe|pay|renew)\b)([A-Za-z][A-Za-z0-9 .&'*@_-]*?)(?=\s+(?:on|from|for|via|ref|upi|is|are|has|have|was|will|would|shall|of)\b|\s*\(|[,;]|\.\s|\.$|$)"""),
        Regex("""(?i)\bat\s+([A-Za-z][^\n]*?)(?=\s+on\s+\d|\s+on\s+20\d\d|\.?\s+avl\b|[.,]?\s+(?:not you|if not|trxn)\b|\.\s|\.$|$)"""),
        Regex("""(?i)\bon\s+(?!(?:your|the|date|a/?c)\b)(?![A-Za-z ]{0,22}\b(?:bank|card)\b)([A-Za-z][^\n]*?)(?=\s+on\s+\d|\.?\s+avl\b|[.,]?\s+(?:not you|if not)\b|\.\s|\.$|$)"""),
    )
    private val VPA = Regex("""(?i)\b([a-z0-9][a-z0-9._-]{1,}@[a-z]{2,})\b""")
    private val BAD_MERCHANT = Regex("""(?i)^(your|the|card|bank|a/?c|account|upi|vpa|ref|date|customer|dear|rs|inr)\b|\bcard\b.*\d{4}|^\d""")

    fun read(sms: Sms, sender: SenderInfo): Read {
        if (sender.personal) return Read.Skip(SkipReason.PERSONAL)
        if (sender.promo) return Read.Skip(SkipReason.PROMO)
        val body = sms.body
        val flat = body.replace(Regex("\\s*\\n\\s*"), " ").trim()

        if (OTP.containsMatchIn(flat)) return Read.Skip(SkipReason.OTP)
        if (IPO.containsMatchIn(flat)) return Read.Skip(SkipReason.IPO)
        if (PROMO.containsMatchIn(flat) && !TXN_VERB.containsMatchIn(flat)) return Read.Skip(SkipReason.PROMO)
        if (STATEMENT.containsMatchIn(flat) && !TXN_VERB.containsMatchIn(flat)) return Read.Skip(SkipReason.STATEMENT)

        val core = body.lines().joinToString("\n") { it.replace(INSTRUCTION, " ") }
        val coreFlat = core.replace(Regex("\\s*\\n\\s*"), " ").trim()
        // Read the merchant first, then look for mandate words in the rest, so a card descriptor
        // like "CLAUDE.AI SUBSCRIPTION" or "SPOTIFY SI" doesn't pretend to be a mandate.
        val merchantRaw = findMerchant(core, coreFlat) ?: knownNameAnywhere(coreFlat)
        val ctxText =if (merchantRaw != null) coreFlat.replace(merchantRaw, " ", ignoreCase = true) else coreFlat
        val hasCtx = MANDATE_CTX.containsMatchIn(ctxText)
        val viaMandate = STRICT_CTX.containsMatchIn(ctxText)
        val hasDebitVerb = DEBITED.containsMatchIn(coreFlat)

        val kind: EventKind? = when {
            hasCtx && FAILED_SETUP.containsMatchIn(coreFlat) -> EventKind.FAILED_SETUP
            hasCtx && DEBIT_FAILED.containsMatchIn(coreFlat) -> EventKind.DEBIT_FAILED
            NACH_WORDS.containsMatchIn(coreFlat) && Regex("""(?i)\b(returned|bounced|dishono?u?red)\b""").containsMatchIn(coreFlat) -> EventKind.DEBIT_FAILED
            hasCtx && REVOKED.containsMatchIn(coreFlat) -> EventKind.REVOKED
            hasCtx && PAUSED.containsMatchIn(coreFlat) -> EventKind.PAUSED
            hasCtx && RESUMED.containsMatchIn(coreFlat) -> EventKind.RESUMED
            hasCtx && EXPIRED.containsMatchIn(coreFlat) && !hasDebitVerb -> EventKind.EXPIRED
            UPCOMING_DEBIT.containsMatchIn(coreFlat) -> EventKind.UPCOMING
            UPCOMING_RENEW.containsMatchIn(coreFlat) -> if (PAY_BY_HAND.containsMatchIn(flat)) return Read.Skip(SkipReason.ADVISORY) else EventKind.UPCOMING
            hasCtx && MODIFIED.containsMatchIn(coreFlat) && !hasDebitVerb -> EventKind.MODIFIED
            hasCtx && CREATED.containsMatchIn(coreFlat) -> EventKind.CREATED
            hasDebitVerb -> EventKind.DEBITED
            else -> null
        }

        val mentionsAutopay = AUTOPAY_WORDS.containsMatchIn(flat)
        if (kind == null) {
            if (CREDIT.containsMatchIn(coreFlat)) return Read.Skip(SkipReason.CREDIT)
            if (ADVISORY.containsMatchIn(flat)) return Read.Skip(SkipReason.ADVISORY)
            return if (mentionsAutopay) Read.Unreadable("Mentions autopay, but we couldn't tell what happened")
            else Read.Skip(SkipReason.NOT_RELEVANT)
        }
        if (kind == EventKind.DEBITED && CREDIT.containsMatchIn(coreFlat) && !Regex("(?i)\\bdebited\\b").containsMatchIn(coreFlat) &&
            !Regex("(?i)\\b(spent|sent|paid)\\b").containsMatchIn(coreFlat)
        ) return Read.Skip(SkipReason.CREDIT)
        if (kind == EventKind.DEBITED && !hasCtx && TXN_FAILED.containsMatchIn(coreFlat)) return Read.Skip(SkipReason.NOT_RELEVANT)

        // ---------- Fields ----------
        val received = sms.receivedAt.toLocalDate()
        val amounts = Amounts.find(coreFlat)
        val charge = amounts.firstOrNull { it.role == AmountRole.CHARGE }?.money
        val limit = amounts.firstOrNull { it.role == AmountRole.LIMIT }?.money

        val bank = sender.bank ?: Banks.byBody(flat)
        val cardDigits = CARD_LAST4.find(flat)?.groupValues?.get(1)
        val acctDigits = ACCOUNT_LAST.find(flat)?.groupValues?.get(1)?.takeLast(4)
        val looksCard = CARD_WORDS.containsMatchIn(flat)
        val instrument = when {
            cardDigits != null -> Instrument(true, bank, cardDigits)
            acctDigits != null -> Instrument(false, bank, acctDigits)
            looksCard -> Instrument(true, bank, null)
            bank != null -> Instrument(false, bank, null)
            else -> null
        }

        val umn = UMN.find(flat)?.groupValues?.get(1)
        val umnHandle = umn?.substringAfter('@', "")?.takeIf { it.isNotEmpty() }
        val mandateId = (umn ?: UMRN.find(flat)?.groupValues?.get(1) ?: MANDATE_ID.find(flat)?.groupValues?.get(1)
            ?: ACH_REF.find(flat)?.groupValues?.get(1)?.let { "ACH-$it" })?.uppercase()

        val rail = when {
            NACH_WORDS.containsMatchIn(flat) -> Rail.NACH
            instrument?.isCard == true && umn == null && !Regex("(?i)\\bupi\\b").containsMatchIn(flat) -> Rail.CARD
            UPI_WORDS.containsMatchIn(flat) -> Rail.UPI
            instrument?.isCard == true -> Rail.CARD
            // SMS from the company itself ("Your Netflix membership will renew...") says nothing about how you pay.
            instrument == null -> Rail.UNKNOWN
            else -> Rail.ACCOUNT
        }
        val upiApp = if (rail == Rail.UPI) UpiApps.byHandle(umnHandle) ?: UpiApps.byName(coreFlat) else null

        val merchant = Merchants.resolve(merchantRaw, rail)

        if (kind == EventKind.DEBITED && merchant == null) {
            return if (mentionsAutopay) Read.Unreadable("Money went out for an autopay, but we couldn't tell to whom")
            else Read.Skip(SkipReason.NOT_RELEVANT)
        }
        if (merchant == null && mandateId == null) {
            return Read.Unreadable("Looks like an autopay update, but we couldn't tell which one")
        }

        var frequency: Frequency? = null
        var variable = false
        FREQ.find(coreFlat)?.groupValues?.get(1)?.lowercase()?.let { f ->
            when {
                f.startsWith("as") || f == "on demand" || f.startsWith("ad") -> variable = true
                f.startsWith("one") -> if (kind == EventKind.CREATED) return Read.Skip(SkipReason.NOT_RELEVANT)
                else -> frequency = parseFrequency(f)
            }
        }

        val dates = Dates.findAll(flat, received)
        val date: LocalDate = when (kind) {
            // Never invent a date. "Set for renewal" with no date goes to the "couldn't read" list instead.
            EventKind.UPCOMING -> dates.map { it.date }.firstOrNull { !it.isBefore(received) && ChronoUnit.DAYS.between(received, it) <= 400 }
                ?: return Read.Unreadable("Says a charge is coming, but not when")
            EventKind.DEBITED, EventKind.DEBIT_FAILED -> dates.map { it.date }.firstOrNull {
                ChronoUnit.DAYS.between(it, received) in -1..10
            } ?: received
            else -> received
        }
        val pausedUntil = if (kind == EventKind.PAUSED) dateAfter(flat, PAUSE_UNTIL, dates) else null
        val validTo = if (kind == EventKind.CREATED || kind == EventKind.MODIFIED) {
            dates.filter { it.date.isAfter(received.plusDays(60)) }.lastOrNull()?.takeIf {
                VALID_TO.containsMatchIn(flat.substring(maxOf(0, it.start - 14), it.start))
            }?.date
        } else null

        return Read.Ok(
            Event(
                sms = sms, kind = kind, rail = rail, date = date,
                amount = charge, maxAmount = limit,
                merchantRaw = merchantRaw, merchant = merchant,
                mandateId = mandateId, instrument = instrument,
                frequency = frequency, variableAmount = variable,
                validTo = validTo, pausedUntil = pausedUntil,
                upiApp = upiApp,
                viaMandate = viaMandate || rail == Rail.NACH,
                trialHint = TRIAL.containsMatchIn(coreFlat),
                trustedLinks = TrustedLinks.find(body),
            ),
        )
    }

    private fun findMerchant(core: String, coreFlat: String): String? {
        for ((i, p) in MERCHANT_PATTERNS.withIndex()) {
            // The first two patterns rely on line breaks (multi-line Axis style SMS).
            val text = if (i < 2) core else coreFlat
            for (m in p.findAll(text)) {
                val cleaned = clean(m.groupValues[1]) ?: continue
                return cleaned
            }
        }
        return VPA.findAll(coreFlat).map { it.groupValues[1] }.firstOrNull { v ->
            !Regex("(?i)\\bumn\\b\\s*[:\\-]?\\s*" + Regex.escape(v)).containsMatchIn(coreFlat)
        }
    }

    private val PAY_APPS_AND_BANKS = Regex(
        """(?i)\b(google pay|gpay|phonepe|paytm|bhim|amazon pay|cred|whatsapp|mobikwik|[a-z]+ bank|bank of [a-z]+|sbi|hdfc|icici|kotak|axis)\b""",
    )

    /** Last resort for SMS like "Your Netflix membership will renew...": spot a known company name anywhere. */
    private fun knownNameAnywhere(coreFlat: String): String? =
        Merchants.match(coreFlat.replace(PAY_APPS_AND_BANKS, " "))?.name

    private fun clean(raw: String): String? {
        var s = raw.trim().trimEnd('.', ',', '-', ':', ';').trim()
        s = s.replace(Regex("""(?i)^(?:nach\s*(?:dr|debit)?|ach\s*d|ecs|upi|vpa)\s*[-:]?\s*"""), "")
        s = s.replace(Regex("""(?i)-[a-z0-9]{8,}$"""), "")
        s = s.replace(Regex("""\s*\(.*\)\s*$"""), "").trim()
        if (s.length < 2 || BAD_MERCHANT.containsMatchIn(s)) return null
        if (s.length > 60) return null
        return s
    }

    private fun parseFrequency(f: String): Frequency? = when {
        f == "daily" -> Frequency.DAILY
        f == "weekly" -> Frequency.WEEKLY
        f == "fortnightly" -> Frequency.FORTNIGHTLY
        f.startsWith("bi") -> Frequency.BIMONTHLY
        f.contains("month") -> Frequency.MONTHLY
        f == "quarterly" -> Frequency.QUARTERLY
        f.startsWith("half") || f.startsWith("semi") -> Frequency.HALF_YEARLY
        f.contains("year") || f.startsWith("annual") || f.contains("annum") -> Frequency.YEARLY
        else -> null
    }

    private fun dateAfter(flat: String, word: Regex, dates: List<FoundDate>): LocalDate? {
        val w = word.find(flat) ?: return null
        return dates.firstOrNull { it.start > w.range.last }?.date
    }
}
