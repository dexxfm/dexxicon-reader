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
  or **OIDC / SSO** through an in‑app WebView. Sessions are refreshed proactively in the
  background; if an SSO session can no longer be renewed the app prompts you to sign in
  again rather than failing silently.

> **SSO / OIDC needs `offline_access`.** A long‑lived refresh token is only issued when the
> authorize request includes the `offline_access` scope *and* the identity provider allows
> it. The app now appends `offline_access` to the scopes automatically, but the IdP side
> must permit it: in **Authentik**, add the *offline_access* scope mapping to the OAuth2
> provider (and to the application's allowed scopes); on **BookLore / BookOrbit**, leave
> the OIDC "scopes" setting blank or include `offline_access` so it isn't stripped.
> Without it an SSO session dies the moment its short access token expires and the only
> recovery is a manual re‑sign‑in.
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
silence**, configurable skip‑forward/back intervals, rewind‑on‑resume. The player shows
the current audio route (speaker / Bluetooth / headphones) and opens the system output
switcher in one tap. A mini‑player bar sits above the bottom navigation while audio is
active; its **X** ends playback and clears the notification.

- **Android Auto.** Audiobooks are browsable and playable from the car — Continue
  listening, Downloaded, and all audiobooks (split per server), plus search. The
  now‑playing screen has cover art, chapter, scrubber and rewind‑15 / play / forward‑30;
  playback resumes where you left off and position syncs back to the server.

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

### Settings
- **Servers** — drag to set display priority (used for the server list, the sync list, and
  which library's books come first when browsing).
- **Appearance** — theme (system / light / dark) and the default book layout (grid or
  list) for Browse and server catalogues; each screen keeps its own toggle.
- **Downloads** — Wi‑Fi‑only queueing and a total storage cap (default 10 GB); a download
  that would exceed the cap is skipped.
- **Reading sync** — per‑server channel and last‑synced time; `kosync` account setup for
  generic OPDS servers.

### Design & privacy
- Jetpack Compose + Material 3, dark‑blue/grey theme, light/dark, edge‑to‑edge.
- No ads, no analytics, no third‑party SDKs. The app connects only to the servers you add;
  credentials are encrypted on‑device with a hardware‑backed key. Crashes are saved locally
  and only emailed if you choose to, after reviewing the contents. See [PRIVACY.md](PRIVACY.md).

## Status

Usable daily‑driver for BookOrbit and Grimmory. Current version **0.10.1** — see
[Releases](https://github.com/dexxfm/dexxicon-reader/releases) for APKs and Play‑ready
App Bundles, and [CHANGELOG.md](CHANGELOG.md) for what each one brought.

Adaptive tablet/foldable layouts, Android Auto, and a Play Console upload are done.

**Not done yet:** MOBI/AZW3/FB2 conversion, OPDS‑PSE comic page streaming. Kobo sync was cut.

## Tech

Kotlin 2.4 (AGP 9.4's built‑in compiler) · Gradle 9.6 · KSP 2 · Jetpack Compose / Material 3
· Hilt · Room (schema v8) · Media3 (incl. `MediaLibraryService` for Android Auto) · Readium
Kotlin toolkit 3.3 (+ PDFium adapter) · Coil 3 · WorkManager · junrar · DataStore ·
kotlinx‑serialization + Retrofit + OkHttp.

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
