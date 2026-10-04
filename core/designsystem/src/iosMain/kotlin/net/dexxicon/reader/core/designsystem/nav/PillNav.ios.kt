package net.dexxicon.reader.core.designsystem.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// iOS's own navigationBarsPadding() already reports the home indicator's fixed 34pt safe
// area — 10pt larger than Android's ~24dp gesture-nav inset. issue #320: Android's gesture
// nav now adds no margin (the pill sits on the inset, ~10dp above the gesture handle, like
// YouTube), so iOS tucks the pill 10pt into its larger safe area to land at the same ~24pt
// from the bottom edge (see pillExtraBottomMargin's doc comment in the commonMain expect).
@Composable
internal actual fun pillExtraBottomMargin(): Dp = (-10).dp
