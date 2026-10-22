# Pi-hole TV

A self-contained, Pi-hole-style DNS filter for Android TV / Google TV. The app
runs its own gravity engine, query log, statistics, web dashboard/API and an
optional LAN DNS listener. It also runs a DNS-only VPN on the TV so every app's
lookups are filtered without root.

No Gradle, AndroidX or third-party libraries. Pure Java, built with the Android
SDK command-line tools.

![Pi-hole TV icon](res/drawable/ic_launcher.png)

## What it does

- **System-wide filtering on the TV.** A local `VpnService` routes IPv4 and
  IPv6 DNS to the embedded resolver. The VPN does not carry video, downloads or
  general LAN traffic.
- **Embedded gravity database.** Ships with a seed list and downloads
  hosts/adblock-style lists (StevenBlack's unified hosts by default). Multiple
  list URLs are supported.
- **Pi-hole-style rules.** Exact allow/deny, wildcard allow/deny and regex rules.
  Allow rules take precedence over gravity. `*.example.com` matches strict
  subdomains, not the apex; `/regex/` supports Java regular expressions.
- **CNAME inspection.** If an upstream answer points at a blocked CNAME target,
  the answer is turned into a blocked reply.
- **Local DNS cache**, DoH upstream (Cloudflare by default) and a plain-DNS
  fallback.
- **Pause / resume** indefinitely or for 5/30 minutes, with timer state persisted
  across restarts.
- **Query log and counters** for queries, blocks, cache hits, forwards, errors,
  clients, types, top domains and upstreams. The log survives app restarts and
  is rolled over daily.
- **Web dashboard** at `http://TV-IP:8080/admin/`, with optional password login.
- **LAN DNS server** on UDP and TCP port **5354** for other devices on the LAN
  (IPv4).
- **Pi-hole-compatible API subset** for dashboards and scripts (see below).
- **D-pad-first TV UI**, boot auto-start, force-DNS capture and Private DNS
  warnings.

## Requirements

- Android TV / Google TV on **Android 8.0 (API 26) or newer**.
- No root. Root is only needed if you want to serve DNS on the standard **port
  53**; unrooted Android cannot bind it, so this app uses **5354** for LAN DNS.
- To build: JDK 17 and Android SDK platform 30 + build-tools. The build script
  defaults to `~/Library/Android/sdk` and `/opt/homebrew/opt/openjdk@17`.

## Build and install

```bash
cd ~/pihole-tv
./tools/test.sh          # 53 desktop checks for DNS/packet code
./build.sh               # writes build/pihole-tv.apk
```

Install over the network:

```bash
adb connect 192.168.1.19:5555
adb install -r build/pihole-tv.apk
adb shell am start -n com.dino.pihole/.MainActivity
```

The APK is signed with `~/.android/pihole-tv.keystore`, created on the first
build. This is a sideload build; there is no Play Store package.

**Current build**

- Version: **2.0.0** (`versionCode 2`)
- APK: `build/pihole-tv.apk` (also `build/pihole-tv-2.0.0.apk`)
- SHA-256:
  `dd8bb306aebc93458892c339b4e0de8eced358c00940f38d9326463465595157`

## First run

1. Open **Pi-hole TV** and select **Turn on protection**. Approve Android's VPN
   prompt. This is a one-time permission; boot auto-start uses it afterwards.
2. In the TV's network settings, set **Private DNS** to **Off**. A strict DoT
   hostname bypasses the VPN and can break filtering; the app shows an amber
   warning while it is on.
3. Open the dashboard:
   `http://192.168.1.19:8080/admin/` (replace with the TV's LAN IP).
4. Optional but recommended: set a **Web password** in the TV app's Settings
   before exposing the dashboard to your LAN.
5. Run **Update gravity now** to download the current list.
6. Run **Test protection**. `doubleclick.net` should resolve to `0.0.0.0` (or
   return no address) while `example.com` resolves normally.

The app remembers protection and can resume it after a reboot. Some TV firmware
limits background starts; opening the app once after a reboot works around that.

## How resolution works

```
TV app
  │  DNS query
  ▼
tun 10.111.221.1 / [fd00:111:221::1]:53
  ▼
DnsEngine
  ├─ allow rule?        → resolve normally (allow wins)
  ├─ gravity/deny?      → 0.0.0.0 / NODATA
  ├─ cache hit?         → cached answer
  └─ DoH / plain DNS    → answer
        │
        └─ inspect CNAME targets and block if needed
```

The LAN DNS listener uses the same engine, so the TV and LAN clients share rules,
statistics and the cache.

Resolution order is intentionally simple: **allow rules → gravity/deny →
cache → DoH → plain DNS**. If the local gravity engine is off or blocking is
paused, lookups still resolve but no blocking rules are applied. CNAME targets
are checked only when blocking is active and the original query was not allowed.

## Dashboard and API

Default dashboard: `http://TV-IP:8080/admin/`

Default LAN DNS: `TV-IP:5354` (UDP and TCP)

Set a web password under **Settings → Web password**. Empty means no password
and the dashboard/API is open to anyone who can reach the TV's LAN address.
When a password is set:

```bash
# Exchange the password for a session ID
curl -s -X POST http://192.168.1.19:8080/api/auth \
  -H 'Content-Type: application/json' \
  -d '{"password":"your-password"}'

# Use the returned sid as X-FTL-SID (or as a sid cookie)
curl -s -H 'X-FTL-SID: <sid>' \
  http://192.168.1.19:8080/api/stats/summary
```

The v5 compatibility endpoint accepts `auth=password`:

```bash
curl -s 'http://192.168.1.19:8080/admin/api.php?summaryRaw&auth=your-password'
```

### API endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/api/health` | GET | Liveness check (no auth required). |
| `/api/auth` | POST | Password → session ID. |
| `/api/stats/summary` | GET | Query counters, gravity size, clients, blocking state. |
| `/api/stats/top_domains` | GET | `blocked=true|false`, `count=1..100`. |
| `/api/stats/top_clients` | GET | Top clients; `count=1..100`. |
| `/api/stats/query_types` | GET | A/AAAA/other counts. |
| `/api/stats/upstreams` | GET | `doh`, `plain`, `cache`, `gravity`, `custom`, etc. |
| `/api/queries` | GET | Query log; `length`, `start`, `blocked`, `client`, `domain`, `search`. |
| `/api/dns/blocking` | GET | Current state and pause timer. |
| `/api/dns/blocking` | POST | `{"blocking":false,"timer":300}`; `timer=0` means indefinite. |
| `/api/domains/{allow|deny}/{exact|wildcard|regex}` | GET | List matching rules. |
| `/api/domains/{allow|deny}/{exact|wildcard|regex}` | POST | Add `{"domain":"..."}`. |
| `/api/domains/{allow|deny}/{exact|wildcard|regex}` | DELETE | `?domain=...`. |
| `/api/lists` | GET | Blocklist URLs as numbered entries. |
| `/api/lists` | POST | Add `{"address":"https://..."}`. |
| `/api/lists/{id}` | DELETE | Remove a list by its numeric id. |
| `/api/action/gravity` | POST | Start a gravity update. |
| `/api/action/flush` | POST | Flush the DNS cache. |
| `/api/info/host` | GET | LAN IP, ports, version and listener state. |
| `/api/info/version` | GET | App/API version. |
| `/admin/api.php` | GET | Pi-hole v5 subset: `summaryRaw`, `status`, `enable`, `disable`, `topItems`, `recentBlocked`, `versions`. |

`/api/stats/summary` returns `gravity.last_update` as Unix seconds for Pi-hole
compatibility and `gravity.last_update_ms` for the bundled dashboard.

### Pi-hole compatibility notes

This is a **Pi-hole-style embedded server**, not a full Pi-hole installation.
The implemented API covers the dashboard, blocking/pause, query log, stats,
domain rules, lists, gravity and cache. The following are **not** implemented:

- local DNS records, conditional forwarding, upstream group management;
- per-client groups and group assignments;
- DHCP/DHCPv6;
- DNSSEC validation;
- complete Pi-hole v5/v6 API coverage (network tables, teleporter,
  diagnostics, messages, etc.);
- binding LAN DNS to port 53 (root-only on Android).

Scripts that only need stats, blocking, rules, lists and query history work.
If you need full Pi-hole behavior, run Pi-hole on a separate machine and use its
dashboard/API; this app deliberately remains self-contained.

## LAN DNS

The TV's own DNS goes through the VPN on port 53. For other devices, enable
**Settings → LAN DNS server** and point them at:

```
DNS server: 192.168.1.19
Port:       5354
```

Both UDP and TCP are supported. Many routers and devices only accept port 53,
which cannot be bound by an unrooted Android app. On those devices you can use a
router/firewall redirect from 53 to 5354, or run a small forwarder on another
machine. The dashboard shows the current listener state and port.

The LAN listener is IPv4 (`0.0.0.0`). It uses the same blocklists, rules,
pause state, CNAME checks and cache as the TV.

## Settings

| Setting | Meaning |
|---|---|
| Protection | Turn the DNS-only VPN on or off. |
| Start at boot | Resume protection after the TV restarts. |
| Gravity blocking | Apply downloaded lists and custom rules. Off = resolve everything. |
| Block subdomains of list entries | Match `ads.example.com` when `example.com` is on a gravity list. |
| Block CNAME targets | Inspect upstream CNAMEs and block answers pointing at blocked names. |
| Force all DNS through the filter | Add routes for common public resolvers and capture hard-coded DNS. |
| Fallback DNS | `Encrypted (DoH)` tries DoH first, then plain DNS; `Plain DNS` uses plain only; `Off` disables upstream resolution. |
| Encrypted DNS server | DoH URL; Cloudflare by default. |
| Web dashboard / port | Enable/disable and choose the HTTP port (default 8080). |
| Web password | Optional dashboard/API password. Empty = open. |
| LAN DNS server / port | Enable/disable the UDP+TCP LAN listener (default 5354). |
| Blocklist sources | One hosts/adblock URL per line. |
| Update gravity now | Download all sources and reload the engine. |
| Test protection | Resolve a known ad domain through the active VPN. |

Changes to ports, fallback, capture or LAN DNS restart the VPN automatically.
Gravity/rule changes are applied live.

## Rules

- **Exact:** `ads.example.com` blocks only that name.
- **Wildcard:** `*.example.com` blocks strict subdomains such as
  `ads.example.com`, but not `example.com` itself.
- **Regex:** `/^ads[0-9]+\.example\.net$/` uses Java regex matching.
- **Allow rules** are evaluated before gravity and custom deny rules. An exact
  allow for a CNAME target also prevents CNAME blocking.
- Custom rules are stored in the app's private preferences and persist across
  restarts.

## Privacy and security

- DNS queries go to the configured DoH endpoint (HTTPS). If DoH is selected but
  fails, or if plain fallback is selected, they go to plain DNS (`1.1.1.1`,
  `8.8.8.8`, `9.9.9.9`). Local gravity matching happens on the TV.
- Blocklist downloads go only to the URLs you configure.
- The dashboard/API listens on the LAN over **plain HTTP**. There is no TLS.
  Use it only on a trusted LAN. Set a web password if other people or untrusted
  devices share the network.
- The web password is stored in the app's private `SharedPreferences` and is
  not encrypted at rest. Anyone with root or a full device backup can read it.
- The VPN is DNS-only. Video, downloads and normal LAN traffic are not routed
  through the app.
- No analytics, accounts or crash reporting.

## Troubleshooting

| Symptom | Check |
|---|---|
| Dashboard does not load | Confirm protection is on, the TV IP is correct, and the web dashboard is enabled. Try `curl http://TV-IP:8080/api/health`. |
| `401 authentication required` | Set/enter the web password, then use the returned `X-FTL-SID` or the v5 `auth=` parameter. |
| LAN DNS refused | Enable **LAN DNS server**, check the port, and verify the client supports a non-53 DNS port. |
| Ads still appear | Same-domain ads (YouTube in-stream), app-level DoH, or the query was allowed. Check the query log and test `doubleclick.net`. |
| No internet while protected | Set fallback to DoH/plain, check Private DNS is Off, and verify the upstream is reachable. |
| Gravity is 0 / update fails | Check the list URL from the TV's network, then run **Update gravity now** again. |
| TV forgets protection after reboot | Some firmware blocks background starts; open the app once. |

## Project layout

```
AndroidManifest.xml          leanback entry, VPN service, boot receiver
src/com/dino/pihole/
  MainActivity.java          TV UI, settings, rules, query log
  DnsVpnService.java         tun lifecycle, packet loop, notification
  DnsEngine.java             allow/gravity/CNAME resolution, logging
  DnsUpstream.java           cache, DoH, plain DNS, startup IP seeding
  DnsPacket.java             DNS parsing, replies, CNAME/TTL helpers (pure Java)
  IpPacket.java              IPv4/IPv6 UDP reply builder (pure Java)
  Blocklist.java             gravity lists, custom rules, downloads
  Stats.java                 persisted daily counters and query log
  WebServer.java             dashboard HTTP server, v6/v5 API subset, auth
  LanDnsServer.java          UDP + TCP LAN DNS listener
  Prefs.java                 persisted settings and pause state
  NetInfo.java               LAN IP and dashboard URL
  PiholeClient.java          legacy external-Pi-hole client (inactive)
  Ui.java, Backdrop.java, ShieldView.java
assets/blocklist.txt         bundled seed list
assets/dashboard.html        responsive web dashboard
res/drawable/                launcher icon, TV banner, notification icon
tools/TestDns.java           desktop checks for DNS/packet code
build.sh                     no-Gradle APK build
```

## Tests

`./tools/test.sh` runs **53 desktop checks** covering query parsing (including
rejection of responses/non-standard opcodes), stripping IPv4/IPv6/UDP headers
from tun packets, blocked A/AAAA/HTTPS replies, answer-address extraction, TTL
selection, IPv4/IPv6 UDP replies and checksums, address/port swapping and
payload preservation.

The APK has been exercised on a Hisense VU Smart TV (Android 11 / API 30,
armeabi-v7a, 1920×1080) at `192.168.1.19`:

- dashboard and API over the LAN, including authenticated sessions;
- gravity update (71,527 domains) and list add/delete;
- TV-originated filtering and LAN DNS over both UDP and TCP;
- blocked/allow/wildcard/regex rules and CNAME blocking;
- pause, timed auto-resume and cache flush;
- DoH upstream (`doh`) and plain fallback;
- v5 compatibility (`summaryRaw`, status, enable/disable);
- boot/protection restart and the D-pad TV UI.

`PiholeClient` is retained only as legacy source; the active UI and resolver use
the embedded engine. An old `usePihole` preference is ignored so upgrades cannot
route DNS around the on-device filter.
