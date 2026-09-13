# Building from source

## Requirements

- JDK 17+ (the Android Studio JBR works)
- Android SDK, platform 37, build-tools 36

```bash
./gradlew :app:assembleDebug
```

Release signing reads an untracked `keystore.properties` + `app/release.keystore`; without
them the release build falls back to the debug key.

```bash
./gradlew :app:assembleRelease   # signed APK
./gradlew :app:bundleRelease     # AAB for Play Console
```

## Tech stack

Kotlin 2.4 (AGP 9.4's built-in compiler) · Gradle 9.6 · KSP 2 · Jetpack Compose / Material 3
· Hilt · Room (schema v9) · Media3 (incl. `MediaLibraryService` for Android Auto) · Readium
Kotlin toolkit 3.3 (+ PDFium adapter) · Coil 3 · WorkManager · junrar · DataStore ·
kotlinx-serialization + Retrofit + OkHttp.

`applicationId` `com.dexxfm.dexxicon_reader` · namespace `net.dexxicon.reader` · `minSdk 29`
· `compileSdk 37` · JDK 17.

## Project structure

Multi-module (24 modules under `core/*` and `feature/*`). Build files are intentionally flat
— no `build-logic` / `buildSrc` convention plugins: under Gradle 9.6 + AGP 9.4 an included
build's precompiled plugins break the type-safe `libs` version-catalog accessor in the first
Android module. Shared Android config lives in `gradle/android-common.gradle`.

## Performance

A baseline profile ships with release builds and is regenerated/measured as described in
[PERF.md](PERF.md).

## Contributing

See [CLAUDE.md](CLAUDE.md) for the issue → branch → PR workflow this project follows.
