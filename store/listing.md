# Hole Shield — Google Play listing and declarations

Working copy for the Play Console. Keep the app name, package and text in sync
with `AndroidManifest.xml` and `docs/privacy.html`.

## App identity

| Field | Value |
|---|---|
| App name | **Hole Shield: DNS Ad Blocker** (27 characters) |
| Package | `com.dino.holeshield` |
| Default language | English (United States) |
| App or game | App |
| Free or paid | Free |
| Category | Tools |
| Tags | DNS, ad blocker, privacy, network, TV |
| Contact email | `gaganpachory@gmail.com` |
| Website | `https://dinosnore1.github.io/holeshield-privacy/` |
| Privacy policy | `https://dinosnore1.github.io/holeshield-privacy/privacy.html` |
| Version | 2.3.0 (`versionCode 7`) |
| Minimum Android | 8.0 (API 26) |
| Target Android | 16 (API 36) |
| Form factors | Phone, tablet, Android TV |
| Contains ads | No |
| In-app purchases | No |
| Account required | No |

The launcher label is **Hole Shield** on phones and **Hole Shield TV** on
televisions. The Play listing title stays the same for both form factors.

## Store listing text

### Short description (77 / 80)

> Block ads and trackers on Android TV and phones with on-device DNS filtering.

### Promotional text (87 / 170)

> Block ads and trackers on your TV and phone. No root, no account, no remote VPN tunnel.

### Full description

```
Hole Shield blocks ads, trackers and TV telemetry at the DNS layer — on your
device. It works on Android TV, Google TV and Android phones without root,
without an account and without sending your traffic through a remote VPN.

ON-DEVICE DNS FILTERING
Hole Shield runs a local, DNS-only VPN using Android's VpnService. Every app's
DNS lookups are checked against your blocklists on the device. Video, downloads
and normal network traffic are not routed through a remote server.

BUILT-IN GRAVITY ENGINE
• Ships with a conservative seed list.
• One-tap maximum preset with up to 25 maintained ad, tracker, malware/phishing and TV-telemetry
  lists (Hagezi, OISD, 1Hosts, AdGuard CNAME, URLhaus, Phishing Army, Perflyst SmartTV and more).
• Separate tracker/telemetry and malware/phishing categories can be added or removed
  independently.
• The web dashboard shows domain counts for Ads, Trackers and Malware, a 24-hour
  chart, blocked-by-category breakdown, per-list health, one-tap allow/block from
  the query log, and CSV/JSON export.
• Runtime switches for Ads, Trackers and Malware apply instantly.
• Optional blocking hours, per-client LAN rules, DNS-over-TLS, a choice of
  blocked response (0.0.0.0, NXDOMAIN or NODATA), and a parental filter.
• The dashboard has icon-only sun/moon theme and refresh buttons, a browser-tab
  favicon, logout, log-out-all, a compact centered Material You login page, and a
  single compact pause pill/dropdown with an in-place countdown and dynamic width.
• Add your own hosts or adblock-style URLs.
• Exact, wildcard and regex allow/deny rules. Allow rules win.
• CNAME inspection catches trackers hidden behind another domain.
• Local DNS cache with encrypted DNS-over-HTTPS or DNS-over-TLS upstream.

SEE WHAT IS HAPPENING
• Live query log with blocked/allowed filters and search.
• Counters for queries, blocks, cache hits, forwards and errors.
• Top domains, clients, query types and upstreams.
• Optional web dashboard at http://DEVICE-IP:8080/admin/.

BUILT FOR THE TV AND THE PHONE
• D-pad navigation and focus rings for Android TV remotes.
• Touch layout with edge-to-edge support on modern phones.
• Dark Material 3 theme with dynamic accent colours on Android 12+.
• Optional start at boot, pause timers, blocking hours, notification actions, a
  Quick Settings tile and a home-screen widget.
• Backup and restore settings, rules, lists and the query log as JSON.

OPTIONAL LAN DNS
Turn other devices on your network into Hole Shield clients with the built-in
LAN DNS listener on port 5354 (UDP and TCP). Port 53 needs root, which this app
does not require.

PRIVACY BY DESIGN
• No account, no ads, no analytics and no crash reporting.
• Query history and settings stay in the app's private storage on your device.
• The developer does not collect or receive your DNS queries.
• The VPN is DNS-only; it does not tunnel your browsing or media traffic.

GOOD TO KNOW
• Android 8.0 or newer.
• Android's VPN permission is required once; the in-app disclosure explains what
  the local VPN does before the system prompt appears.
• Set Private DNS to Off in Android network settings if you use a strict DoT
  hostname that bypasses the local filter.
• The dashboard and LAN DNS listener are off by default; enable them in the
  first-run setup or Settings and set a web password before LAN exposure.
• The maximum blocklist preset is aggressive. Add an allow rule if a service
  stops working.
• Hole Shield is an independent app and is not affiliated with any other DNS
  filtering project.

Website: https://dinosnore1.github.io/holeshield-privacy/
Privacy policy: https://dinosnore1.github.io/holeshield-privacy/privacy.html
```

