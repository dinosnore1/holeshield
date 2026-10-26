# Closed testing plan (personal Play account)

A new personal Google Play developer account must run a closed test with **at
least 12 testers opted in continuously for 14 days** before it can apply for
production access. This is a Play policy gate; the app itself is ready.

## Tester tracker

Copy this table into a spreadsheet and keep it current. Play may ask for the
opt-in dates during the production-access review.

| # | Name | Google account email | Invite sent | Opted in (date) | Day 14 (date) | Last seen / notes |
|---|---|---|---|---|---|---|
| 1 |  |  |  |  |  |  |
| 2 |  |  |  |  |  |  |
| 3 |  |  |  |  |  |  |
| 4 |  |  |  |  |  |  |
| 5 |  |  |  |  |  |  |
| 6 |  |  |  |  |  |  |
| 7 |  |  |  |  |  |  |
| 8 |  |  |  |  |  |  |
| 9 |  |  |  |  |  |  |
| 10 |  |  |  |  |  |  |
| 11 |  |  |  |  |  |  |
| 12 |  |  |  |  |  |  |
| 13 |  |  |  |  |  |  |
| 14 |  |  |  |  |  |  |

Keep 2–3 extra testers in case one drops out. A tester who leaves the test or
uninstalls for a long period can reset the continuous 14-day requirement.

## Play Console steps

1. **Internal testing** → create a release → upload `build/holeshield.aab` →
   add your own account as a tester → install from the Play link → run the smoke
   test below.
2. **Closed testing** → create a track (for example `beta`) → promote the same
   release → **Testers** → create an email list with the 12+ Google accounts.
3. Copy the **opt-in URL** and send it to the testers.
4. Ask each tester to:
   - open the opt-in link and tap **Become a tester**;
   - install Hole Shield from the Play Store link;
   - open the app, accept the VPN disclosure and Android VPN prompt;
   - leave it installed for at least 14 days.
5. Check **Closed testing → Testers** and the tracker every few days. Replace
   anyone who has not opted in.
6. After 14 continuous days, go to **Production → Apply for production
   access**, answer the questions, and explain that this is a DNS filtering
   utility for phones and TVs.

## Invite message

> Hi — I’m testing Hole Shield, an Android TV/phone DNS filter that blocks ads
> and trackers on the device. Could you join the closed test and keep it
> installed for 14 days? It is free, has no ads or account, and does not route
> your traffic through a remote VPN.
>
> 1. Open this link on your Android phone or TV: [OPT-IN LINK]
> 2. Tap **Become a tester**.
> 3. Install **Hole Shield** from the Play Store link on that page.
> 4. Open it, tap **Turn on protection**, read the VPN disclosure, tap **Agree
>    and continue**, then approve Android’s VPN prompt.
> 5. Please keep it installed until [DATE]. If you hit a problem, send me a
>    screenshot and the Android version.
>
> Thanks!

## Tester smoke checklist

Ask testers to report:

- Phone or TV model and Android version.
- Whether the VPN disclosure appeared before Android’s VPN prompt.
- Whether protection turned on and the ongoing notification appeared.
- Whether `doubleclick.net` is blocked and `example.com` resolves (Settings →
  Test protection).
- Whether the dashboard opens at `http://DEVICE-IP:8080/admin/` on the same
  Wi-Fi.
- Any crash, layout clipping, or TV remote focus problem.
- Portrait and landscape on phones.

## Reviewer notes for production access

Suggested text:

> Hole Shield is a DNS filtering utility for Android phones and TVs. It uses
> Android's VpnService only to route DNS lookups through an on-device resolver;
> it does not operate a remote VPN tunnel or collect user traffic. The app has
> no accounts, ads, analytics or in-app purchases. The closed test covered
> phones and Android TV, including the VPN disclosure, Android VPN consent,
> foreground service, blocklist updates, query log, dashboard and LAN DNS.
