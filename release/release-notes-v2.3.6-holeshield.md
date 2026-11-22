# Hole Shield 2.3.6 — Rolling 30-day statistics

- Android, Android TV and desktop keep a rolling 30 days of DNS query history
  and main counters. The web dashboard has **Older** and **Newer** controls
  to browse retained queries beyond the old 2,000-entry limit.
- Existing retained query history is migrated. History already discarded by
  older versions cannot be recovered. Desktop stays JDK-only.
- The Android notification and widget still show today's blocks separately
  from the rolling totals. Native log views and exports are bounded snapshots.
- Fixed an Android crash when saving newly created hourly statistics.

Android uses the same `com.dino.holeshield` package and signing key
(`versionCode 14`). On TV firmware that suppresses app-initiated updates,
install over the existing app with `adb install -r holeshield-2.3.6.apk`.
