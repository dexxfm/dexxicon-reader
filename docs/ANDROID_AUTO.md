# Android Auto — declaration & release checklist

Dexxicon Reader exposes its audiobooks to **Android Auto** (phone projected onto the car
screen). It is a **media app** using the standard media browse/playback templates — there
is no custom car UI, and it is **not** an Android Automotive OS (AAOS) native app (no
`android.hardware.type.automotive` feature, no car-specific APK).

Google reviews every media app before it can appear in a car, against the
[media app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality)
and the
[Android Auto Driver Distraction Guidelines](https://developer.android.com/training/cars/media#driver-distraction).
This document is what we submit against.

---

## 1. What the app declares (already in the manifest)

| Declaration | Where | Value |
|---|---|---|
| Car app support | `app/src/main/AndroidManifest.xml` | `<meta-data android:name="com.google.android.gms.car.application" android:resource="@xml/automotive_app_desc"/>` |
| Car app type | `app/src/main/res/xml/automotive_app_desc.xml` | `<automotiveApp><uses name="media"/></automotiveApp>` |
| Browse + playback service | `core/media` `PlaybackService` | `androidx.media3.session.MediaLibraryService` (Media3 1.11) — Media3 auto-bridges the legacy `android.media.browse.MediaBrowserService` that Auto connects through |
| Service intent filters | manifest | `MediaLibraryService`, `MediaSessionService`, `android.media.browse.MediaBrowserService` |
| Foreground service type | manifest | `mediaPlayback` (+ `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission) |
| App category | manifest `<application>` | `android:appCategory="audio"` |
| Authenticated cover art | `core/media` `ArtworkProvider` | exported, HMAC-signed `content://<appId>.artwork/cover` proxy so the car (which can't send our bearer token) can still load covers |

No new permissions and no new Gradle dependencies are needed for Android Auto — `media3-session`
already contains `MediaLibraryService`.

---

## 2. Play Console — enabling Android Auto

1. **Play Console → your app → Release → Setup → Advanced settings → Form factors** (wording
   varies) → **Android Auto** → *Add form factor* / opt in.
2. Complete the **Android Auto declaration**: category **Media**, confirm there is no video
   content and no custom UI.
3. Upload an App Bundle (`./gradlew :app:bundleRelease`) to a track.
4. **Submit for Android Auto review.** Media apps stay hidden in the car until this review
   passes — it is separate from normal store review and can take a few days.
5. After approval, the app shows up in the car's media-app picker for anyone with the app
   installed.

There is nothing to configure in the APK beyond section 1 — the review is purely a check of
runtime behaviour.

---

## 3. Media app quality checklist

Mapped to the current implementation (`core/media/PlaybackService.kt`,
`AutoLibraryCallback`, `MediaLibraryContentSourceImpl`):

| Guideline | Status | Notes |
|---|---|---|
| Extends `MediaBrowserService` / `MediaLibraryService` | ✅ | Media3 `MediaLibraryService` |
| Root is browsable; hierarchy is shallow (≈ ≤ 3 levels) | ✅ | `root → {Continue, Downloaded, All audiobooks → [server →] page}` |
| Content style hints for browsable vs playable | ✅ | `EXTRAS_KEY_CONTENT_STYLE_*` — list for folders, grid for books |
| Paging for long lists | ✅ | `onGetChildren(page, pageSize)`; "All audiobooks" auto-pages up to 200 items |
| `MediaMetadata` complete (title, subtitle/artist, art) | ✅ | title + author + `artworkUri` (via `ArtworkProvider`) |
| Playback position / completion state on browsable items | ✅ | `EXTRAS_KEY_COMPLETION_STATUS` + `EXTRAS_KEY_COMPLETION_PERCENTAGE` |
| Standard playback controls via `MediaSession` | ✅ | play/pause, seek, 30 s / 15 s skip (`setSeekForward/BackIncrementMs`) |
| Session activity for the now-playing card | ✅ | `setSessionActivity(launchIntent)` — also the media-notification tap target |
| No chapter-as-track skipping that could distract | ✅ | one `MediaItem` per book; skip is time-based only |
| Playback resumption after reboot / BT connect | ✅ | `onPlaybackResumption` → `lastPlayed()` |
| Search | ✅ | `onSearch` / `onGetSearchResult` → audiobook-filtered catalog search |
| Sign-in / setup errors surfaced with a resolution action | ✅ | `LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED` / `_SESSION_SETUP_REQUIRED` + `EXTRAS_KEY_ERROR_RESOLUTION_ACTION_*` deep-linking to the app; `Downloaded` stays usable offline |
| Works offline for downloaded content | ✅ | `resolve()` falls back to `resolveFromLocal()` — plays the downloaded file at the last saved position with no server round-trip |
| No ads, no video, no autoplay of full-screen/promotional content | ✅ | — |
| Audio focus + becoming-noisy handled | ✅ | `handleAudioFocus = true`, `setHandleAudioBecomingNoisy(true)` |
| Custom actions within limits | ✅ | skip-silence is an in-app session command, **not** a car-surfaced custom action |
| Headless auth on a cold car start | ✅ | Verified on the AAOS emulator: launching `PlaybackService` straight from the car (no app UI) streams + plays with a token the service acquired itself. NATIVE = password login; OIDC = refresh-token flow (proactive refresh + rotation hardening from PR #2); a dead refresh token → error node + `Downloaded` still works. |

---

## 4. Driver distraction

The app draws no car UI of its own — Android Auto renders the browse list and the playback
screen from our `MediaSession` / `MediaLibraryService`, so the distraction-optimised
templates, tap limits and text truncation are enforced by the platform. Nothing in the app
opens an Activity on the car display. The only app-launched `PendingIntent` is the
"sign in" resolution action, which opens on the **phone**, not the car.

---

## 5. Testing before submission

### Desktop Head Unit (DHU) — full setup

**On the computer (one time):**
1. Android Studio → **Tools → SDK Manager → SDK Tools** tab → tick **"Android Auto Desktop
   Head Unit Emulator"** (and **"Android SDK Command-line Tools (latest)"**) → **Apply**.
   Installs to `<SDK>\extras\google\auto\` (`desktop-head-unit.exe` on Windows).

**On the phone (one time):**
2. **Developer options**: Settings → About phone → tap **Build number** 7×.
   Then Settings → System → Developer options → enable **USB debugging**.
3. **Android Auto app**: install / update **Android Auto** from the Play Store. Open it once
   and complete first-run setup (it may say "connect to a car" — that's fine).
4. **Android Auto developer mode**: open Android Auto's settings —
   Settings → **Connected devices → Connection preferences → Android Auto**
   (or search "Android Auto" in Settings). Scroll to the bottom and tap **"Version"** /
   *"Version and permission info"* about 10× until it toasts *"Developer mode enabled"*.
   A **⋮ menu → Developer settings** now appears. In there enable:
   - **"Unknown sources"** — required, or a sideloaded app won't show in the car.
   - **"Start head unit server"** — tick it (a persistent notification appears).
   > If the Version row isn't tappable / dev settings never appear: the phone hasn't
   > completed AA setup, or the OEM hid it. Connecting to DHU once (step 6) usually unlocks
   > it; otherwise try the standalone *Android Auto* entry in the app drawer or
   > `adb shell am start -n com.google.android.projection.gearhead/.companion.DeveloperHeadunitLauncherActivity`.

**Each session:**
5. Plug the phone in over USB; accept the debugging prompt. Confirm `adb devices` lists it.
6. `adb forward tcp:5277 tcp:5277`
7. `cd "<SDK>\extras\google\auto"` then `desktop-head-unit.exe`
   (macOS/Linux: `./desktop-head-unit`). The DHU window opens; the phone shows "Android
   Auto" projecting.
8. `adb install -r app\build\outputs\apk\debug\app-debug.apk` — then in the DHU, open the
   **media app switcher** (top bar) and pick **Dexxicon Reader**.

Walk the checklist:
   - browse **Continue listening / Downloaded / All audiobooks** (→ per-server when >1),
   - play from each; confirm it resumes at the right position and pushes back on pause,
   - cover art loads in the grid and on the now-playing screen,
   - next/prev + 30 s / 15 s skip, pause/stop,
   - search returns audiobooks,
   - airplane mode → `Downloaded` still plays,
   - expire an OIDC session → **All audiobooks / <server>** shows the "sign in" action, and
     `Downloaded` is unaffected.

### Media Controller Test (MCT)
Google's [Media Controller Test app](https://github.com/googlesamples/android-media-controller)
connects to `PlaybackService` directly and exercises browse + transport controls without a
car — fastest way to catch a broken `MediaItem` or missing action.

### Automotive emulator (AAOS)
Not required for the Android Auto declaration (that's phone-projected), but useful:
AVD `Automotive with Google Play`, then
`adb shell am start -a android.car.intent.action.MEDIA_TEMPLATE -e android.car.intent.extra.MEDIA_COMPONENT "com.dexxfm.dexxicon_reader/net.dexxicon.reader.core.media.PlaybackService"`.

---

## 6. Status

**Verified on a real phone (Galaxy Z Fold 6, Android 16) over the Desktop Head Unit**, on
the release build:

- ✅ App appears in the car media-app launcher; browse tree renders — **Continue listening
  / Downloaded / All audiobooks → per-server → grid**, with titles, authors and progress.
- ✅ **Cover art loads** everywhere (browse grid *and* now-playing) via `ArtworkProvider`.
- ✅ **Now-playing screen renders correctly**: cover, title/artist, scrubber, art-derived
  background, and **pause / rewind-15 / fast-forward-30** — the seek buttons move the
  position. (Fixed by `setSessionActivity`, plain-URL now-playing art, and
  `setMediaButtonPreferences`.)
- ✅ Play from a browsed item resumes at the right position; pause/resume work.
- ✅ Headless token acquisition (service launched straight from the car, no 401).

- ✅ `./gradlew :app:bundleRelease` produces a signed `app-release.aab` ready for Play.

> The AAOS `Automotive_Distant_Display` emulator can't render a sideloaded app's now-playing
> template or list it in the media-app picker — always test Android Auto on the **DHU with a
> real phone**, not that emulator.

### Offline downloaded-audiobook playback — inspection-verified

The car resolves a browsed id via `MediaLibraryContentSourceImpl.resolve()`. When
`catalogRepository.detail()` fails (no network / expired session) it falls back to
`resolveFromLocal()`, which returns a `PlayableAudiobook` pointing at
`Uri.fromFile(downloadedFile)` with the last saved position — no server call. The player's
`DefaultDataSource.Factory` opens that `file://` URI via `FileDataSource`, and the same
factory's streaming path is DHU-verified. Not clicked through end-to-end (the only
audiobook on the test account is 1.2 GB); **spot-check next time a book is downloaded**:
airplane mode → car → *Downloaded* → play.

### Remaining (your action)

- [ ] Upload `app/build/outputs/bundle/release/app-release.aab` to a Play track.
- [ ] Play Console → add the **Android Auto** form factor → complete the declaration
      (category *Media*, no video, no custom UI).
- [ ] Submit for **Android Auto review**.
- [ ] Optional: **Media Controller Test** pass (the DHU pass already covers browse +
      transport).
