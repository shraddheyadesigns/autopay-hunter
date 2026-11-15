# AutoNope (was AutoPay Hunter)

**Test APK:** https://github.com/shraddheyadesigns/autopay-hunter/releases/download/latest/AutoNope.apk
Built by GitHub Actions on every push. Asks for SMS only, no internet. CI checks that on every build.

Reads bank SMS on the phone and finds everything that auto-debits you: UPI AutoPay, card standing instructions, NACH bank mandates, free trials, and repeat card charges with no mandate at all. Then it says it in plain words and tells you exactly where to cancel.

The engine is in `engine/`. A plain test app that runs it on your real inbox is in `app/`. Picking this up in a new chat? Read `CONTEXT.md` first.

## Why it's still worth building

- Since 31 Dec 2025, NPCI makes every UPI app show all your UPI AutoPay mandates. So "one list for UPI" is solved
- Cards, NACH, trials and "did it actually stop?" are not. That's the gap this fills
- Pitch: **UPI got one list. Your cards, bank mandates and trials didn't.**

## How it works

1. **Gate.** Drops SMS from people (unread), ads, OTP messages, statements, IPO blocks and money coming in
2. **Read.** For each bank SMS: what happened (set up, changed, paused, cancelled, failed, upcoming, charged), who, how much, the limit vs the charge, which card or account, the mandate ID, which UPI app, how often, and the date
3. **Group.** Same mandate ID means same subscription. Everything else joins by company + payment type + account
4. **Work out.** Status, next charge, trials, price rises, failed payments, charges after a cancel, paying twice
5. **Explain.** One plain line, a few detail lines, and a cancel route for each

Rules and pattern matching only. No AI calls, no network, no storage. `AutoPayEngine(today).scan(smsList)` is the whole API.

## What the user sees

| Section | What goes there |
|---|---|
| Needs you now | Charged after you cancelled, trial turns paid within 3 days, last payment failed |
| Free trials | Started with ₹1 or ₹2, hasn't turned paid yet |
| Subscriptions | Active, sorted by yearly cost |
| Bills, EMIs and investments | SIPs, loans, insurance, electricity, phone. Shown, never pushed to cancel |
| Might be subscriptions | Seen once or twice. Kept out of the main list so that list stays trustworthy |
| Not charging lately, but still open | Mandate still allowed to charge, nothing charged in a while |
| Paused, Probably ended, Cancelled | As named |
| We couldn't read these | Anything that mentions autopay but couldn't be parsed. Nothing is silently dropped |

## Trust rules built in

- Never invents a date. "Set for renewal" with no date goes to "couldn't read"
- SMS from people's phone numbers are skipped without being read
- Only shows links on bank or NPCI domains (`*.bank.in`, `sihub.in`, `npci.org.in`, known bank sites). Stops look-alike phishing links
- Before cancelling a loan EMI, insurance, SIP or bill, it warns what stopping means
- For card charges with no mandate SMS, it says "cancel inside the app" instead of sending you to a bank page that won't have it
- `Anonymizer` masks IDs, account digits, UPI IDs of people and balances, so an unreadable SMS can be shared to add its format

## The "did it actually stop?" loop

The app keeps small user marks and passes them back in on every scan:

- `cancelledOn`: you said you cancelled it. Any later charge is flagged loudly
- `notASubscription`: hide it
- `trialEndsOn`: you told it when the trial ends

IDs stay stable across scans and SMS order, so marks stick.

## Tests

`./run-tests.sh` runs three suites:

1. **Main test inbox**, 122 SMS across 22 scenarios from 8 banks and card issuers (HDFC, SBI, SBI Card, Axis, ICICI, Kotak, IDFC FIRST, Canara). Every SMS has an expected reading, plus checks on every subscription and on the user-marks loop
2. **Holdout inbox**, 48 SMS with banks and wordings the rules weren't written for (Kotak cards, Yes, Federal, IndusInd, AU, PNB, BoB, Amex, OneCard, HSBC, SMS from the companies themselves, and traps like "renew now" reminders and a daily chai). First run: 25 of 35 read right and 5 silent misses. Fixed with general rules. Now all pass
3. **Buried in noise**: the main inbox plus 5,000 junk SMS (shop spends, transfers to people, OTPs naming Netflix, promos saying "AutoPay") must give the exact same result. It does, in about 2 seconds

## Not verified yet

- **Real SMS.** The test SMS are modelled on real bank formats but written by hand. Your own inbox is the real test
- **Cancel steps inside UPI apps** and the app package names. Marked `verified = false` so the app can say "steps may differ"
- **Card SI portal links.** Only ICICI's (`sihub.in/managesi/icici`) is confirmed. Others come from the bank's own SMS when present
- **Play Store.** "SMS-based money management" is still an allowed reason to read SMS, after a review. Not applied for yet

## Run it on an export

```
kotlinc engine/src/main/kotlin engine/src/cli/kotlin -d build/classes
java -cp build/classes:kotlin-stdlib.jar app.autopayhunter.cli.CliKt sms-backup.xml --evidence --share-misses
```

Reads an "SMS Backup & Restore" XML export (received messages only). Runs on your laptop, nothing is uploaded.

## Files

```
engine/src/main/kotlin/app/autopayhunter/engine/
  AutoPayEngine.kt   the one entry point
  Extractor.kt       reads one SMS into an event
  Builder.kt         groups events, works out status, trials, flags
  Explain.kt         plain-language lines and cancel routes
  Dictionaries.kt    32 banks and card issuers, 10 UPI apps, 87 merchants, trusted link domains
  Dates.kt, Text.kt  every Indian date and money format we've seen
  Model.kt           the data the app will use
  Report.kt          text report, test inbox reader, anonymiser
engine/src/cli/      command line runner for phone exports
engine/src/test/     the checks
testdata/            corpus.txt, holdout.txt, make_noise.py
```

Pure Kotlin with no dependencies, so it drops into the Android app as is (needs minSdk 26 for java.time).
