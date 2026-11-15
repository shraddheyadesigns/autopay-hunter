package app.autopayhunter.engine

/**
 * Everything the engine "knows" about India's payment world lives here, as plain data.
 * Adding a bank or a merchant should never need a code change anywhere else.
 */
internal object Banks {
    private class Entry(val bank: Bank, val headers: Set<String>, val body: Regex)

    private fun e(key: String, name: String, headers: String, body: String, portal: String? = null) =
        Entry(Bank(key, name, portal), headers.split(",").map { it.trim() }.toSet(), Regex("(?i)$body"))

    private val ALL = listOf(
        e("hdfc", "HDFC Bank", "HDFCBK,HDFCBN,HDFCCC", """hdfc\s?bank"""),
        // The SI Hub link below comes from ICICI's own Mandate Hub FAQ.
        e("icici", "ICICI Bank", "ICICIB,ICICIT,ICICIO", """icici\s?bank""", "https://www.sihub.in/managesi/icici"),
        e("sbicard", "SBI Card", "SBICRD,SBIPRM,SBICRM", """sbi (?:credit )?card"""),
        e("sbi", "SBI", "SBIUPI,SBIINB,SBIPSG,CBSSBI,ATMSBI,SBMSMS,SBIBNK,SBIOTP", """-\s?sbi\b|state bank of india|\bsbi\b"""),
        e("axis", "Axis Bank", "AXISBK,AXISBN,AXISCC", """axis\s?bank"""),
        e("kotak", "Kotak Bank", "KOTAKB,KOTAKM,KMBANK", """kotak"""),
        e("idfc", "IDFC FIRST Bank", "IDFCFB,IDFCBK,IDFCFC", """idfc\s?first"""),
        e("yes", "Yes Bank", "YESBNK,YESBKS", """yes\s?bank"""),
        e("indusind", "IndusInd Bank", "INDUSB,INDUSL,IBLBNK", """indusind"""),
        e("au", "AU Bank", "AUBANK,AUSFBL", """\bau (?:small finance )?bank"""),
        e("federal", "Federal Bank", "FEDBNK,FEDFIB,FEDBKS", """federal\s?bank"""),
        e("pnb", "PNB", "PNBSMS,PNBBNK", """\bpnb\b|punjab national"""),
        e("bob", "Bank of Baroda", "BOBTXN,BOBSMS,BOBCRD,BOBFIN", """bank of baroda|\bbob\b"""),
        e("canara", "Canara Bank", "CANBNK,CANARA", """canara"""),
        e("union", "Union Bank", "UNIONB,UBOIBK", """union bank"""),
        e("boi", "Bank of India", "BOIIND,BOISMS", """bank of india"""),
        e("indianbank", "Indian Bank", "INDBNK,INDBKS", """\bindian bank"""),
        e("iob", "Indian Overseas Bank", "IOBCHN,IOBBNK", """indian overseas"""),
        e("central", "Central Bank", "CENTBK,CBOIBK", """central bank of india"""),
        e("uco", "UCO Bank", "UCOBNK", """\buco bank"""),
        e("idbi", "IDBI Bank", "IDBIBK", """\bidbi"""),
        e("rbl", "RBL Bank", "RBLBNK,RBLCRD", """\brbl\b"""),
        e("amex", "American Express", "AMEXIN,AMEXCD", """american express|\bamex\b"""),
        e("hsbc", "HSBC", "HSBCIN,HSBCBK", """\bhsbc\b"""),
        e("sc", "Standard Chartered", "SCBANK,STANCB", """standard chartered"""),
        e("paytmbank", "Paytm Payments Bank", "PAYTMB,PYTMBK", """paytm payments bank"""),
        e("airtelbank", "Airtel Payments Bank", "AIRBNK,AIRPBK", """airtel payments bank"""),
        e("ippb", "India Post Payments Bank", "IPBMSG,IPPBNK", """india post payments"""),
        e("onecard", "OneCard", "ONECRD,ONECRT", """onecard"""),
        e("dbs", "DBS Bank", "DBSBNK", """\bdbs\b"""),
        e("dcb", "DCB Bank", "DCBBNK", """\bdcb bank"""),
        e("bandhan", "Bandhan Bank", "BDNSMS,BANDHN", """bandhan bank"""),
    )

