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

Google Play (issue #303): when asked to put the AAB on Play, build it
(`./gradlew :app:bundleRelease`) and upload it with `tools/play_upload.py`:

```bash
python tools/play_upload.py --list-tracks
python tools/play_upload.py --aab app/build/outputs/bundle/release/dexxicon-reader-$1.aab \
    --track alpha --name $1 --notes-file <notes, max 500 chars>
```

It signs in as a service account whose JSON key lives outside the repo, at
`~/.config/dexxicon/play-service-account.json` or `$PLAY_SERVICE_ACCOUNT_JSON`. Never print or
copy the key. `alpha` is Play's default closed-testing track; `--validate-only` does a dry
run and `--draft` saves the release without rolling it out.

iOS (issue #304), all through `E:\Claude-Mac-Scripts\mac_run.py`, never raw ssh:
1. On the Mac: `git worktree add --detach ~/dexxicon-v<ver> v$1` from `~/dexxicon-reader`. Add
   `local.properties` (`sdk.dir=/Users/administrator/Library/Android/sdk`), run `xcodegen generate` in
   `iosApp/`, and copy `iosApp/Package.resolved` into
   `Dexxicon.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/`.
2. Put `ExportOptions.plist` (from the previous `~/dexxicon-release-*`) in `~/dexxicon-release-$1`.
3. `zsh ~/.dexxicon-ci/tools/archive_release.sh $1 ~/dexxicon-v<ver>`. It unlocks the dedicated
   signing keychain itself (`tools/ios/unlock_signing_keychain.sh`), so the login keychain isn't
   needed. The first build in a fresh worktree also downloads Kotlin/Native, so it's slow.
4. `xcrun altool --upload-app -f ~/dexxicon-release-$1/export/Dexxicon.ipa -t ios --apiKey 98VBD8ALPP
   --apiIssuer 842be70a-119f-4e5b-b900-8ba2c501203b`, then remove the worktree.
If the signing keychain is missing (a new Mac, say), a person runs
`tools/ios/setup_signing_keychain.sh` once in Terminal on the Mac. The copies the Mac runs live in
`~/.dexxicon-ci/tools/`.

Note: otherwise, don't build or upload an `.aab` as part of this flow. Build one (`./gradlew
:app:bundleRelease`) only when explicitly asked, and leave it as a local file for download
rather than attaching it to the GitHub release.
