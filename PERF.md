# Performance pass

A measured sweep over startup, scrolling, image loading, and the network layer. This file
records what was measured, what changed, and what was deliberately left alone.

## Method

- **Baseline Profile** — a new `:baselineprofile` macrobenchmark module
  (`BaselineProfileGenerator`) records the startup path and a scroll of each top-level
  screen. The profile is bundled into the release APK/AAB and installed by
  `androidx.profileinstaller` on first run, so ART pre-compiles the hot paths.
  Regenerate with `./gradlew :app:generateBaselineProfile` (one connected API 33+ device).
- **Startup timing** — `StartupBenchmark` measures cold start under three compilation
  modes. Run `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
- **Compose** — `-PcomposeMetrics` dumps per-module skippability reports to
  `<module>/build/compose-metrics/`.

## Cold start (Pixel_10_Pro_Fold emulator, API 37, `benchmarkRelease`, 10 iterations)

| Compilation mode | timeToInitialDisplay | notes |
|---|---|---|
| `None` (no profile) | **583 ms** median, 552–1185 | worst case — a first run before `profileinstaller` applies the profile |
| `Partial(BaselineProfile)` | **538 ms** median, 495–747 | what users get from launch 2 on |
| `Full` (everything AOT) | 723 ms median, 658–878 | not profile-guided; slower here, as often happens |

The profile trims ~45 ms (8%) off the median **and roughly halves the tail** — worst-case
launch drops from 1185 ms to 747 ms. Emulator timings understate the win (fast disk, no
real JIT pressure); a physical device usually sees more. Rerun on a real phone with
`./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` for trustworthy absolute
numbers (the emulator-suppression flag becomes a no-op there).

## Changes

### Baseline Profile (`:baselineprofile`, `app/src/release/generated/baselineProfiles/`)
The single biggest lever for cold start and first-scroll jank. `baseline-prof.txt` carries
~29k pre-compile rules (≈1.5k app classes); `startup-prof.txt` is the smaller
startup-critical subset compiled at install time.

### Image loading (`DexxiconApplication.newImageLoader`)
Coil's `ImageLoader` now declares an explicit **memory cache** (25% of app RAM) and a
**256 MB disk cache** at `cacheDir/image_cache`. Covers previously had no persistent cache,
so every app launch re-downloaded the whole visible grid through the authed client; they
now come straight off disk.

### Network logging (`NetworkModule.provideLoggingInterceptor`)
`HttpLoggingInterceptor` was pinned to `BASIC` in every build. It's now `BASIC` only in
debug and `NONE` in release — no per-request string formatting, and request URLs no longer
land in logcat on user devices. (`core:network` gained `buildConfig = true`.)

### Auth refresh storm (`TokenManager`)
When a session died, every pending request 401'd at once and each 401 triggered its own
`/auth/refresh` — ~10–20 serial refresh calls per sync, all failing. A 30 s negative cache
(`refreshFailedAt`) now short-circuits repeat attempts until a real sign-in
(`seedSession`) or `invalidate` clears it. Cuts request volume and server load for an
expired session roughly in half and removes the per-server mutex pile-up.

### Startup work (`DexxiconApplication.onCreate`)
`SessionRefreshWorker.schedule` (which forces first `WorkManager.getInstance()` init +
a periodic-work enqueue, both disk I/O) is now posted to a daemon thread instead of
running inline on the main thread. The 6-hour cadence doesn't care about the few ms.

## Looked at, left alone

- **Compose stability** — already clean. Kotlin 2.4's strong-skipping mode is on, so every
  composable in `catalog` / `library` / `player` is already `restartable skippable` and the
  UI-state classes report as `stable` **without** a stability-config file. No work needed.
- **Lazy list keys** — every `items(...)` that matters already passes a stable `key`.
- **Room indices** — the tables hold at most a few hundred rows; `WHERE key = :key` hits the
  primary key and the rare non-indexed scans are sub-millisecond. A migration isn't worth it
  at this scale.
- **R8 keep rules** — `proguard-rules.pro` keeps `org.readium.**` and `androidx.media3.**`
  wholesale. Both could likely be narrowed (Media3 ships its own consumer rules; Readium
  needs only its `@Serializable` models kept), but Readium's reflection is fragile and the
  risk/reward is poor without a full release-build regression pass. Left as a follow-up.
- **APK size / ABI splits** — the AAB is already split per-ABI by Play; the GitHub-release
  APK is intentionally a universal APK so sideloaders don't have to know their ABI.
- **`AuthHeaderProviderImpl` `runBlocking`** — the per-request `runBlocking` sits on OkHttp
  dispatcher threads (not main) and the common path returns a cached token immediately, so
  it's cheap. The negative cache above removes the expensive (network) case.

## Follow-ups

- Narrow the Readium / Media3 R8 keeps behind a full release smoke test.
- Regenerate the baseline profile whenever the startup path or a top-level screen changes
  materially (it's not auto-generated on every build).
