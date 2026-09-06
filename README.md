# Dexxicon Reader

An OPDS client for Android for self‑hosted book, comic and audiobook libraries — with
first‑class support for **BookOrbit** and **Grimmory** (BookLore successor).

## What it will do

- Multi‑server OPDS (1.2 Atom + 2.0 JSON): browse feeds, facets, search, pagination.
- Stream‑first: open content over authenticated HTTP range requests; offline is opt‑in.
- In‑app readers for **EPUB**, **M4B audiobook**, **CBZ comic**, plus **PDF / MOBI / AZW3 / FB2**.
- Modern UI: Jetpack Compose + Material 3, dark‑blue/grey theme, adaptive layouts.
- Sync: KOReader progress protocol, Kobo sync, and native annotation/highlight sync.
- OPDS‑PSE comic page streaming.

See `.claude/plans/` (local) for the full implementation plan.

## Status

Early scaffolding. The multi‑module project configures and the debug APK assembles;
feature screens are placeholders. Build phases are tracked in the plan.

## Tech

Kotlin 2.4 · AGP 9.4 · Gradle 9.6 · Jetpack Compose / Material 3 · Hilt · Room · KSP2 ·
Media3 · Readium Kotlin toolkit 3.3 · Coil 3 · WorkManager.

`minSdk 29`, `compileSdk 37`.

## Build

Requires JDK 17+ (the Android Studio JBR works) and the Android SDK (platform 37,
build‑tools 36).

```bash
./gradlew :app:assembleDebug
```

The module build files are intentionally flat (no `build-logic` / `buildSrc` convention
plugins): under Gradle 9.6 + AGP 9.4 an included build's precompiled plugins break the
type‑safe `libs` version‑catalog accessor in the first Android module. Shared Android
config lives in `gradle/android-common.gradle`.

## Licence

[MIT](LICENSE) — free to use, modify, and fork. Keep the copyright notice and licence
text, and credit the original project (`github.com/dexxfm/dexxicon-reader`).

Bundled third-party components keep their own licences (Readium toolkit: BSD-3-Clause;
Media3/AndroidX: Apache-2.0; libmobi, when added: LGPL-2.1).
