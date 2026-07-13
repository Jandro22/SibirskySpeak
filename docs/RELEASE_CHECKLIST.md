# Release checklist

The public APK must be built by `.github/workflows/release.yml` from a `vMAJOR.MINOR.PATCH` tag.

Configure these repository secrets before publishing:

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`
- `RELEASE_CERT_SHA256` (the SHA-256 fingerprint printed by `apksigner`)

The workflow derives `versionCode` as `major * 1,000,000 + minor * 1,000 + patch`, runs unit, lint, content, and connected QA tests, builds the signed shrinker variant, launches that exact APK in a fresh emulator with `tools/smoke_release.py`, validates the package/version/signature/permissions, and enforces the release APK size limit. The current Room schema is 32 and includes the reader-bookmark migration.

The `benchmark` module contains Macrobenchmark cold-start/card-reader scenarios
and a baseline-profile collector. Run `:app:installBenchmark` followed by
`:benchmark:connectedBenchmarkAndroidTest` on a physical profileable device for
performance numbers; emulator runs are skipped or used only for wiring smoke.

The rolling `latest` APK is a debug build with application ID `com.sibirskyspeak.dev`. It is intentionally isolated from the public `com.sibirskyspeak` package and must not be used as an upgrade test for the public release.

Before publishing, verify the release certificate fingerprint and test both a clean install and an upgrade over the previous signed public APK. The Room migration chain is covered by `Migration30To31Test`, `Migration31To32Test`, and `MigrationChainTest`; the signed upgrade pass must still be exercised on the release emulator when the database schema changes. Never commit the production keystore or its passwords.

For a repeatable device pass, run `python tools/verify_upgrade.py old.apk
app-release.apk --serial <device>`; it installs the old build, upgrades in place,
and waits for the upgraded activity without clearing learner data.

Run `python tools/device_resilience.py app-release.apk --serial <device>` before
handoff. It refuses an unsafe low-storage device, validates APK asset CRCs, and
confirms the installed package reaches its foreground activity.
