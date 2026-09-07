# Changelog

## 0.7.4 — 2026-09-06

Adaptive layouts, landscape, and foldable support.

- Navigation rail replaces the bottom bar at ≥600 dp wide — phones in landscape, unfolded
  foldables, and tablets.
- Audiobook player has a dedicated landscape layout (cover left, controls right).
- Book detail is a two-pane layout on tablets/foldables (cover + actions beside the
  description) instead of stretching edge to edge; Settings and detail text stay width-capped.
- The app no longer recreates itself when a foldable is opened or closed (or on rotation),
  so the reader/player keep their state across a fold.
- Reader fixes: single-step swipe-to-turn in the EPUB, comic, and PDF readers; removed the
  extra headroom above every screen's top bar.
- Library/catalog covers show a reading-progress bar and a "downloaded" badge.
- Predictive-back slide transitions between screens.
- Accessibility: grid cards and reader sliders announce their state to TalkBack.
- Fixed a two-way sync bug where a first read of a book with no server-side progress could
  fail to parse an empty response.

## 0.7.3 — 2026-09-06

- Comic archives: load CBR (RAR) comics from a server, not just local files, and cache the
  unpacked copy so a re-open costs no download.
- Book detail shows the actual file extension (epub, cbz, m4b…) as the format.

## 0.7.2 — 2026-09-06

- Reading-progress sync now uses each server's own library API for BookOrbit and Grimmory
  (the same channel as their web readers); KOReader `kosync` is reserved for generic OPDS
  servers. Fixes cases where a position set on the website didn't reach the app or vice versa.
- Grimmory `bookFileId` is cached to avoid a lookup on every progress push.
- Added a privacy policy (`PRIVACY.md`).

## 0.7.1 — 2026-09-06

- Native two-way reading/listening progress sync against the BookOrbit and Grimmory
  web-reader APIs (ebook, PDF, comic, audiobook).
- Play Store package identity (`com.dexxfm.dexxicon_reader`).

## 0.7.0 — 2026-09-06

- Pull-to-refresh on Library and Settings; pull affordance on Browse.
- Swipe left/right to turn pages in the readers.
- Audiobook player audio options: skip silence, adjustable skip intervals, rewind-on-resume,
  end-of-chapter sleep timer.
- Settings shows "Last synced" for every configured KOReader sync account.

## 0.6.0 — 2026-09-06

- Two-way KOReader (`kosync`) progress sync wired into all readers and the player.
- Library gains "Continue reading" and "Continue listening" shelves.
- CBR comic support (local files).

## 0.5.0 — 2026-09-06

First tagged build. Project scaffold only — feature screens are placeholders.

- Multi-module Android project (22 modules) on AGP 9.4 / Kotlin 2.4 / Gradle 9.6 / KSP2.
- Jetpack Compose + Material 3, dark-blue/grey brand theme, fox adaptive launcher icon.
- App shell: Hilt Application + WorkManager, edge-to-edge activity, bottom-nav NavHost
  (Library / Browse / Settings) with placeholder content.
- Core domain models for servers/auth, OPDS catalog feeds + OPDS-PSE, content-format
  media-type mapping, library items / downloads / reading positions / annotations.
- Signed release APK build wired up (`assembleRelease`).

Not yet implemented: server management, OPDS browsing, downloads, any reader, sync.
