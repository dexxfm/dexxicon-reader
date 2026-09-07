# Changelog

## 0.9.0 — 2026-09-07

Reading status, better Browse controls, and the Library fills itself in.

- **Reading status** — every book's status (Unread, Want to read, Reading, On hold,
  Rereading, Read, Did not finish) shows on its detail screen and can be set there or from
  the long-press menu's new **Reading options**. "Mark as read" / "Mark as unread" set the
  status too, so the position and the status never disagree. On a book that lives on more
  than one server, the change applies to **every** copy.
- **Browse** gets format filter chips (All / Books / Comics / Audiobooks / PDFs / Other)
  and a grid ⇆ list toggle; Library gets the same view toggle.
- **Long-press any book** — in Browse, Library, or a single server's catalog — for mark
  read/unread, reading options, book details, and download/remove.
- **The Library fills in on its own** — adding a server (and every sync afterwards) pulls
  that server's own "continue reading" / "continue listening" lists, so books you have in
  progress on the website show up without opening them here first.
- **Settings shows which account** each server is signed in as.

## 0.8.0 — 2026-09-07

Browse is now one library, and reading position round-trips fully with Grimmory.

- **Browse** is a single, de-duplicated list of books merged across every server. A book
  on more than one server appears once, tagged "On N libraries".
- **Server setup moved to Settings** — add / edit / remove servers there; tap one to
  browse just that library.
- Opening a book that lives on more than one server asks **which server** to open it from
  (a bottom sheet, matching the rest of the app's modals).
- **Format badges**: every cover shows a colour-coded tag (EPUB, PDF, COMIC, AUDIO, …) in
  its bottom-right corner; the legend is in Settings.
- **Grimmory two-way sync now carries the exact page**, not just a percentage — read a
  comic or PDF in the app and the website resumes on the right page, and vice-versa. Also
  fixes silent progress-push failures and adds page sync for BookOrbit.

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
