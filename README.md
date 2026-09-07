# Dexxicon Reader

A native Android client for self‑hosted book, comic and audiobook libraries. Built for
**BookOrbit** (primary) and **Grimmory / BookLore** (secondary), talking to their own REST
APIs — the same ones their web readers use — rather than generic OPDS.

<p>
  <a href="https://github.com/dexxfm/dexxicon-reader/releases"><img alt="latest release" src="https://img.shields.io/github/v/release/dexxfm/dexxicon-reader?include_prereleases&sort=semver"></a>
  <img alt="min SDK 29" src="https://img.shields.io/badge/minSdk-29-blue">
  <a href="LICENSE"><img alt="MIT" src="https://img.shields.io/badge/licence-MIT-green"></a>
</p>

## Features

### Browse & open
- Add multiple servers; per‑server sign‑in with username + password (native JWT, auto‑refresh)
  or **OIDC / SSO** through an in‑app WebView.
- Browse the whole library with shelves/facets, search, sort and infinite scroll; covers
  load through the authenticated client.
- **Stream‑first**: books open over authenticated HTTP range requests. "Make available
  offline" is a per‑book opt‑in; the readers and player prefer a local copy when present.
- Book detail shows description, metadata and the real file type (`epub`, `cbz`, `cbr`,
  `m4b`, `pdf`, …).
- Pull‑to‑refresh on Library, Browse and Settings.

### Readers
| Format | Reader |
|---|---|
| **EPUB** | Readium — font size, light/sepia/dark themes, paged or scrolling, TOC, tap **and swipe** to turn pages, highlights & notes |
| **Comic** — CBZ **and CBR** | Readium image navigator + page slider + pinch zoom. `.cbr` (RAR) is unpacked with junrar and cached as CBZ on first open |
| **PDF** | Readium + PDFium — page/scroll modes, outline, zoom |
| **Audiobook** — M4B / MP3 / … | Media3 player — background playback, lock‑screen & notification controls, chapter list, scrubber |

Audiobook extras: playback speed, sleep timer (timed or **end of chapter**), **skip
silence**, configurable skip‑forward/back intervals, rewind‑on‑resume. A mini‑player bar
sits above the bottom navigation while audio is active.

MOBI / AZW3 / FB2 are recognised but not yet openable (converters are a work in progress).

### Sync — two‑way
- **Reading & listening position** round‑trips with the server, so you resume on your phone
  exactly where the website left off, and vice‑versa:
  - BookOrbit / Grimmory → the server's own progress API (identical to the web reader).
  - Generic OPDS servers → the **KOReader `kosync`** protocol (per‑server sync account,
    configured in Settings).
- **Highlights & notes** sync to the server's annotation API (BookLore full CRUD, BookOrbit
  read‑only).
- "Continue reading" and "Continue listening" shelves on the Library screen; `Settings →
  Reading sync` shows each server's channel and when it last synced.

### Design & privacy
- Jetpack Compose + Material 3, dark‑blue/grey theme, light/dark, edge‑to‑edge.
- No ads, no analytics, no third‑party SDKs. The app connects only to the servers you add;
  credentials are encrypted on‑device with a hardware‑backed key. See [PRIVACY.md](PRIVACY.md).

## Status

Usable daily‑driver for BookOrbit and Grimmory. Current version **0.7.3** — see
[Releases](https://github.com/dexxfm/dexxicon-reader/releases) for prerelease APKs and
Play‑ready App Bundles.

**Not done yet:** MOBI/AZW3/FB2 conversion, OPDS‑PSE comic page streaming, tablet/foldable
adaptive layouts, Play Store publication. Kobo sync was cut.

## Tech

Kotlin 2.4 (AGP 9.4's built‑in compiler) · Gradle 9.6 · KSP 2 · Jetpack Compose / Material 3
· Hilt · Room (schema v6) · Media3 · Readium Kotlin toolkit 3.3 (+ PDFium adapter) · Coil 3
· WorkManager · junrar · kotlinx‑serialization + Retrofit + OkHttp.

`applicationId` `com.dexxfm.dexxicon_reader` · namespace `net.dexxicon.reader` · `minSdk 29`
· `compileSdk 37` · JDK 17.

Multi‑module (24 modules under `core/*` and `feature/*`). Build files are intentionally flat
— no `build-logic` / `buildSrc` convention plugins: under Gradle 9.6 + AGP 9.4 an included
build's precompiled plugins break the type‑safe `libs` version‑catalog accessor in the
first Android module. Shared Android config lives in `gradle/android-common.gradle`.

## Build

Requires JDK 17+ (the Android Studio JBR works) and the Android SDK (platform 37,
build‑tools 36).

```bash
./gradlew :app:assembleDebug
```

Release signing reads an untracked `keystore.properties` + `app/release.keystore`; without
them the release build falls back to the debug key.

```bash
./gradlew :app:assembleRelease   # signed APK
./gradlew :app:bundleRelease     # AAB for Play Console
```

## Licence

[MIT](LICENSE) — free to use, modify and fork. Keep the copyright notice and licence text,
and credit the original project (`github.com/dexxfm/dexxicon-reader`).

Bundled third‑party components keep their own licences: Readium toolkit — BSD‑3‑Clause;
Media3 / AndroidX — Apache‑2.0; junrar — UnRar restriction + free for non‑extraction‑tool
use.
