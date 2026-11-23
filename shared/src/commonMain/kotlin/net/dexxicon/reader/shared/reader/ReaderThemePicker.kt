package net.dexxicon.reader.shared.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderTheme
import net.dexxicon.reader.core.designsystem.theme.LocalDarkTheme

/**
 * issue #261 — the reader's theme as one compact row (a "Tn" preview in the theme's colours and
 * its name) that opens the full list, instead of a block of chips: the owner's call, so the rest
 * of the settings stay in view. Custom 1 and 2 edit their colours from the list.
 */
@Composable
fun ReaderThemeSetting(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val appDark = LocalDarkTheme.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = "Choose a theme") { open = true }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        ThemePreview(preferences.theme, preferences, appDark)
        Spacer(Modifier.width(12.dp))
        Text(preferences.theme.label(), style = MaterialTheme.typography.bodyLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
    if (open) {
        ReaderThemeSheet(
            preferences = preferences,
            onChange = onChange,
            onDismiss = { open = false },
        )
    }
}

/** The list of themes: preview on the left, name on the right; picking one closes it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderThemeSheet(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReaderThemeList(preferences, onChange, onPicked = onDismiss)
    }
}

/** The themes as rows, for [ReaderThemeSheet]; [onPicked] follows a choice. */
@Composable
fun ReaderThemeList(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
    onPicked: () -> Unit,
) {
    val appDark = LocalDarkTheme.current
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 16.dp)
            .navigationBarsPadding(),
    ) {
        Text(
            "Theme",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        ReaderTheme.entries.forEach { theme ->
            ThemeRow(
                theme = theme,
                preferences = preferences,
                appDark = appDark,
                selected = preferences.theme == theme,
                onSelect = {
                    onChange { it.copy(theme = theme) }
                    onPicked()
                },
                onChange = onChange,
            )
        }
    }
}

@Composable
private fun ThemeRow(
    theme: ReaderTheme,
    preferences: ReaderDisplayPreferences,
    appDark: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onSelect)
            .semantics { this.selected = selected }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ThemePreview(theme, preferences, appDark)
        Text(theme.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (theme == ReaderTheme.CUSTOM_1 || theme == ReaderTheme.CUSTOM_2) {
            CustomColorsButton(theme, preferences, onChange)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null)
    }
}

/** Custom 1/2: pick the page or the text colour (the colour wheel from #261). */
@Composable
private fun CustomColorsButton(
    theme: ReaderTheme,
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    val first = theme == ReaderTheme.CUSTOM_1
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.Palette, contentDescription = "Edit ${theme.label()} colours")
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Background colour") }, onClick = { menu = false; editing = BACKGROUND })
            DropdownMenuItem(text = { Text("Text colour") }, onClick = { menu = false; editing = TEXT })
        }
    }
    when (editing) {
        BACKGROUND -> ColorSelectionDialog(
            title = "background",
            color = if (first) preferences.custom1Background else preferences.custom2Background,
            onDismiss = { editing = null },
            onSelect = { color ->
                editing = null
                onChange { if (first) it.copy(custom1Background = color) else it.copy(custom2Background = color) }
            },
        )
        TEXT -> ColorSelectionDialog(
            title = "text",
            color = if (first) preferences.custom1Foreground else preferences.custom2Foreground,
            onDismiss = { editing = null },
            onSelect = { color ->
                editing = null
                onChange { if (first) it.copy(custom1Foreground = color) else it.copy(custom2Foreground = color) }
            },
        )
    }
}

/** A small page in the theme's colours with "Tn" in its text colour. */
@Composable
private fun ThemePreview(theme: ReaderTheme, preferences: ReaderDisplayPreferences, appDark: Boolean) {
    val (background, text) = theme.previewColors(preferences, appDark)
    Box(
        Modifier
            .size(width = 44.dp, height = 34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text("Tn", color = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
    }
}

/** The page and text colours a theme shows, for its preview. */
internal fun ReaderTheme.previewColors(preferences: ReaderDisplayPreferences, appDark: Boolean): Pair<Color, Color> {
    preferences.copy(theme = this).paintedColors?.let { return Color(it.background) to Color(it.text) }
    return when (this) {
        ReaderTheme.SYSTEM -> if (appDark) DARK_PREVIEW else LIGHT_PREVIEW
        ReaderTheme.LIGHT -> LIGHT_PREVIEW
        ReaderTheme.SEPIA -> Color(0xFFF5ECDC) to Color(0xFF5B4636)
        ReaderTheme.GREY -> Color(0xFFC8CBCF) to Color(0xFF17181A)
        else -> DARK_PREVIEW
    }
}

/** The name a theme goes by in every reader's settings and Book defaults. */
internal fun ReaderTheme.label(): String = when (this) {
    ReaderTheme.SYSTEM -> "System"
    ReaderTheme.LIGHT -> "White"
    ReaderTheme.SEPIA -> "Sepia"
    ReaderTheme.SEPIA_DARK -> "Sepia Dark"
    ReaderTheme.GREY -> "Grey"
    ReaderTheme.DARK -> "Black"
    ReaderTheme.MIDNIGHT -> "Midnight"
    ReaderTheme.NORD -> "Nord"
    ReaderTheme.CUSTOM_1 -> "Custom 1"
    ReaderTheme.CUSTOM_2 -> "Custom 2"
}

/**
 * issue #261 — back to the book's own typography: clears the font, spacing, indent,
 * justification and hyphenation overrides (the size stays). Once any of those is set, Readium
 * turns the publisher's styles off, and a switch can't express "unset" by itself.
 */
@Composable
internal fun ResetTextButton(
    preferences: ReaderDisplayPreferences,
    onChange: ((ReaderDisplayPreferences) -> ReaderDisplayPreferences) -> Unit,
) {
    val overridden = preferences.fontFamily != null || preferences.lineSpacing != null ||
        preferences.paragraphSpacing != null || preferences.paragraphIndent != null ||
        preferences.justification != null || preferences.hyphenation != null
    TextButton(
        onClick = {
            onChange {
                it.copy(
                    fontFamily = null,
                    lineSpacing = null,
                    paragraphSpacing = null,
                    paragraphIndent = null,
                    justification = null,
                    hyphenation = null,
                )
            }
        },
        enabled = overridden,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text("Use the book's own text styles")
    }
}

private val LIGHT_PREVIEW = Color(0xFFFFFFFF) to Color(0xFF121212)
private val DARK_PREVIEW = Color(0xFF000000) to Color(0xFFFEFEFE)
private const val BACKGROUND = "background"
private const val TEXT = "text"
