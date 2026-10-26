# Hole Shield — Play release checklist

## Ready now

- Signed Play bundle: `build/holeshield.aab`
  SHA-256 `a59bbe06b01946f9bd4acd8cc999b03bc37feb3a9d2bad2b899a73985fe7e9cf`
- Universal test APK: `build/universal/universal.apk`
- Everything in one zip: `build/holeshield-play-2.0.0.zip`
- Store text and declarations: `store/listing.md`
- Phone screenshots: `store/assets/phone/` (1080×1920)
- TV screenshots: `store/assets/tv/` (1920×1080)
- Icon: `store/assets/icon-512.png`
- Feature graphic: `store/assets/feature-graphic-1024x500.png`
- TV banner: `res/drawable/banner.png`
- VPN declaration videos: `store/videos/`
- Privacy policy: <https://dinosnore1.github.io/holeshield-privacy/privacy.html>
- Website: <https://dinosnore1.github.io/holeshield-privacy/>
- Closed-test tracker and invite text: `store/testers.md`

## 1. Play Console account

- Use the personal developer account.
- Pay the one-time registration fee and complete identity/address verification.
- Accept the developer agreement.
- If the account is new, expect the closed-testing requirement below.

## 2. Create the app

- Play Console → **Create app**.
- App name: **Hole Shield: DNS Ad Blocker**
- Default language: English (United States)
- App or game: **App**
- Free or paid: **Free**
- Accept the declarations.

## 3. Set up the listing

Copy the fields from `store/listing.md`:

- Short description, full description and release notes.
- Category: **Tools**.
- Contact email: `gaganpachory@gmail.com`.
- Website: <https://dinosnore1.github.io/holeshield-privacy/>
- Privacy policy: <https://dinosnore1.github.io/holeshield-privacy/privacy.html>
- Upload the icon, feature graphic, phone screenshots and TV screenshots.
- Upload the TV banner if the Console asks for one separately.

## 4. Complete App content

Use the answers in `store/listing.md`:

- Ads: **No**
- App access: no login; dashboard is off by default and can be enabled in
  Settings. Add this to the reviewer notes.
- Content rating: complete the IARC questionnaire (utility, no objectionable
  content, no ads, no user-generated content). Expected Everyone/PEGI 3.
- Target audience: 13+; not designed for children.
- News app: **No**
- Data safety: no developer collection; DNS processing is local; queries go to
  the user-selected resolver. See the note in `store/listing.md` if review asks
  for a stricter interpretation.
- Advertising ID: **No**
- Government app: **No**
- Financial/health: **None**
- VPN declaration: yes, core functionality, **Device security/firewall**, no
  remote VPN tunnel. Upload the two unlisted YouTube videos from
  `store/videos/`.
- Foreground service `specialUse`: use the declaration text in
  `store/listing.md`.

## 5. Internal testing

- **Testing → Internal testing → Create new release**.
- Upload `build/holeshield.aab`.
- Add release notes from `store/listing.md`.
- Add your own account as a tester.
- Install from the Play link and run the smoke test in `store/testers.md`.
- Fix any blocker, rebuild with a higher `versionCode`, and upload again.

## 6. Closed testing

- Promote the internal release to **Closed testing** (same build).
- Create an email list with at least 12 Google accounts (keep 2–3 spares).
- Send the opt-in link using the invite text in `store/testers.md`.
- Track opt-in dates. Testers must stay opted in for **14 continuous days**.
- Check the tester list every few days and replace dropouts.

## 7. Production access

- After 14 continuous days, apply for production access.
- Explain that Hole Shield is a DNS filtering utility for phones and TVs, uses
  VpnService only for local DNS, and has no remote tunnel or data collection.
- Roll out to a small percentage first, watch crashes/ANRs, then expand.

## 8. Keep safe

- Back up `~/.android/holeshield-upload.jks` and
  `~/.android/holeshield-upload-password.txt`.
- Do not upload the universal APK; upload the AAB.
- Keep the privacy policy URL and the VPN videos available for as long as the
  app is listed.