### Release notes (versionCode 7)

```
Hole Shield 2.3.0
• Dashboard and LAN DNS are now opt-in, with a first-run setup screen.
• Notification actions, a Quick Settings tile and a home-screen widget.
• Query log quick allow/block, category column and CSV/JSON export.
• Blocking hours with automatic resume, and per-client LAN rules.
• PBKDF2 web-password storage, login lockout and log-out-all sessions.
• Backup and restore settings, rules, lists and query history as JSON.
• 24-hour dashboard chart, blocked-by-category breakdown, per-list health.
• Runtime Ads/Trackers/Malware switches and a compact gravity index.
• DNS-over-TLS, selectable blocked response and a parental filter.
• Launcher shortcuts, network details, accessibility labels and CI.
```

## Graphics

| Asset | Requirement | File |
|---|---|---|
| App icon | 512×512 PNG, 32-bit, ≤1 MB | `store/assets/icon-512.png` |
| Feature graphic | 1024×500 PNG/JPEG, no transparency | `store/assets/feature-graphic-1024x500.png` |
| Phone screenshots | 2–8, 9:16, 1080×1920 | `store/assets/phone/01-home.jpg` … `05-gravity.jpg` |
| TV screenshots | 2–8, 1920×1080 | `store/assets/tv/01-home.jpg` … `03-settings.jpg` |
| TV banner | 320×180 PNG/JPEG | `res/drawable/banner.png` (copy in `store/assets/tv-banner-320x180.png`) |
| Promo video | Optional YouTube URL | — |

The five phone screenshots are 1080×1920 (9:16): Home, Settings, Server,
Query log and Gravity & rules. The three TV screenshots are 1920×1080: Home,
Server and Settings. All are flattened RGB (no alpha channel) so Play does not
interpret transparency.

Screenshots must show the actual app UI, without device frames or added text
that misrepresents the app. TV screenshots must show the TV layout.

## App content declarations

### Privacy policy

`https://dinosnore1.github.io/holeshield-privacy/privacy.html`

### Ads

No, the app does not contain ads.

### App access

All functionality is available without an account. If Play review needs the
dashboard, it is disabled by default and can be enabled in **Settings → Web
dashboard**. No credentials are required for the app itself. Note this in the
review instructions.

### Content rating

Complete the IARC questionnaire with these answers:

- Category: Utility / productivity.
- Violence, sexuality, language, controlled substances: No.
- Gambling, simulated gambling: No.
- User-generated content or user-to-user communication: No.
- Sharing location or personal information: No.
- Digital purchases: No.
- Ads: No.
- Unrestricted web access: No.

Expected result: Everyone / PEGI 3 / rated for all ages.

### Target audience

- Age groups: 13–15, 16–17, 18 and over. Not designed for children.
- Appeals to children: No.
- Teacher-approved: No.

### News app

No.

### Data safety

Recommended answers:

- Does your app collect or share any of the required user data types? **No.**
- Is all of the user data encrypted in transit? **Not applicable** (no data
  collected by the developer).
- Does your app provide a way for users to request that their data be deleted?
  **Not applicable** (no server-side data). Users can clear local data in Android
  settings or by uninstalling.
- Data processed only on the device: DNS queries, query log, settings and rules.
- Data sent off the device: DNS queries go to the resolver the user selects
  (Cloudflare DoH by default; plain DNS fallback), and blocklist downloads go to
  the configured list URLs. These are core app functions and are not received by
  the developer.

If Play review asks for a stricter interpretation, declare **App activity →
Other actions** as ephemeral, not stored by the developer, and explain that DNS
queries are forwarded to the user-selected resolver to provide the service.
Do not mark data as collected for advertising or analytics; the app has neither.

### Advertising ID

No, the app does not use the advertising ID.

### Government app

No.

### Financial features

None.

### Health

None.

## VpnService declaration

Play Console → **App content → VPN**.

- Does the app use VpnService? **Yes.**
- Is the VPN the app's core functionality? **Yes.** Hole Shield is a DNS
  filtering utility; the local VPN exists to route DNS through the on-device
  resolver.
- VPN category: **Device security / firewall** (DNS filtering and tracker
  blocking). Not a remote-access or anonymity VPN.
