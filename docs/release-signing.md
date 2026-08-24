# Android Release Signing

The release keystore lives **outside the repository**:

- Keystore: `~/.keystores/fuel-dashboard-release.jks` (alias `fuel-dashboard`, RSA 2048, valid 30 years)
- Keystore password: `~/.keystores/fuel-dashboard-release.password` (mode 600)

The build reads connection details from `android-signing.properties` at the repo root — **gitignored**, mode 600:

```properties
storeFile=/home/rhomancer/.keystores/fuel-dashboard-release.jks
storePassword=<from the password file>
keyAlias=fuel-dashboard
keyPassword=<from the password file>
```

Without this file, release builds are unsigned (debug builds never sign) — CI and fresh checkouts are unaffected.

## Building the Play bundle

```bash
./gradlew :composeApp:bundleRelease
# → composeApp/build/outputs/bundle/release/composeApp-release.aab
```

versionName follows `gradle.properties` `version` (e.g. `0.2.0-beta.1`); versionCode comes from
`android.versionCode` in `gradle.properties` (increment on every Play upload — start at 1).

## Google Play (Harry's developer account)

Recommended: enroll in **Play App Signing** on first upload — Google holds the app signing key and
the local keystore becomes the upload key. This protects against keystore loss (which would
otherwise permanently break app updates).

- Upload the AAB to a **closed beta track** (e.g. "beta") — invited testers only, fits the
  pre-public-beta posture.
- Required before the track goes live: store listing (icon ✓ exists, feature graphic, screenshots,
  descriptions), privacy policy URL, data-safety form (all "no data collected" — local-first, keys
  never leave the device), content rating questionnaire.

## Keystore loss = update-breaking

If the keystore is lost and Play App Signing was NOT enrolled, the app cannot be updated under the
same listing ever again. Back up `~/.keystores/fuel-dashboard-release.jks` + password file to
offline storage (password manager / encrypted backup). With Play App Signing enrolled, losing the
upload key is recoverable via Play Console reset.
