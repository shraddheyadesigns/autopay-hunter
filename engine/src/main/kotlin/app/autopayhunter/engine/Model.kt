package app.autopayhunter.engine

import java.time.LocalDate
import java.time.LocalDateTime

/** One SMS as it comes off the phone. Nothing else is needed. */
data class Sms(
    val id: String,
    val sender: String,
    val body: String,
    val receivedAt: LocalDateTime,
)

/** Money in minor units (paise / cents) so sums never drift. */
data class Money(val minor: Long, val currency: String = "INR") {
    val isInr get() = currency == "INR"
    operator fun times(n: Int) = Money(minor * n, currency)
    companion object {
        fun inr(rupees: Double) = Money(Math.round(rupees * 100), "INR")
    }
}

/** How the money moves. Decides where you go to cancel. UNKNOWN = SMS from the company, not the bank. */
enum class Rail { UPI, CARD, NACH, ACCOUNT, UNKNOWN }

enum class EventKind {
    CREATED, MODIFIED, PAUSED, RESUMED, REVOKED, EXPIRED, FAILED_SETUP,
    UPCOMING, DEBITED, DEBIT_FAILED,
}

enum class Frequency(val days: Int, val perYear: Int, val every: String) {
    DAILY(1, 365, "day"),
    WEEKLY(7, 52, "week"),
    FORTNIGHTLY(14, 26, "2 weeks"),
    MONTHLY(30, 12, "month"),
    BIMONTHLY(61, 6, "2 months"),
    QUARTERLY(91, 4, "3 months"),
    HALF_YEARLY(182, 2, "6 months"),
    YEARLY(365, 1, "year"),
}

enum class Category(val label: String) {
    STREAMING("Streaming"), MUSIC("Music & audio"), SHOPPING("Shopping"), FOOD("Food"),
    CLOUD("Cloud storage"), SOFTWARE("Apps & software"), AI("AI tools"), WORK("Work"),
    NEWS("News & reading"), FITNESS("Fitness"), LEARNING("Learning"), DATING("Dating"),
    TELECOM("Phone & internet"), UTILITIES("Utilities"), INSURANCE("Insurance"),
    INVESTMENTS("Investments"), LOANS("Loans"), OTHER("Other"),
}

/**
 * SUBSCRIPTION: optional, a kill candidate.
 * ESSENTIAL: bills, EMIs, SIPs, insurance. Shown, but never pushed to cancel.
 * ONE_OFF: shops you buy from now and then (Swiggy, Amazon). Never guessed as recurring.
 */
enum class MerchantKind { SUBSCRIPTION, ESSENTIAL, ONE_OFF }

data class Merchant(
    val key: String,
    val name: String,
    val category: Category,
    val kind: MerchantKind,
    val known: Boolean,
    val manageUrl: String? = null,
)

data class Bank(
    val key: String,
    val name: String,
    /** Card standing-instruction portal, only when we've confirmed it's real. */
    val siPortal: String? = null,
)

data class UpiApp(
    val key: String,
    val name: String,
    val packageName: String?,
)

data class Instrument(val isCard: Boolean, val bank: Bank?, val last4: String?) {
    fun label(): String {
        val b = bank?.name ?: if (isCard) "card" else "bank"
        // "SBI Card ••3344", not "SBI Card card ••3344"
        val card = if (b.endsWith("card", ignoreCase = true)) b else "$b card"
        return when {
            isCard && last4 != null -> "$card ••$last4"
            isCard -> card
            last4 != null -> "$b account ••$last4"
            else -> "$b account"
        }
    }
}

/** One meaningful thing an SMS told us. */
data class Event(
    val sms: Sms,
    val kind: EventKind,
    val rail: Rail,
    val date: LocalDate,
    val amount: Money?,
    val maxAmount: Money?,
    val merchantRaw: String?,
    val merchant: Merchant?,
    val mandateId: String?,
    val instrument: Instrument?,
    val frequency: Frequency?,
    val variableAmount: Boolean,
    val validTo: LocalDate?,
    val pausedUntil: LocalDate?,
    val upiApp: UpiApp?,
    /** True when the SMS itself says this is a mandate / autopay / SI / NACH. */
    val viaMandate: Boolean,
    val trialHint: Boolean,
    /** Links found in the SMS that point at a real bank or NPCI domain. */
    val trustedLinks: List<String>,
)

/** What happened to each SMS. Powers "show me exactly what you read". */
sealed class Disposition {
    data class Used(val kind: EventKind) : Disposition()
    data class Skipped(val reason: SkipReason) : Disposition()
    data class Unreadable(val why: String) : Disposition()
}

