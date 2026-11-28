# Dexxicon Reader

Dexxicon Reader is a Kotlin Multiplatform client for self-hosted BookOrbit and
Grimmory/BookLore libraries. Android is the primary native host; `shared` supplies the
Compose Multiplatform app shell and is also built into the iOS `SharedKit` framework.

## Build, test, and lint

Use JDK 17+ (the project defaults to the Android Studio JBR). On another machine, override
`org.gradle.java.home` in `~/.gradle/gradle.properties`; do not change the committed machine
path in `gradle.properties`.

```powershell
# Debug APK
.\gradlew.bat :app:assembleDebug

# All Android unit/host tests used by CI
.\gradlew.bat testDebugUnitTest testAndroidHostTest

# One classic Android unit test
.\gradlew.bat :app:testDebugUnitTest --tests "fully.qualified.TestClass"

# One KMP Android host test (KMP modules use androidHostTest, not src/test)
.\gradlew.bat :core:serverapi:testAndroidHostTest --tests "fully.qualified.TestClass"

# Android lint
.\gradlew.bat :app:lintDebug
```

Use `.\gradlew.bat` on Windows and `./gradlew` elsewhere. Generate the baseline profile only
with a connected API 33+ device or emulator: `./gradlew :app:generateBaselineProfile`.

## Architecture

- `app` is the Android application host. It owns Hilt bindings, Android entry points, and
  Android-specific integrations. `MainActivity` hosts `shared`'s `App()`.
- `shared` is the cross-platform Compose UI/navigation and manual composition root. Keep UI
  and flows that must work on iOS in `commonMain`; put platform implementations in
  `androidMain` or `iosMain`.
- `core:model` and `core:common` hold shared domain types and utilities. `core:serverapi`
  implements BookOrbit, Grimmory/BookLore, OPDS, and sync API clients over shared Ktor;
  `core:data` coordinates repositories, catalog/progress sync, downloads, and server actions.
- `core:database` is the KMP Room store; `core:datastore`, `core:network`, and
  `core:security` provide cross-platform settings, connectivity/HTTP, and credential
  primitives. `core:designsystem` centralizes shared Compose styling and reusable UI.
- `feature:reader-epub`, `feature:reader-pdf`, `feature:reader-comic`, `feature:player`,
  and `feature:annotations` provide format- and media-specific reader integrations used by
  the Android host.

## Repository conventions

- The project intentionally has no `buildSrc` or included-build convention plugins. Keep
  module build files flat and use `gradle/android-common.gradle` for shared Android
  configuration; this avoids a Gradle/AGP version-catalog accessor failure.
- KMP modules target Android and iOS. Hilt cannot be applied to these KMP modules, so place
  Android Hilt modules/providers in `app`; use the shared composition root and platform
  actuals for cross-platform dependencies.
- KMP Android unit tests live in `src/androidHostTest` and run through
  `testAndroidHostTest`; classic Android modules use `src/test` and `testDebugUnitTest`.
- Room is versioned and migrated in `core:database`. Preserve its established database path
  and add a migration/schema update with any persistent model change.
- Release packaging requires the untracked `keystore.properties` and release keystore.
  `assembleRelease`/`bundleRelease` fail without them unless
  `-PallowUnsignedRelease` is explicitly supplied for a non-distributed local smoke build.
- Add focused unit tests with every bug fix or feature whose logic is testable without a
  device (extract it from composables if needed); skip only for pure UI wiring.
- For a bug fix or product change, follow `CLAUDE.md`: open a GitHub issue first, branch from
  synced `main` as `fix/<issue>-...` or `feature/<issue>-...`, and make the PR close that
  issue.
