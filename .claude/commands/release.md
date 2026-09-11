---
description: Build a signed release APK for a given version and cut a GitHub release
argument-hint: <version, e.g. 0.6>
---

For version `$1`:

1. Bump `versionName` and increment `versionCode` in `app/build.gradle.kts`.
2. `./gradlew :app:assembleRelease` (JAVA_HOME = Android Studio JBR). Signing comes from
   `keystore.properties` (untracked); it falls back to debug signing if absent.
3. Commit the version bump and push to `origin main`.
4. Create GitHub release `v$1` on `dexxfm/dexxicon-reader` via the API (token from
   `git credential fill`), attaching `app/build/outputs/apk/release/app-release.apk`
   renamed to `dexxicon-reader-$1.apk`. Write concise release notes from the commits since
   the previous tag.

Note: don't build or upload an `.aab` as part of this flow. Build one (`./gradlew
:app:bundleRelease`) only when explicitly asked, and leave it as a local file for download
rather than attaching it to the GitHub release.