    fun byHeader(header: String?): Bank? = header?.let { h -> ALL.firstOrNull { h in it.headers }?.bank }

    /** The bank named in the body wins over nothing; sender header wins over body. */
    fun byBody(body: String): Bank? = ALL.firstOrNull { it.body.containsMatchIn(body) }?.bank

    fun all() = ALL.map { it.bank }
}

internal object UpiApps {
    private class Entry(val app: UpiApp, val handles: Set<String>, val names: Regex)

    private fun e(key: String, name: String, pkg: String?, handles: String, names: String) =
        Entry(UpiApp(key, name, pkg), handles.split(",").map { it.trim() }.toSet(), Regex("(?i)$names"))

    // Handle lists are the well known ones. A UMN or payer VPA ending in one of these tells us the app.
    private val ALL = listOf(
        e("gpay", "Google Pay", "com.google.android.apps.nbu.paisa.user", "okaxis,okhdfcbank,okicici,oksbi", """google pay|\bgpay\b"""),
        e("phonepe", "PhonePe", "com.phonepe.app", "ybl,ibl,axl", """phonepe|phone pe"""),
        e("paytm", "Paytm", "net.one97.paytm", "paytm,ptyes,ptaxis,pthdfc,ptsbi", """\bpaytm\b(?! payments bank)"""),
        e("bhim", "BHIM", "in.org.npci.upiapp", "upi", """\bbhim\b"""),
        e("amazonpay", "Amazon Pay", "in.amazon.mShop.android.shopping", "apl,yapl,rapl", """amazon ?pay\b"""),
        e("cred", "CRED", "com.dreamplug.androidapp", "axisb", """\bcred\b"""),
        e("whatsapp", "WhatsApp", "com.whatsapp", "waicici,wahdfcbank,waaxis,wasbi", """whatsapp"""),
        e("mobikwik", "MobiKwik", "com.mobikwik_new", "ikwik", """mobikwik"""),
        e("navi", "Navi", "com.naviapp", "naviaxis", """\bnavi\b"""),
        e("supermoney", "super.money", null, "superyes", """super\.?money"""),
    )

    fun byHandle(handle: String?): UpiApp? = handle?.lowercase()?.let { h -> ALL.firstOrNull { h in it.handles }?.app }
    fun byName(text: String): UpiApp? = ALL.firstOrNull { it.names.containsMatchIn(text) }?.app
}

internal object Merchants {
    private class Entry(val m: Merchant, val aliases: List<String>)

    private fun sub(key: String, name: String, cat: Category, aliases: String, url: String? = null) =
        Entry(Merchant(key, name, cat, MerchantKind.SUBSCRIPTION, true, url), aliases.split("|"))
    private fun bill(key: String, name: String, cat: Category, aliases: String) =
        Entry(Merchant(key, name, cat, MerchantKind.ESSENTIAL, true), aliases.split("|"))
    private fun shop(key: String, name: String, cat: Category, aliases: String) =
        Entry(Merchant(key, name, cat, MerchantKind.ONE_OFF, true), aliases.split("|"))

