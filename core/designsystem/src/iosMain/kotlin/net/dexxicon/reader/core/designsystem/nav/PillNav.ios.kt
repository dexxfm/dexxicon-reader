package net.dexxicon.reader.core.designsystem.nav

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// iOS's own navigationBarsPadding() already reports the home indicator's fixed 34pt safe
// area — 10pt larger than Android's ~24dp gesture-nav inset — so a smaller extra margin
// here brings the two platforms' total bottom clearance to the same ~40 logical units
// (see PillExtraBottomMargin's doc comment in the commonMain expect declaration).
internal actual val PillExtraBottomMargin: Dp = 6.dp
