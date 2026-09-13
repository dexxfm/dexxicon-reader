# Dexxicon Reader — style guide

Phase 4 (issue [#115](https://github.com/dexxfm/dexxicon-reader/issues/115)) redesign,
implemented from a Claude Design mockup (`Dexxicon Reader.dc.html`). Lives in
`core/designsystem/theme/`, a single Kotlin Multiplatform module (converted from
Android-only in the Phase 4 restructure's Stage A, issue #126) used directly by both the
native Android app and the Compose Multiplatform `:shared` module — one implementation, not
two copies kept in sync. This document describes what's actually implemented, not the full
mockup — see "Known gaps" at the end for what's deliberately deferred.

## Direction

Fully rounded and floating: pill-shaped buttons, tags, search fields and navigation; soft
14–28px card radii everywhere else; translucent surfaces for anything that overlays content
(the bottom nav, the mini-player). Nothing in this app is a hard 0px corner or a flat square
control. This is a deliberate departure from the mockup's own "Modernist" base design system
(flat, zero-radius, red-on-white, per its `readme.md`) — that base system's tokens are
declared but overridden pervasively in the actual mockup markup, and it's the *rendered*
design that's authoritative here, not the unused base spec.

## Color

Two roles: a fixed **ground** (navy dark / slate light — the app's own brand palette,
unchanged by this redesign) and a swappable **accent**. The mockup ships 4 accent options;
**Aqua** is the one implemented.

### Ground (`theme/Color.kt`)

| Token | Dark | Light |
|---|---|---|
| Background / surface | `Navy900` `#0F1B31` | `Slate50` `#F4F6F9` |
| Surface variant | `Slate700` `#3A414D` | `Slate200` `#C7CED9` |
| Text | `Slate100` `#E7EBF1` | `Slate900` `#191D24` |
| Text, muted | `Slate300` `#9AA4B4` | `Slate700` `#3A414D` |
| Outline / divider | `Slate500` `#6B7688` | `Slate500` `#6B7688` |

### Accent — Aqua

| Role | Dark | Light |
|---|---|---|
| `primary` / `tertiary` (accent) | `AquaAccentDark` `#57C9B4` | `AquaAccentLight` `#0E7A69` |
| `onPrimaryContainer` / emphasis (pressed, text-on-tint) | `AquaAccentDarkHi` `#8FDCCD` | `AquaAccentLightHi` `#0A5B4E` |

The other 3 mockup options (Brand blue `#8FAEDC`/`#35507E`, Amber `#E8B54B`/`#A8760A`, Coral
`#FF9377`/`#C0452A`) are documented in the mockup itself but not wired into the app — Aqua was
the confirmed pick, there's no in-app accent switcher.

Format badges (EPUB/PDF/COMIC/AUDIO/etc., `core/designsystem/component/FormatBadge.kt`) keep
their own fixed, theme-independent colors — they're a legend, not part of the accent system.

## Shape (`theme/Shape.kt`)

| Token | Value | Used for |
|---|---|---|
| `Pill` | `RoundedCornerShape(percent = 50)` | Buttons, chips/tags, search fields, segmented controls, the floating nav bar, the mini-player pill |
| `CoverShapeSmall` | 10dp | Grid/list cover tiles (the default `CoverImage` radius) |
| `CoverShapeMedium` | 16dp | Larger covers (e.g. a book-detail hero, sized up from the grid tile) |
| `CoverShapeLarge` | 22dp | Full-bleed hero covers |
| `DexxiconShapes` (M3 `Shapes`, wired into `MaterialTheme`) | extraSmall 10dp / small 14dp / medium 16dp / large 20dp / extraLarge 28dp | Cards, dialogs, sheets — anything that reads M3's shape slots instead of a named shape above |

Use `Pill` directly (not through `DexxiconShapes`) for anything whose rounding must stay a
true stadium regardless of its own height.

## Typography

Archivo (`core/designsystem/theme/Type.kt`) — the mockup's own display/body face, bundled as
static weights (Regular 400, SemiBold 600, ExtraBold 800) rather than Android's Downloadable
Fonts API (which needs Google Play Services and isn't available to iOS at all); loaded via
Compose Multiplatform resources so the same font files and loading code serve both platforms.
License: `docs/licenses/ARCHIVO_OFL.txt`.

Applied to every M3 type-scale slot (one face for both heading and body, matching the
mockup), keeping each slot's own size/spacing; weights Archivo doesn't declare fall back to
Compose's normal nearest-match font synthesis. Three slots additionally pick up a heavier
weight or extra tracking on top of the base swap: `headlineMedium` and `titleLarge` merge in
SemiBold, and `labelLarge` gets 0.1sp of letter-spacing.

## Navigation shell (`core/designsystem/nav/PillNav.kt`)

Three top-level destinations — Home, Library, Settings — presented one of two ways depending
on width, at the same 600dp breakpoint the app already used for its Material3 rail before
this redesign:

- **`< 600dp` (phone): `FloatingPillNavBar`.** A translucent pill `Surface`
  (`surfaceVariant` at ~82% alpha, 1dp `outlineVariant` border, elevated), icon-only, 74×52dp
  touch targets, no background highlight on the active icon — only its tint changes (accent
  vs. muted). Sits in the `Scaffold`'s `bottomBar` slot with a floating look (margin, pill,
  translucency) rather than reserving space with a true overlay — see that file's doc comment
  for why real backdrop blur isn't attempted.
- **`>= 600dp` (rail — fold/tablet): `PillNavigationRail`.** An 88dp-wide vertical rail;
  every destination keeps a visible label (dimmed when inactive), and the *active* icon gets
  a 56×32dp pill highlight behind it (`primary` at 22% alpha) — a real visual difference from
  the compact bar, matching the mockup's own distinct treatment for the two widths.

### Mini-player (`MiniPlayer.kt`, both platforms)

Same content (cover, title, current chapter, play/pause, dismiss) in two shells:

- **Floating** (phone): a translucent pill above the nav bar, margin on all sides, 36dp cover.
- **Full-width** (rail layouts): flush `Surface`, no margin — matches the mockup's turn 4/5
  (fold, tablet), where the player is no longer a floating chip once the rail replaces the
  bottom nav.

Both variants keep the thin progress line on top (the mockup's own floating pill omits it —
overridden on request).

## Components

- **`CoverImage`** — `CoverShapeSmall` corners (was a flat 8dp `RoundedCornerShape` before
  this redesign).
- **`FormatBadge`** (the small tag on a cover) — `Pill`, not the old 4dp rect. `FormatLegend`'s
  own reference-key swatch is untouched (no legend screen exists in the mockup to match it
  against).
- **Shelf / filter pills, search fields, sort control** — not yet ported screen-by-screen;
  see the tracking issues below.

## Known gaps

Deliberately out of scope for the shell/token pass (issue #115) — tracked separately so this
document doesn't imply more is done than actually is:

- Screen-by-screen content reskin (Home shelves, Library grid/list, Book Detail, full
  Settings parity) — issue #115 (this same issue, remaining scope).
- Audiobook player redesign — issue [#116](https://github.com/dexxfm/dexxicon-reader/issues/116).
- EPUB reader display-settings sheet + two-column tablet/fold layout — issue [#117](https://github.com/dexxfm/dexxicon-reader/issues/117).
- Android Auto screens — issue [#118](https://github.com/dexxfm/dexxicon-reader/issues/118).
- CarPlay (new scene, doesn't exist today) — issue [#119](https://github.com/dexxfm/dexxicon-reader/issues/119).
- Real backdrop blur on the floating nav/mini-player — approximated with a translucent tonal
  surface instead (see `PillNav.kt`'s doc comment for why).
