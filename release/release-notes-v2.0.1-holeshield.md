# Hole Shield 2.0.1 — no more background CPU, protection stays up

An in-place update for 2.0.0: same package, same signing key, your settings, rules,
blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield-2.0.1-release-SHA256SUMS.txt`** — checksums for the APK.

## Fixed: the filter could burn a whole CPU core

On some firmwares `VpnService.Builder.establish()` hands back a **non-blocking** tun
descriptor. `read()` then returns `0` immediately instead of waiting for the next
packet, and the DNS reader loop answered that with `continue` — a busy-wait that pegged
one core for as long as protection ran (measured at 94 % on a VU/Hisense Android 11 TV,
~137 MB resident). Everything still worked; it just never idled.

- The reader now puts the descriptor into blocking mode before reading.
- If a firmware refuses that, an empty read yields for 2 ms instead of spinning.
- The loop is bound to its own tunnel (`generation`), so a reader left over from a
  restart can no longer outlive the tunnel it belongs to.

Result on the set above: the reader thread went from 94 % of a core to **0 CPU ticks in
10 s**, and the device idles at ~5 % across four cores instead of one core saturated.

## Fixed: protection now comes back by itself

- Updating the app kills its process and used to leave the VPN down until the app was
  opened. The boot receiver now also handles `MY_PACKAGE_REPLACED`, so an update lands
  with protection already running.
- A boot whose first start is refused or killed while the TV is still starting gets one
  more attempt a minute later, instead of waiting for someone to open the app.
- Tunnel teardown interrupts and drops the old reader before a restart clears the stop
  flag, so a restart cannot leave a reader behind.

## Not changed

UI, dashboard, rules, blocklists, statistics and the VPN consent flow are untouched.

## Verified

- 70 desktop checks pass.
- Direct install and update in place on the TV: protection returned on its own 12 s after
  the update, with no user action.
- Reboot: protection resumed on its own, reader idle at 0 % CPU, queries still filtered.
- APK: 131 921 bytes. Checksums:

```
1dbf2ea47626c533da150f479851e19e3641834b09a732669f4e30d549b0f45f  holeshield.apk
```
