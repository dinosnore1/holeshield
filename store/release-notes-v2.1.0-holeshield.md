# Hole Shield 2.1.0 — username + password web login

An in-place update for 2.0.x: same package, same signing key, your settings,
rules, blocklists and statistics are kept.

## Download

- **`holeshield.apk`** — install directly on a phone, tablet or Android TV.
- **`holeshield.aab`** — signed Play bundle for Google Play upload.
- **`holeshield-2.1.0-release-SHA256SUMS.txt`** — checksums for both files.

## New: web dashboard username

- Add **Settings → Web username** to require a username as well as the web
  password.
- The dashboard login form shows the username field when one is configured.
- Password-only login still works; leave the username empty.
- An empty password still leaves the dashboard open, so set a password before
  exposing the dashboard to your LAN.
- Clearing the password also clears the username.
- Changing the web login invalidates existing dashboard sessions.
- `/api/auth` accepts `{"username":"admin","password":"secret"}`.
- The Pi-hole v5 compatibility endpoint accepts `auth=admin:secret` when a
  username is set, or `auth=password` for password-only installs.
- The Server screen and dashboard show whether the web login is off,
  password-only, or user + password.

## Fixed

- Version strings in the UI, dashboard and API now come from the installed
  package instead of hard-coded `2.0.0` values.

## Verified

- 87 desktop checks pass, including web login credential rules.
- On a VU/Hisense Android 11 TV: username + password enforced; wrong
  user/password rejected; SID and v5 `auth=user:pass` accepted; clearing the
  password reopened the dashboard.
- Existing 2.0.1 background-CPU and restart fixes remain.

## SHA-256

```
ba1e84ab3d4e873bc5215978061c60aeb91afe9c9fb1bc19329637f4c94bb185  holeshield.aab
27046905fae6c29359d707865cce05e3378197594ce93037499005926228a3df  holeshield.apk
```
