package net.dexxicon.reader.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.dexxicon.reader.core.designsystem.theme.Pill

/** Phase 4 (issue #115) — the mockup's own back control: a surface-colored pill with an
 * arrow icon *and* the word "Back", used on any screen that skips the standard M3
 * `TopAppBar` in favor of the pill design language. Originally local to Book Detail
 * (issue #121); promoted here (Stage E1 follow-up) so other back-navigable screens can match
 * it instead of each rendering their own plain `TextButton` "‹ Back".
 */
@Composable
fun BackPill(onBack: () -> Unit) {
    Surface(
        onClick = onBack,
        shape = Pill,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.height(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Text(
                "BACK",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
        }
    }
}
