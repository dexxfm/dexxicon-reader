# Changelog

## 0.11.4 — 2026-09-09

PDF reader settings and a fuller diagnostics report.

- **PDF display settings and bookmarks.** The PDF reader gained a settings sheet
  (background colour for the margins, page fit — fit vs. width, and paged vs. continuous
  scroll) and page bookmarks — a top-bar toggle plus a list, synced with the server like
  EPUB bookmarks. A Contents sheet shows when the PDF has an embedded outline. (Text search
  and highlights need a different PDF engine and aren't included.)
- **Report a problem now attaches a log zip.** Both the crash prompt and Settings › Report
  a problem bundle the app's logcat, any saved crash reports, and a device summary into a
  zip attached to the email. Nothing is collected or sent until you send it.

## 0.11.3 — 2026-09-09

Settings that survive updates, a tidier server form, and clearer book details.

- **Your settings now survive an app update or a restore.** Backup rules were added so the
  library database and every preference round-trips through a reinstall or a move to a new
  phone (the encrypted credential store stays on-device — you re-enter server passwords).
  A release build can no longer be published with the wrong signing key, which was the
  other way an update could force an uninstall.
- **Reworked Add/Edit server screen.** Server name is the first field; Save stays enabled
  while editing so you can change any detail without re-running Test connection;
  "Sign in with SSO" sits just above Save; and KOReader sync moved into its own
  "KOReader (optional)" sheet instead of three inline fields.
- **Book details show where the book lives.** Under the format and reading-status chips,
  small chips name the server(s) the book is on, by their display name.

## 0.11.2 — 2026-09-09

Casting, clearer reader tools, and new page layouts.

- **Google Cast for audiobooks.** A Cast button on the now-playing screen opens the system
  device picker; picking a Chromecast hands playback to it at the current position and the
  controller dialog (volume, stop casting) works. Stopping brings playback back to the phone
  where it left off. Streams that need a login are cast with a scoped token; local-only
  downloads can't be cast.
- **Three separate audio buttons on the player.** Cast, audio output, and audio options are
  now distinct — audio options no longer carries the output list. Tapping a device in the
  output sheet switches immediately, with no Done button or extra sliders. Bluetooth media
  now routes to the A2DP device (not the call-audio path that dropped it to mono), and the
  player follows the newest Bluetooth or Android Auto connection automatically.
- **Reader tools that don't look alike.** Highlights now use a marker/underline icon instead
  of one nearly identical to bookmarks, and bookmarks have their own dedicated list button in
  the top bar. Bookmarks are gone from the Contents sheet (the list button replaces them).
  Each bookmark row shows how far through the chapter you were, plus the page number when the
  book has one — e.g. "43% – Page 87".
- **EPUB page layout options.** New in the display-settings sheet: reading background
  (System / White / Sepia / Grey / Black), page fit (Fit / Width / Height / Actual size),
  page layout (Auto / Single / Two-page — two columns on tablets and unfolded foldables), and
  Paged / Scroll as a proper setting. EPUB only for now.

## 0.11.0 — 2026-09-08

Bookmarks, a faster start, and crash reports you control.

- **EPUB bookmarks.** A bookmark toggle in the reader's top bar (filled when you're on a
  bookmarked spot) and a Bookmarks section at the top of the Contents sheet — tap to jump,
  X to delete. Bookmarks sync both ways with the server (BookOrbit and Grimmory both have
  full bookmark APIs); one you made in a server's web reader shows up here and jumps to its
  chapter. EPUB only — comics, audiobooks and PDFs are unaffected.
- **Performance pass.** A baseline profile ships with release builds (cold start measured
  ~583 ms → ~538 ms on an emulator, worst-case frame 1185 ms → 747 ms), the image cache is
  sized explicitly (25% RAM / 256 MB disk), verbose network logging is gone from release,
  auth back-off only trips on a real 401 (not a flaky network), and WorkManager scheduling
  is off the startup thread. Full method and numbers in [PERF.md](PERF.md).
- **Opt-in crash reporter.** If the app crashes, the next launch offers to show you the
  full report; you can copy it or send it by email. Nothing leaves the device unless you
  choose to send it, and you see the exact contents first.
- **Android Auto now-playing artwork.** The cover art is embedded as bytes in the media
  session, so it shows on the car's now-playing screen (and the mini-view) even though the
  app can't hand the car your login.
- Settings chip rows (theme, book layout, storage limit) wrap to the next line instead of
  running off the edge on narrower phones.

## 0.10.1 — 2026-09-08

Settings you asked for, and a stop button that stops.

- **The mini-player "X" now stops the audiobook.** It used to just detach the controls
  while the service kept playing in the background; now it ends playback and clears the
  media notification.
- **Audio output on the player.** The now-playing screen shows where sound is going —
  phone speaker, a Bluetooth device, or headphones — with a one-tap icon (and a row in
  Audio options) that opens the system output switcher. Android has no supported way to
  pin a default output from an app, so switching is handed to the OS chooser.
- **Storage limit for downloads (Settings › Downloads).** A cap on total downloaded-media
  size, default 10 GB, with a "X used of Y" readout. A download that would push you over
  the limit is skipped with a message rather than filling the disk.
- **Server display priority (Settings › Servers).** Press and hold the handle to drag
  servers into the order you want. That order is used everywhere — the server list, the
  reading-sync list, and which library's books come first when browsing.
- **Default book layout (Settings › Appearance).** Choose whether Browse and a server's
  catalogue start as a grid or a list. Each screen still has its own toggle; changing the
  default here snaps them all back to it.
- Release builds now embed native debug symbol tables so crash traces from the bundled
  PDF/graphics libraries are readable in Play Console.

## 0.10.0 — 2026-09-08

Android Auto, and sign‑ins that stay signed in.

- **Android Auto.** Audiobooks are now browsable and playable from the car — Continue
  listening, Downloaded, and All audiobooks (split per server when you have more than one),
  plus search. The now‑playing screen shows cover art, chapter, a scrubber, and
  rewind‑15 / play / forward‑30. Playback resumes where you left off, position syncs back
  to the server, and downloaded books play with no signal. Cover art loads in the car even
  though it can't send your login.
- **SSO sessions survive being left alone.** OIDC/SSO logins are refreshed in the
  background (and just before a sync) so an idle phone still has a live session, and the
  refresh token is rotated safely. If a session genuinely can't be recovered, a banner and
  a notification take you straight back into sign‑in for that server instead of a dead end.
  The app now always requests the `offline_access` scope — see the README for the
  identity‑provider setup.
- **Audiobook player fixed on foldables.** On an unfolded foldable (or a phone in
  landscape) the player kept the stacked layout instead of squashing the controls into a
  strip next to a full‑height cover; the "Sleep timer" label no longer wraps one letter
  per line.

## 0.9.1 — 2026-09-07

- **Downloads no longer jump around.** The Library "Downloaded" shelf and the progress
  notification keep a fixed order (the order you queued them) instead of reshuffling on
  every progress tick.
- **One download notification for everything.** Concurrent downloads show as a single
  "Downloading N books" notification that lists up to five at once with their individual
  progress, rather than flickering between titles.
- **Browse recovers after adding a server.** It now refreshes itself when you add or remove
  a server, and pull-to-refresh works even when the list is empty.
- Removed the grid/list toggle from Library (it stays on Browse and the per-server catalog).

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
