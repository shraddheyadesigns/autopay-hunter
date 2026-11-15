"""Builds a big noisy inbox (testdata/noise.txt) to check for false positives and speed.
Same bank formats as the corpus, but nothing in here is a subscription."""
import random
from datetime import datetime, timedelta

random.seed(42)
start = datetime(2026, 1, 1)
shops = ["DMART", "RELIANCE FRESH", "STARBUCKS", "PVR CINEMAS", "INDIAN OIL", "HP PETROL PUMP", "ZARA", "MYNTRA",
         "IRCTC", "MAKEMYTRIP", "BOOKMYSHOW", "APOLLO PHARMACY", "CROMA", "DECATHLON", "NATURE S BASKET", "BLUE TOKAI",
         "CHAAYOS", "HALDIRAMS", "LIFESTYLE", "WESTSIDE", "SHOPPERS STOP", "IKEA", "PEPPERFRY", "NYKAA", "AJIO",
         "BARBEQUE NATION", "DOMINOS", "MCDONALDS", "KFC", "THIRD WAVE COFFEE", "CAFE COFFEE DAY", "MEDPLUS"]
people = ["AMIT DESAI", "PRIYA JOSHI", "ROHAN PATIL", "SNEHA KULKARNI", "VIKRAM RAO", "NEHA GUPTA", "ARJUN NAIR",
          "POOJA SHAH", "KUNAL MEHTA", "ANJALI IYER", "SANDEEP KUMAR", "MEERA PILLAI"]
vpas = ["9876543210@ybl", "rohan.patil@okaxis", "priya1990@oksbi", "kiranastore@okhdfcbank", "sharma.dairy@ybl",
        "7700112233@paytm", "puneautofix@okicici"]


def when():
    return start + timedelta(days=random.randint(0, 317), minutes=random.randint(0, 1439))


def block(t, sender, body):
    return f"@ {t:%Y-%m-%d %H:%M} | {sender}\n{body}\n"


out = ["# Generated noise. Nothing here is a subscription.\n"]
for _ in range(1400):
    t = when(); a = random.randint(80, 9000)
    out.append(block(t, "AX-HDFCBK-S", f"Spent Rs.{a} On HDFC Bank Card 4321 At {random.choice(shops)} On {t:%Y-%m-%d:%H:%M:%S} Not You? To Block+Reissue Call 18002586161/SMS BLOCK CC 4321 to 7308080808"))
for _ in range(900):
    t = when(); a = random.randint(10, 20000)
    out.append(block(t, "JD-SBIUPI-S", f"Dear UPI user A/C X5678 debited by {a}.0 on date {t:%d%b%y} trf to {random.choice(people)} Refno {random.randint(10**11, 10**12 - 1)}. If not u? call 1800111109. -SBI"))
for _ in range(700):
    t = when(); a = random.randint(10, 3000)
    out.append(block(t, "AX-KOTAKB-S", f"Sent Rs.{a}.00 from Kotak Bank AC X2468 to {random.choice(vpas)} on {t:%d-%m-%y}.UPI Ref {random.randint(10**11, 10**12 - 1)}. Not you, https://kotak.com/KBANKT/Fraud"))
for _ in range(600):
    t = when(); a = random.randint(100, 50000)
    out.append(block(t, "AD-ICICIT-S", f"INR {a:,}.00 spent using ICICI Bank Card XX9012 on {t:%d-%b-%y} on {random.choice(shops)}. Avl Limit: INR 1,10,000.00. If not you, call 1800 2662/SMS BLOCK 9012 to 9215676766."))
for _ in range(800):
    t = when()
    out.append(block(t, "AX-HDFCBK-T", f"{random.randint(100000, 999999)} is the OTP for txn of INR {random.randint(100, 9999)}.00 at {random.choice(shops)} on HDFC Bank Card XX4321. Valid for 5 mins. Do not share."))
for _ in range(400):
    t = when()
    out.append(block(t, random.choice(["VM-PHONPE-P", "AD-SWIGGY-P", "JD-MYNTRA-P", "VK-ZOMATO-S"]),
                     random.choice(["Flat 50% off on your next order! Use code SAVE50. T&C apply. Click https://x.co/a1",
                                    "Get Rs.100 cashback on your first UPI AutoPay setup. Hurry, offer ends soon!",
                                    "Your favourite restaurants are waiting. Order now and get free delivery."])))
for _ in range(120):
    t = when()
    out.append(block(t, "AX-HDFCBK-S", f"Your A/c XX8642 is credited with Rs.{random.randint(500, 90000):,}.00 on {t:%d-%m-%y} by NEFT from ACME DESIGN STUDIO. Avl Bal Rs.1,02,345.00 -HDFC Bank"))
for _ in range(80):
    t = when()
    out.append(block(t, f"+9198{random.randint(10000000, 99999999)}", random.choice(["Reached?", "Call me when free", "Netflix password kya hai?", "Send the autopay screenshot"])))

open("noise.txt", "w").write("\n".join(out))
print(sum(1 for x in out if x.startswith("@")), "noise SMS written")