    private val ALL = listOf(
        // Streaming and entertainment
        sub("netflix", "Netflix", Category.STREAMING, "NETFLIX", "https://www.netflix.com/cancelplan"),
        sub("amazon-prime", "Amazon Prime", Category.STREAMING, "AMAZON PRIME|PRIME VIDEO|AMAZONPRIME|PRIMEVIDEO|PRIME MEMBERSHIP"),
        sub("jiohotstar", "JioHotstar", Category.STREAMING, "JIOHOTSTAR|JIO HOTSTAR|HOTSTAR|DISNEY PLUS|DISNEYPLUS|JIOCINEMA|NOVI DIGITAL"),
        sub("sonyliv", "SonyLIV", Category.STREAMING, "SONYLIV|SONY LIV|CULVER MAX"),
        sub("zee5", "ZEE5", Category.STREAMING, "ZEE5|ZEE 5"),
        sub("youtube-premium", "YouTube Premium", Category.STREAMING, "YOUTUBE PREMIUM|YOUTUBEPREMIUM|YOUTUBE MUSIC|YOUTUBE|YT PREMIUM", "https://www.youtube.com/paid_memberships"),
        sub("tataplay", "Tata Play", Category.STREAMING, "TATA PLAY|TATAPLAY|TATA SKY"),
        sub("times-prime", "Times Prime", Category.OTHER, "TIMES PRIME|TIMESPRIME"),
        // Music and audio
        sub("spotify", "Spotify", Category.MUSIC, "SPOTIFY", "https://www.spotify.com/account/subscription/"),
        sub("jiosaavn", "JioSaavn", Category.MUSIC, "JIOSAAVN|SAAVN"),
        sub("gaana", "Gaana", Category.MUSIC, "GAANA"),
        sub("audible", "Audible", Category.MUSIC, "AUDIBLE"),
        sub("kindle-unlimited", "Kindle Unlimited", Category.NEWS, "KINDLE UNLIMITED|KINDLE"),
        // Google, Apple, Microsoft
        sub("google-one", "Google One", Category.CLOUD, "GOOGLE ONE|GOOGLEONE|GOOGLE STORAGE"),
        sub("google-play", "Google Play", Category.SOFTWARE, "GOOGLE PLAY|GOOGLEPLAY|PLAY STORE"),
        sub("google", "Google", Category.SOFTWARE, "GOOGLE INDIA|GOOGLE"),
        sub("apple", "Apple", Category.SOFTWARE, "APPLE COM BILL|APPLECOMBILL|APPLE SERVICES|APPLE MEDIA|ITUNES|ICLOUD|APPLE"),
        sub("microsoft", "Microsoft", Category.SOFTWARE, "MICROSOFT|MSBILL|XBOX"),
        // Work tools
        sub("adobe", "Adobe", Category.SOFTWARE, "ADOBE"),
        sub("canva", "Canva", Category.SOFTWARE, "CANVA"),
        sub("figma", "Figma", Category.SOFTWARE, "FIGMA"),
        sub("notion", "Notion", Category.SOFTWARE, "NOTION"),
        sub("github", "GitHub", Category.SOFTWARE, "GITHUB"),
        sub("linkedin", "LinkedIn Premium", Category.WORK, "LINKEDIN"),
        // AI
        sub("chatgpt", "ChatGPT", Category.AI, "CHATGPT|OPENAI"),
        sub("claude", "Claude", Category.AI, "CLAUDE AI|CLAUDEAI|ANTHROPIC|CLAUDE"),
        sub("perplexity", "Perplexity", Category.AI, "PERPLEXITY"),
        sub("midjourney", "Midjourney", Category.AI, "MIDJOURNEY"),
        // Memberships on top of shopping and food apps
        sub("swiggy-one", "Swiggy One", Category.FOOD, "SWIGGY ONE|SWIGGYONE"),
        sub("zomato-gold", "Zomato Gold", Category.FOOD, "ZOMATO GOLD|ZOMATOGOLD"),
        sub("uber-one", "Uber One", Category.OTHER, "UBER ONE|UBERONE"),
        sub("flipkart-vip", "Flipkart VIP", Category.SHOPPING, "FLIPKART VIP|FLIPKART PLUS PREMIUM"),
        // Fitness, learning, other apps
        sub("cultfit", "Cult.fit", Category.FITNESS, "CULTFIT|CULT FIT|CUREFIT|CURE FIT"),
        sub("healthifyme", "HealthifyMe", Category.FITNESS, "HEALTHIFYME|HEALTHIFY"),
        sub("duolingo", "Duolingo", Category.LEARNING, "DUOLINGO"),
        sub("coursera", "Coursera", Category.LEARNING, "COURSERA"),
        sub("truecaller", "Truecaller", Category.SOFTWARE, "TRUECALLER"),
        sub("tinder", "Tinder", Category.DATING, "TINDER"),
        sub("bumble", "Bumble", Category.DATING, "BUMBLE"),
        // Phone, internet, utilities
        bill("airtel", "Airtel", Category.TELECOM, "BHARTI AIRTEL|AIRTEL"),
        bill("jio", "Jio", Category.TELECOM, "RELIANCE JIO|JIO PLATFORMS|JIOHOME|JIO HOME|JIOFIBER|JIO FIBER|JIO AIRFIBER|JIO"),
        bill("vi", "Vi", Category.TELECOM, "VODAFONE IDEA|VODAFONE|VI POSTPAID"),
        bill("act", "ACT Fibernet", Category.TELECOM, "ACT FIBERNET|ACTFIBERNET|ATRIA CONVERGENCE"),
        bill("msedcl", "MSEDCL electricity", Category.UTILITIES, "MSEDCL|MAHADISCOM|MAHAVITARAN|MAHARASHTRA STATE ELECTRICITY"),
        bill("mngl", "MNGL gas", Category.UTILITIES, "MNGL|MAHARASHTRA NATURAL GAS"),
        bill("tata-power", "Tata Power", Category.UTILITIES, "TATA POWER"),
        bill("adani-electricity", "Adani Electricity", Category.UTILITIES, "ADANI ELECTRICITY"),
        bill("bescom", "BESCOM", Category.UTILITIES, "BESCOM"),
        // Investments
        bill("hdfc-mf", "HDFC Mutual Fund", Category.INVESTMENTS, "HDFC MUTUAL FUND|HDFC MF|HDFCMF|HDFC AMC"),
        bill("icici-pru-mf", "ICICI Prudential MF", Category.INVESTMENTS, "ICICI PRUDENTIAL MUTUAL|ICICI PRU MF|ICICIPRUMF"),
        bill("sbi-mf", "SBI Mutual Fund", Category.INVESTMENTS, "SBI MUTUAL FUND|SBI MF|SBIMF|SBI FUNDS"),
        bill("axis-mf", "Axis Mutual Fund", Category.INVESTMENTS, "AXIS MUTUAL FUND|AXIS MF"),
        bill("nippon-mf", "Nippon India MF", Category.INVESTMENTS, "NIPPON"),
        bill("ppfas", "Parag Parikh MF", Category.INVESTMENTS, "PPFAS|PARAG PARIKH"),
        bill("mirae", "Mirae Asset MF", Category.INVESTMENTS, "MIRAE"),
        bill("kotak-mf", "Kotak Mutual Fund", Category.INVESTMENTS, "KOTAK MUTUAL|KOTAK MF"),
        bill("bse-sip", "Mutual fund SIP (BSE)", Category.INVESTMENTS, "ICCL|INDIAN CLEARING CORP|BSE LIMITED|BSE LTD|BSESTARMF"),
        bill("nse-sip", "Mutual fund SIP (NSE)", Category.INVESTMENTS, "NSE CLEARING|NSCCL|NSEMF|NSE MFSS"),
        bill("groww", "Groww", Category.INVESTMENTS, "GROWW"),
        bill("zerodha", "Zerodha", Category.INVESTMENTS, "ZERODHA"),
        bill("rd", "Recurring deposit", Category.INVESTMENTS, "RECURRING DEPOSIT|RD INSTALLMENT|RD INSTALMENT"),
        bill("cc-bill", "Credit card bill", Category.OTHER,"CREDIT CARD BILL|CARD BILL PAYMENT|CREDIT CARD DUES|CC BILL"),
        // Loans
        bill("bajaj-finance", "Bajaj Finance", Category.LOANS, "BAJAJ FINANCE|BAJAJ FIN|BAJAJFINANCE"),
        bill("tata-capital", "Tata Capital", Category.LOANS, "TATA CAPITAL"),
        bill("home-credit", "Home Credit", Category.LOANS, "HOME CREDIT"),
        bill("lic-hfl", "LIC Housing Finance", Category.LOANS, "LIC HOUSING"),
        // Insurance
        bill("lic", "LIC", Category.INSURANCE, "LIFE INSURANCE CORPORATION|LIC OF INDIA|LICI|LIC"),
        bill("hdfc-life", "HDFC Life", Category.INSURANCE, "HDFC LIFE|HDFCLIFE"),
        bill("hdfc-ergo", "HDFC ERGO", Category.INSURANCE, "HDFC ERGO|HDFCERGO"),
        bill("icici-pru-life", "ICICI Pru Life", Category.INSURANCE, "ICICI PRU LIFE|ICICI PRUDENTIAL LIFE"),
        bill("icici-lombard", "ICICI Lombard", Category.INSURANCE, "ICICI LOMBARD"),
        bill("sbi-life", "SBI Life", Category.INSURANCE, "SBI LIFE"),
        bill("star-health", "Star Health", Category.INSURANCE, "STAR HEALTH"),
        bill("niva-bupa", "Niva Bupa", Category.INSURANCE, "NIVA BUPA|MAX BUPA"),
        bill("care-health", "Care Health", Category.INSURANCE, "CARE HEALTH"),
        bill("acko", "Acko", Category.INSURANCE, "ACKO"),
        bill("digit", "Digit Insurance", Category.INSURANCE, "GO DIGIT|DIGIT INSURANCE"),
        bill("tata-aia", "Tata AIA", Category.INSURANCE, "TATA AIA"),
        bill("max-life", "Axis Max Life", Category.INSURANCE, "AXIS MAX LIFE|MAX LIFE"),
        bill("bajaj-allianz", "Bajaj Allianz", Category.INSURANCE, "BAJAJ ALLIANZ"),
        bill("policybazaar", "Policybazaar", Category.INSURANCE, "POLICYBAZAAR|POLICY BAZAAR"),
        // Shops: we link them to a mandate if one exists, but never guess they recur
        shop("swiggy", "Swiggy", Category.FOOD, "SWIGGY|INSTAMART"),
        shop("zomato", "Zomato", Category.FOOD, "ZOMATO|BLINKIT"),
        shop("amazon", "Amazon", Category.SHOPPING, "AMAZON|AMZN"),
        shop("flipkart", "Flipkart", Category.SHOPPING, "FLIPKART"),
        shop("uber", "Uber", Category.OTHER, "UBER"),
        shop("ola", "Ola", Category.OTHER, "OLA CABS|ANI TECHNOLOGIES"),
    )