enum class SkipReason(val label: String) {
    PERSONAL("From a person, not read"),
    PROMO("Advert"),
    OTP("One-time password"),
    STATEMENT("Card statement or due reminder"),
    ADVISORY("General reminder from the bank"),
    IPO("IPO money block, not a subscription"),
    CREDIT("Money coming in"),
    NOT_RELEVANT("Not a payment"),
    DUPLICATE("Same SMS received twice"),
}

enum class Status {
    /** Charging as expected. */
    ACTIVE,
    /** Mandate is open but nothing has been charged in a long time. */
    QUIET,
    PAUSED,
    /** Cancelled, revoked or expired. */
    CANCELLED,
    /** Only seen as repeat charges (no mandate SMS), and they stopped. */
    PROBABLY_ENDED,
    /** Free trial that hasn't turned paid yet. */
    TRIAL,
}

enum class Confidence { HIGH, MEDIUM, LOW }

enum class ClosedBy { REVOKED, EXPIRED, USER }

sealed class Flag {
    /** Money left after a cancel. The worst thing this app can find. */
    data class ChargedAfterCancel(val cancelledOn: LocalDate, val charge: Money, val chargedOn: LocalDate, val alreadyScheduled: Boolean) : Flag()
    data class TrialEndsSoon(val endsOn: LocalDate, val price: Money?) : Flag()
    data class PaymentFailing(val failedOn: LocalDate) : Flag()
    data class PriceWentUp(val from: Money, val to: Money, val on: LocalDate) : Flag()
    /** The mandate lets them take far more than they normally do. */
    data class HighLimit(val limit: Money) : Flag()
    data class AlsoPaidElsewhere(val otherId: String, val otherInstrument: String) : Flag()
    data class VariableAmount(val low: Money, val high: Money) : Flag()
}

data class Trial(
    val startedOn: LocalDate,
    /** Known only when the bank has sent the first "will be charged" notice. */
    val endsOn: LocalDate?,
    val price: Money?,
    /** Common trial lengths, so the app can ask "when does it end?" with good defaults. */
    val likelyEnds: List<LocalDate>,
)

data class CancelGuide(
    val title: String,
    val steps: List<String>,
    /** Android package to open, when we know which app holds it. */
    val openApp: UpiApp?,
    val url: String?,
    /** Also stop it with the company, so they don't keep the account billing. */
    val merchantUrl: String?,
    /** Shown before the user acts, e.g. "this is a loan EMI". */
    val warning: String?,
    /** False until the steps are checked on a real phone. The app should say "steps may differ". */
    val verified: Boolean,
)

/** MAYBE keeps one-off looking charges out of the main list, so the main list stays trustworthy. */
enum class Section { NEEDS_ATTENTION, TRIALS, SUBSCRIPTIONS, BILLS_AND_INVESTMENTS, MAYBE, QUIET, PAUSED, PROBABLY_ENDED, CANCELLED }

data class Subscription(
    /** Stable across scans, so user marks ("I cancelled this") stick. */
    val id: String,
    val merchant: Merchant,
    val rail: Rail,
    val instrument: Instrument?,
    val upiApp: UpiApp?,
    val mandateId: String?,
    val amount: Money?,
    val maxAmount: Money?,
    val frequency: Frequency?,
    val frequencyStated: Boolean,
    val firstSeen: LocalDate,
    val lastCharged: LocalDate?,
    val nextCharge: LocalDate?,
    val nextChargeConfirmed: Boolean,
    val status: Status,
    val statusSince: LocalDate?,
    /** Why it's closed, so the words are right: "Cancelled", "Ended", or "You marked it cancelled". */
    val closedBy: ClosedBy?,
    val pausedUntil: LocalDate?,
    val trial: Trial?,
    /** Yearly cost in the charge currency. Null when we can't tell how often. */
    val yearlyCost: Money?,
    val confidence: Confidence,
    val flags: List<Flag>,
    val section: Section,
    val events: List<Event>,
    val headline: String,
    val detail: List<String>,
    val cancel: CancelGuide,
)

data class Suspect(val sms: Sms, val why: String)

data class ScanStats(
    val total: Int,
    val personalSkipped: Int,
    val bankMessagesRead: Int,
    val eventsFound: Int,
)

data class Totals(
    /** Per currency: what active optional subscriptions cost a year. */
    val subscriptionsYearly: Map<String, Long>,
    val billsYearly: Map<String, Long>,
    val someEstimated: Boolean,
)

data class ScanResult(
    val today: LocalDate,
    val subscriptions: List<Subscription>,
    val suspects: List<Suspect>,
    val dispositions: Map<String, Disposition>,
    val stats: ScanStats,
    val totals: Totals,
) {
    fun section(s: Section) = subscriptions.filter { it.section == s }
}

/** Things the user tells the app. Kept by the app, passed back in on every scan. */
data class UserMark(
    val cancelledOn: LocalDate? = null,
    val notASubscription: Boolean = false,
    val trialEndsOn: LocalDate? = null,
)
