# Phase 4 — design vs. implementation audit

Running tracker for the screen-by-screen check of the Claude Design mockup against what the
app actually renders. Issue [#115](https://github.com/dexxfm/dexxicon-reader/issues/115) is the
parent; [PR #120](https://github.com/dexxfm/dexxicon-reader/pull/120) landed the shell/token
layer only, so every screen below is still on its pre-Phase-4 content.

This file exists because the audit spans sessions and **remote/cloud Claude Code sessions start
from a fresh clone with no memory of prior ones** — anything not committed here is gone. Record
findings as you go, not at the end.

## Source of truth

The mockup is `Dexxicon Reader.dc.html` from a Claude Design handoff bundle. **It is not in this
repo** — it has only ever been passed in per-session as a local export/upload, which is why a
cold session cannot audit anything without the user re-attaching it.

> **Recommendation:** commit the bundle under `docs/design/` so any session can diff against it
> unaided. Until that happens, every audit session needs the zip attached by hand.

Within the bundle, `project/github.md` carries the screen→source map and a sync history. Note it
is **newer than issue #115** (synced 05:26Z, issue filed 05:23Z) and disagrees with it in one
place: the Library tab maps to `BrowseScreen.kt` + `BrowseControls.kt`, not `CatalogScreen.kt`.
Trust `github.md`.

Mockup artboards (14): phone `Prototype` (dark) and `Prototype light` — each a single frame
switching between Home / Library / Book detail / Settings — plus `Player`, `EPUB reader`,
`Fold library + detail`, `Fold player`, `Fold EPUB`, `Tablet library + detail`, `Tablet player`,
`Tablet EPUB`, `Auto browse root`, `Auto per-server grid`, `Auto now playing`, `CarPlay`.
The prototype's own `accent` prop defaults to **Aqua**, confirming PR #120's pick.

## How this gets tested

- **Phone layouts only, so far.** Fold and tablet artboards exist but have not been checked
  against the app.
- Live checks run on the user's Windows machine (repo at `E:/ClaudeCode/`) against **3 Android
  Studio emulators**, which need restarting between rounds.
- **Android Auto** is tested over USB against a physical phone. Not currently connected —
  deferred until later (issue #118).
- A cloud session can do the static comparison (mockup markup vs. Kotlin source) but **cannot run
  emulators or reach `E:/ClaudeCode/`**. Split the work accordingly: source-level diff here,
  on-device confirmation there.

## Status

| Screen | Mockup artboard | Implementation | Phone | Fold | Tablet |
|---|---|---|---|---|---|
| Home | `Prototype` / `…light` | `feature/library/.../LibraryScreen.kt` | done — findings not captured | not started | not started |
| Book detail | `Prototype` / `…light` | `feature/catalog/.../BookDetailScreen.kt` | **audited — #121, #122** | not started | not started |
| Library tab | `Prototype` / `…light` | `feature/catalog/.../BrowseScreen.kt`, `core/designsystem/.../BrowseControls.kt` | not started | not started | not started |
| Settings | `Prototype` / `…light` | `feature/settings/.../SettingsScreen.kt` | not started | not started | not started |
| Server browsing | — (check coverage) | — | not started | not started | not started |
| Audiobook player | `Player` | `feature/player/.../PlayerScreen.kt` | deferred — issue #116 | | |
| EPUB reader | `EPUB reader` | `feature/reader-epub/.../EpubReaderScreen.kt` | deferred — issue #117 | | |
| Android Auto | `Auto …` ×3 | `core/media/.../PlaybackService.kt` | deferred — issue #118, needs USB phone | | |
| CarPlay | `CarPlay` | none — proposal only | deferred — issue #119 | | |

> **Home's findings were never written down** — they were produced in a session whose transcript
> isn't recoverable from the cloud. Re-run the Home comparison, or paste the findings in here,
> before treating Home as closed.

## Findings

### Book detail — phone (2026-09-12)

Compared the mockup's `isDetail` block against `feature/catalog/.../BookDetailScreen.kt`.
PR #120 does **not** touch this file, so all of it is pre-Phase-4.

Filed as [#121](https://github.com/dexxfm/dexxicon-reader/issues/121) (the reskin, findings 1 and
3–8) and [#122](https://github.com/dexxfm/dexxicon-reader/issues/122) (finding 2, a functional
gap rather than a reskin).

**Structural**

1. **Back affordance.** Mockup has no app bar: a single pill `BACK` button (uppercase, 44px min
   height, `--color-surface` fill, radius 999) sits inline above the content, and the title
   appears once at 22px beside the cover. The app uses an M3 `TopAppBar` with the title
   duplicated in it plus an `ArrowBack` `IconButton`. Removing the app bar is the single biggest
   visual delta on this screen.
2. **Reading progress is missing entirely.** The mockup shows a 6px pill progress bar plus a
   `progressDetail` caption directly under the primary action. The app renders no reading
   progress on detail at all — its only `LinearProgressIndicator` tracks *download* progress.
   This is a functional gap, not a reskin: `BookDetail` (`core/model/.../Browse.kt`) carries
   `readingStatus` but no progress field. The data exists — `LibraryViewModel` already injects a
   `progressRepository` and keys progress by book for the Home shelf — so the fix is to plumb the
   same source into `BookDetailViewModel`.

**Shape / token violations** (all contradict `docs/styles.md`, which PR #120 added)

3. **Cover** — mockup 120×180, radius 16 (= `CoverShapeMedium`), 1px divider border. App
   hardcodes `RoundedCornerShape(8.dp)` with no border, and bypasses the shared `CoverImage`
   component entirely.
4. **Server chips** (`ServerChip`) — mockup is a true pill; app uses `RoundedCornerShape(8.dp)`.
   Direct violation of the "pill for all chips/tags" rule.
5. **Format / status tags** — mockup is an outline pill + an accent-filled pill, 10px, no icons.
   App uses two M3 `AssistChip`s at M3's default corner, and the status chip carries a leading
   `MenuBook` icon the mockup doesn't have.
6. **Primary action button** — mockup is full-width, 54px min height, **left-aligned** content
   with a 22px left inset. App uses a default-height M3 `Button` with centred content, and fakes
   the icon gap with a two-space string prefix (`"  Play"`) instead of real spacing.
7. **Offline button** — mockup is 48px min height, left-aligned, pill. App uses a default
   `OutlinedButton`, centred.
8. **Details block** — mockup wraps the rows in a `--color-surface` card at **radius 18** with
   per-row bottom dividers; labels are a 104px uppercase 11px micro-caps column, values 13px
   **weight 600**. App renders bare rows, no card, no dividers, 96dp label column, sentence-case
   `bodySmall` labels and regular-weight values. Note 18px is not in `DexxiconShapes`
   (10/14/16/20/28) — either add it or settle on `medium` 16dp.

**Systemic**

9. **Section headings.** Mockup styles `About` / `Details` as 13px uppercase micro-caps with
   `.1em` tracking and a 1px divider rule above each section. The app uses plain `titleMedium`
   with no rule. The same micro-caps idiom recurs on Home's shelf headings (`Continue`,
   `On Deck`, `Downloaded`), so this wants one shared `SectionHeading` composable in
   `core/designsystem` rather than a per-screen fix.

**Not defects**

10. The mockup models only one offline state; the app's `DownloadButton` handles queued /
    running / done / failed with progress and error text. Keep the app's behaviour — the mockup
    is under-specified here, not the app over-built.
11. The fold artboard's Details list shows 6 fields (Format, Narrator, Publisher, Published,
    Duration, File size) against the app's 10. That's an audiobook sample, not a spec to trim to.

**Also spotted, unrelated to the screen**

12. [#123](https://github.com/dexxfm/dexxicon-reader/issues/123) — `docs/styles.md` (added by
    PR #120) contradicts PR #120. Its Typography section says "the
    mockup's Archivo display face was not adopted" and Known gaps repeats "Archivo … isn't
    bundled or wired in" — but that same PR bundles `archivo_{regular,semibold,extrabold}.ttf`
    on both platforms and rewrites `Type.kt`. The doc is stale as merged; fix it before it
    misleads the next reader.

### Book detail — `:shared`

Not audited. `shared/.../catalog/BookDetailScreen.kt` is a 173-line port against native's 500 and
uses `FilterChip`/`TextButton` where native uses `AssistChip`; it needs its own pass rather than
an assumption that findings carry over.