- Does the app operate a remote VPN tunnel or remote VPN service? **No.** The
  tunnel terminates inside the app on the device; no traffic is sent to a VPN
  server operated by the developer.
- Does the app collect or log user traffic? **No.** DNS queries are processed on
  the device; the optional query log stays in private app storage and is not
  uploaded.
- Prominent disclosure: implemented in `MainActivity.showVpnDisclosure()` and
  shown before Android's VPN consent dialog. It explains the local DNS-only VPN,
  local query logging, optional LAN DNS/dashboard, and how to turn protection
  off.
- Consent: the disclosure has **Agree and continue** and **Cancel**; agreeing
  then opens Android's system VPN prompt. Consent is not bundled with other
  terms.
- Demo videos: upload `store/videos/01-vpn-consent.mp4` and
  `store/videos/02-vpn-cancel.mp4` to YouTube as **Unlisted**, then paste the
  links into the declaration. The first shows the first-run disclosure, Agree,
  the Android VPN prompt, and protection turning on. The second shows the same
  disclosure with Cancel, which leaves protection off and starts no VPN service.

Suggested declaration text:

> Hole Shield uses Android's VpnService to run a local, DNS-only VPN on the
> user's device. It does not connect to a remote VPN server and does not route
> browsing, video or app traffic. DNS queries are checked against blocklists on
> the device and forwarded to the resolver the user selects. An in-app prominent
> disclosure is shown before Android's VPN consent prompt; the user must tap
> "Agree and continue" and then approve the system prompt. Query history stays in
> private app storage and is never uploaded.

## Foreground service declaration

Play Console → **App content → Foreground service permissions** (or the
`specialUse` declaration shown for the uploaded bundle).

- Type: `specialUse`
- Subtype property:
  `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` =
  "Local DNS filtering: a DNS-only VpnService that filters on-device lookups.
  No remote VPN tunnel is created."

Suggested explanation:

> Hole Shield keeps its DNS-only VpnService alive while the user has protection
> enabled, so DNS lookups from any app can be filtered even when Hole Shield is
> not in the foreground. The service shows an ongoing notification and stops
> when the user turns protection off. No other foreground-service type applies:
> the app does not play media, track location, make calls or transfer files. The
> VPN service itself is started by the user through Android's VPN consent flow.

## Permission explanations

| Permission | Why |
|---|---|
| `INTERNET` | Download blocklists and resolve DNS. |
| `ACCESS_NETWORK_STATE` | Detect connectivity and the LAN address for the dashboard. |
| `FOREGROUND_SERVICE` | Keep DNS filtering active with an ongoing notification. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declare the DNS filtering service on Android 14+. |
| `POST_NOTIFICATIONS` | Show the required foreground-service notification on Android 13+. |
| `RECEIVE_BOOT_COMPLETED` | Optionally resume protection after a reboot. |
| `BIND_VPN_SERVICE` (service permission) | Android's required permission for a VpnService. |

No location, contacts, camera, microphone, SMS, call log, storage or advertising
ID permissions are requested.

## Testing tracks

1. **Internal testing** — upload `build/holeshield.aab`, add the developer
   account and a few devices, install from the Play link, and run the smoke test
   below.
2. **Closed testing** — promote the same build. Recruit at least 12 testers who
   opt in and keep the app installed for 14 continuous days. Track testers in a
   spreadsheet with email, opt-in date and last-seen date.
3. **Production** — apply for production access after the 14-day requirement is
   met, answer the questionnaire, and roll out to a small percentage first.

### Smoke test for each track

- Fresh install on a phone and on a TV.
- First run: disclosure appears, Cancel does nothing, Agree opens the system VPN
  prompt.
- Notification permission prompt appears on Android 13+; denial does not crash
  the app.
- Protection on: status bar VPN key (phone), ongoing notification, dashboard
  reachable, `doubleclick.net` blocked, `example.com` resolves.
- Gravity update, pause/resume, allow rule, query log.
- Phone: rotate portrait/landscape, check status/navigation bar insets.
- TV: D-pad through every screen, check focus rings and the banner.
- Reboot with "Start at boot" enabled.

## Compliance notes

- The app name and listing avoid the Pi-hole trademark entirely. Do not use
  Pi-hole logos or imply endorsement. The README may describe API compatibility
  for developers, but the Play listing should not.
- The privacy policy must stay reachable at the URL above for as long as the app
  is listed.
- Keep the upload keystore and password backed up. Losing the upload key requires
  a Play App Signing key reset.
- The app has no remote VPN server, so it should not be listed as a VPN service
  provider; it is a local device-security/firewall utility that uses VpnService.