    private class Alias(val entry: Entry, val text: String, val spaced: String, val squashed: String)

    // Longest alias first, so "SWIGGY ONE" beats "SWIGGY" and "YOUTUBE PREMIUM" beats "GOOGLE".
    private val INDEX = ALL.flatMap { e -> e.aliases.map { Alias(e, it, Norm.spaced(it), Norm.squashed(it)) } }
        .sortedByDescending { it.squashed.length }

    /** Strip payment-gateway and legal noise before matching or showing a name. */
    private val NOISE = Regex(
        """(?i)\b(payu|razorpay|rzp|cashfree|billdesk|ccavenue|juspay|paytm pg|pvt|private|ltd|limited|llp|inc|india|services|technologies|entertainment|digital|online|com|www|upi|autopay|auto pay|mandate|subscr(?:iption)?|si|recurring|nach|dr|ach)\b""",
    )

    fun match(raw: String?): Merchant? {
        if (raw.isNullOrBlank()) return null
        val spaced = " " + Norm.spaced(raw) + " "
        val squashed = Norm.squashed(raw)
        val hit = INDEX.firstOrNull { a ->
            // Short aliases must stand alone as words: "APPLE" shouldn't match "PINEAPPLE CAFE".
            if (a.squashed.length <= 5) spaced.contains(" " + a.spaced + " ")
            else squashed.contains(a.squashed)
        }
        return hit?.entry?.m
    }

