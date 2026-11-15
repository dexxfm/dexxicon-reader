package net.dexxicon.reader.core.designsystem.nav

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// iOS's own navigationBarsPadding() already reports the home indicator's fixed 34pt safe
// area — 10pt larger than Android's ~24dp gesture-nav inset — so a smaller extra margin
// here brings the two platforms' total bottom clearance to the same ~38 logical units
// (see PillExtraBottomMargin's doc comment in the commonMain expect declaration).
// issue #322 — 4pt, lowered 2 to match Android's 14dp.
// issue #336 — until then the app's SwiftUI host also inset the Compose view by the safe
// area, so on a real iPhone the pill actually sat a further 34pt higher than this intends.
internal actual val PillExtraBottomMargin: Dp = 4.dp
