# AutoPay Hunter: handoff for the next chat

Everything a new chat needs to pick this up. Written 15 Nov 2026, after the engine was built and pushed.

---

## Who and how

- **Shady**, freelance UI/UX designer in Pune. Building useful AI side projects for his portfolio. Engine first, UI later
- **Phone:** OnePlus Nord CE6 (Android). Test earbuds and everything else on it
- **No Android Studio.** Every change goes to GitHub, GitHub Actions builds the APK, he installs it from one fixed link. Same setup as `shraddheyadesigns/earphone-finder`
- **Working rules he set:**
  - Don't start anything until he says go
  - Ask before every build or push
  - When he asks for an explanation, explain only, don't build
  - Headings and bullets, warm and short, no em dashes
- **Model:** Sonnet for building. Opus only for planning and bugs that ate an hour
- **Tokens matter.** Batch feedback, paste only the first error from a failed build, no long recaps

---

## What the app is

Reads bank SMS on the phone and finds everything that auto-debits you, then says it plainly and shows exactly where to cancel.

- **The gap moved.** Since 31 Dec 2025, NPCI makes every UPI app show all your UPI AutoPay mandates (plus upihelp.npci.org.in, and BHIM MyUPI from Sept 2026). So UPI alone is solved
- **Still open:** card standing instructions (bank by bank), NACH bank mandates, free trials, card charges with no mandate SMS, and "did it actually stop after I cancelled?"
- **Pitch:** UPI got one list. Your cards, bank mandates and trials didn't
- **Case study angle:** designing trust for an app that reads bank texts
- **Play Store:** "SMS-based money management" is still an allowed reason to read SMS, after Google reviews it. Not applied for

---

## Status: engine done, in this repo

- Pure Kotlin, zero dependencies, `engine/src/main/kotlin/app/autopayhunter/engine/`. Needs minSdk 26 (java.time)
- **API:** `AutoPayEngine(today, userMarks).scan(smsList)` → `ScanResult` with `subscriptions`, `suspects` ("couldn't read"), `dispositions` (what happened to each SMS), `stats`, `totals`
- **Input:** `Sms(id, sender, body, receivedAt: LocalDateTime)`. That's all the app needs to read from the inbox
- **Pipeline:** gate (people, ads, OTPs, statements, IPO blocks, credits) → read each SMS into an event → group (mandate ID first, then company + rail + account) → work out status, next charge, trials, flags → plain-language lines + cancel route
- **Rails:** UPI, CARD, NACH, ACCOUNT, UNKNOWN (SMS sent by the company itself)
- **Sections:** Needs you now, Free trials, Subscriptions, Bills/EMIs/investments, Might be subscriptions, Not charging lately but still open, Paused, Probably ended, Cancelled
- **Flags:** charged after cancel, trial ends soon, payment failing, price went up, high mandate limit, paying twice, variable bill
- **Cancel routes:**
  - UPI → any UPI app (opens the app it was set up in), or upihelp.npci.org.in
  - Card → the bank's SI page, using the opt-out link from the bank's own SMS when it's on a trusted domain
  - NACH → bank mandates page
  - No-mandate card charges and company-sent SMS → cancel inside the app
  - Warnings before stopping EMIs, insurance, SIPs, bills, card bill autopay
- **User marks** (the app stores these and passes them back each scan): `cancelledOn`, `notASubscription`, `trialEndsOn`. Subscription IDs are stable across scans
- **Trust rules:** never invents a date, skips SMS from people's numbers unread, only shows links on `*.bank.in`, sihub.in, npci.org.in or known bank sites, no network, no AI
- `Report.render(result)` gives a plain-text report. `Anonymizer.clean(body)` masks IDs, digits, people's UPI IDs and balances so an unreadable SMS can be shared safely

## Tests

- `./run-tests.sh` (needs a JDK and kotlinc). Three suites:
  1. `testdata/corpus.txt`: 122 hand-written SMS, 22 scenarios, 8 banks. Every SMS and every subscription checked, plus the user-marks loop
  2. `testdata/holdout.txt`: 48 SMS from banks and wordings the rules weren't tuned on. First run had 5 silent misses, fixed with general rules
  3. Noise: the corpus plus 5,000 junk SMS (`make_noise.py`) must give the identical result. About 2 s
- All pass. Gradle and Maven were blocked in the workspace where it was built, so tests ran with plain kotlinc. There's no Gradle setup yet

## Not verified yet

- **Real SMS.** All test SMS are hand-written, modelled on real formats. His own inbox is the real test
- Cancel steps inside UPI apps, and the app package names (`verified = false`)
- Card SI portal links, except ICICI's `sihub.in/managesi/icici`

---

## Next: the test APK (nothing built yet)

Goal: run the engine on his real inbox. Done when it finds every autopay on his phone with no misses.

- **Project:** add an Android `app/` module to this repo that uses `engine/` (as a plain Kotlin JVM library module, or the sources directly). Kotlin + Compose, matching Earphone Finder
- **Permissions:** `READ_SMS` only. **No INTERNET permission at all**, and strip any a library merges in (`tools:node="remove"`). This is the core trust promise
- **Read the inbox:** `Telephony.Sms.Inbox` via ContentResolver (address, body, date)
- **Two plain screens, no design:**
  - **Scan:** allow SMS, the Android 15+ help (App info → ⋮ → Allow restricted settings → Permissions → SMS → Allow, with a button to open App info), then Scan
  - **Report:** `Report.render` output, scrollable, plus "Copy unreadable ones (masked)" using `Anonymizer`
- **GitHub Actions:** on push, run the tests, build a signed release APK, publish it to a `latest` release with a fixed name, so the link never changes: `https://github.com/shraddheyadesigns/autopay-hunter/releases/download/latest/AutoPayHunter.apk`
- **Signing key:**
  - Must never be committed. `.gitignore` already blocks `*.jks`, `*.keystore`, `keystore.properties`
  - Generate it once (keytool in Claude's workspace is fine), then Shady pastes the base64 key and passwords into GitHub → repo Settings → Secrets and variables → Actions
  - He should also keep a private backup. If it's lost, new builds can't install over old ones
- **Size:** expect roughly 2 to 6 MB
- **On his phone:** Android 15+ blocks SMS for sideloaded apps until he allows restricted settings once
- **Later:** Google's developer verification for sideloaded apps hits India in 2027, not yet

## After that

1. **UI prototype:** in the browser (artifact) or Figma, using engine output as sample data. Fast and cheap to iterate. Design Home and Detail first
2. **Real app screens:** Welcome (what it reads and never does), Permission help, Scanning (live counts, people skipped unread), Home (yearly total + sections), Detail (flags + "What we read" evidence), Cancel (steps, open app or bank page, warnings, "I've cancelled it"), Did it stop? (watching / stopped / still charged), Couldn't read, Settings & privacy. Sheets: trial end date, "Not a subscription?". Notifications: trial ends tomorrow, charged after cancel, payment failed, new autopay found
3. **Build order:** Home → Detail → Cancel → Welcome and permission → Scanning → the rest
4. Polish, then the case study

---

## Repo

- `shraddheyadesigns/autopay-hunter`, **public** by Shady's choice. Signing key stays in Secrets so it can go private any time without risk
- Project doc in the Builds project: `claude/autopay-hunter-context.md` (same content as this file)