    /** Known merchant, or a tidy guess built from what the SMS said. */
    fun resolve(raw: String?, rail: Rail): Merchant? {
        match(raw)?.let { return it }
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace(NOISE, " ").replace(Regex("[^A-Za-z0-9&.' ]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.length < 2) return null
        val name = Norm.title(cleaned)
        val key = "x-" + Norm.squashed(cleaned).lowercase().take(24)
        val kind = if (rail == Rail.NACH) MerchantKind.ESSENTIAL else MerchantKind.SUBSCRIPTION
        return Merchant(key, name, Category.OTHER, kind, known = false)
    }
}

/** Sender headers look like "AX-HDFCBK", "VM-HDFCBK-S" (TRAI suffix), "HDFCBK", or a phone number. */
internal data class SenderInfo(val personal: Boolean, val promo: Boolean, val header: String?, val bank: Bank?)

internal object Senders {
    /** Indian mobile numbers and international numbers are people. 5 to 8 digit short codes are services. */
    private val MOBILE = Regex("""^(\+?91)?[6-9]\d{9}$""")
    private val INTERNATIONAL = Regex("""^\+\d{10,15}$""")

    fun parse(address: String): SenderInfo {
        val a = address.trim()
        val digits = a.replace(Regex("[ \\-]"), "")
        if (MOBILE.matches(digits) || INTERNATIONAL.matches(digits)) return SenderInfo(personal = true, promo = false, header = null, bank = null)
        val parts = a.uppercase().split("-").map { it.trim() }.filter { it.isNotEmpty() }
        val suffix = parts.lastOrNull()?.takeIf { it.length == 1 }
        val header = parts.filter { it.length >= 5 }.maxByOrNull { it.length } ?: parts.firstOrNull()
        return SenderInfo(personal = false, promo = suffix == "P", header = header, bank = Banks.byHeader(header))
    }
}

/**
 * A link is only shown if it points at a bank or NPCI. RBI reserves ".bank.in" for banks,
 * which makes that the strongest single check against phishing links in look-alike SMS.
 */
internal object TrustedLinks {
    private val LINK = Regex("""(?i)\b(?:https?://)?((?:[a-z0-9-]+\.)+[a-z]{2,})(/[^\s,)]*)?""")
    private val TRUSTED = listOf(
        Regex("""(?i)(^|\.)bank\.in$"""),
        Regex("""(?i)(^|\.)sihub\.in$"""),
        Regex("""(?i)(^|\.)npci\.org\.in$"""),
        Regex("""(?i)(^|\.)(hdfcbank\.com|icicibank\.com|sbicard\.com|onlinesbi\.sbi|sbi\.co\.in|axisbank\.com|kotak\.com|idfcfirstbank\.com|yesbank\.in|indusind\.com|federalbank\.co\.in|americanexpress\.com|aubank\.in|rblbank\.com|hsbc\.co\.in|sc\.com)$"""),
    )

    fun find(body: String): List<String> = LINK.findAll(body).mapNotNull { m ->
        val host = m.groupValues[1].lowercase()
        if (host.count { it == '.' } < 1 || host.matches(Regex("""[0-9.]+"""))) return@mapNotNull null
        if (TRUSTED.none { it.containsMatchIn(host) }) return@mapNotNull null
        val path = m.groupValues[2].trimEnd('.', ',')
        "https://$host$path"
    }.distinct().toList()
}
