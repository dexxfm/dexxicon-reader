package net.dexxicon.reader.feature.reader.epub

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.datastore.ReaderDisplayPreferences
import net.dexxicon.reader.core.datastore.ReaderTheme
import net.dexxicon.reader.shared.reader.ReaderThemeList
import net.dexxicon.reader.shared.reader.ReaderThemeSetting
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * issue #261 — the theme row names the theme; the list (shown in a sheet in the app) sets one
 * and edits the custom colours. The list is tested without its sheet: Robolectric can't host it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp")
class ReaderThemePickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the row names the current theme`() {
        var prefs by mutableStateOf(ReaderDisplayPreferences())
        compose.setContent {
            MaterialTheme {
                ReaderThemeSetting(prefs, { transform -> prefs = transform(prefs) })
            }
        }

        compose.onNodeWithText("Theme").assertExists()
        compose.onNodeWithText("System").assertExists()
        compose.runOnIdle { prefs = prefs.copy(theme = ReaderTheme.NORD) }
        compose.onNodeWithText("Nord").assertExists()
    }

    @Test
    fun `picking a theme from the list sets it`() {
        var prefs by mutableStateOf(ReaderDisplayPreferences())
        var picked = false
        compose.setContent {
            MaterialTheme {
                ReaderThemeList(prefs, { transform -> prefs = transform(prefs) }, onPicked = { picked = true })
            }
        }

        compose.onNodeWithText("Midnight").performScrollTo().performClick()

        compose.runOnIdle {
            assertThat(prefs.theme).isEqualTo(ReaderTheme.MIDNIGHT)
            assertThat(picked).isTrue()
        }
    }

    @Test
    fun `a custom theme edits its colours from the list`() {
        var prefs by mutableStateOf(ReaderDisplayPreferences())
        compose.setContent {
            MaterialTheme {
                ReaderThemeList(prefs, { transform -> prefs = transform(prefs) }, onPicked = {})
            }
        }

        compose.onNodeWithContentDescription("Edit Custom 1 colours").performScrollTo().performClick()
        compose.onNodeWithText("Background colour").performClick()
        compose.onNodeWithText("Choose background color").assertExists()
    }

    @Test
    fun `painted palettes know a dark page from a light one`() {
        assertThat(ReaderDisplayPreferences(theme = ReaderTheme.MIDNIGHT).paintedColors?.isDark).isTrue()
        assertThat(ReaderDisplayPreferences(theme = ReaderTheme.SEPIA_DARK).paintedColors?.isDark).isFalse()
        assertThat(ReaderDisplayPreferences(theme = ReaderTheme.CUSTOM_2).paintedColors?.isDark).isTrue()
        assertThat(ReaderDisplayPreferences(theme = ReaderTheme.LIGHT).paintedColors).isNull()
        assertThat(ReaderDisplayPreferences(theme = ReaderTheme.CUSTOM_2).hasDarkPage(systemInDark = false)).isTrue()
    }
}
