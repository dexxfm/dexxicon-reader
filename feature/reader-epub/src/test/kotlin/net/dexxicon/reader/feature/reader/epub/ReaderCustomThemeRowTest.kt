package net.dexxicon.reader.feature.reader.epub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.shared.reader.ReaderCustomThemeRow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp")
class ReaderCustomThemeRowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `labels adapt to the measured width and preview follows the buttons`() {
        var width by mutableStateOf(700.dp)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(width)) {
                    ReaderCustomThemeRow(
                        title = "Custom 1",
                        selected = false,
                        background = 0xFFFFFFFF.toInt(),
                        foreground = 0xFF000000.toInt(),
                        onSelect = {},
                        onBackgroundChange = {},
                        onForegroundChange = {},
                    )
                }
            }
        }

        compose.onNodeWithText("Background").assertIsDisplayed()
        compose.onNodeWithText("Foreground").assertIsDisplayed()
        compose.onNodeWithText("BG").assertDoesNotExist()
        val chip = compose.onNodeWithText("Custom 1").fetchSemanticsNode().boundsInRoot
        val background = compose.onNodeWithText("Background").fetchSemanticsNode().boundsInRoot
        val foreground = compose.onNodeWithText("Foreground").fetchSemanticsNode().boundsInRoot
        val preview = compose.onNodeWithText("Aa Bb 123").fetchSemanticsNode().boundsInRoot
        assertThat(chip.right).isLessThan(background.left)
        assertThat(background.right).isLessThan(foreground.left)
        assertThat(foreground.right).isLessThan(preview.left)

        val fullWidth = with(compose.density) { preview.right.toDp() + 8.dp }
        compose.runOnIdle { width = fullWidth + 1.dp }
        compose.onNodeWithText("Background").assertIsDisplayed()
        compose.onNodeWithText("Foreground").assertIsDisplayed()
        compose.runOnIdle { width = fullWidth - 1.dp }
        compose.onNodeWithText("BG").assertIsDisplayed()
        compose.onNodeWithText("FG").assertIsDisplayed()
        compose.onNodeWithText("Background").assertIsNotDisplayed()
        compose.onNodeWithText("Foreground").assertIsNotDisplayed()
        val compactBackground = compose.onNodeWithText("BG").fetchSemanticsNode().boundsInRoot
        val compactForeground = compose.onNodeWithText("FG").fetchSemanticsNode().boundsInRoot
        val compactPreviews = compose.onAllNodesWithText("Aa Bb 123")
        val visiblePreview = compactPreviews.fetchSemanticsNodes().indices.single { compactPreviews[it].isDisplayed() }
        val compactPreview = compactPreviews[visiblePreview].fetchSemanticsNode().boundsInRoot
        assertThat(compactBackground.right).isLessThan(compactForeground.left)
        assertThat(compactForeground.right).isLessThan(compactPreview.left)
        assertThat(compactPreview.center.y).isWithin(1f).of(compactForeground.center.y)

        compose.runOnIdle { width = 160.dp }
        compose.onNodeWithText("BG").assertIsDisplayed()
        compose.onNodeWithText("FG").assertIsDisplayed()
        compose.onNodeWithText("Background").assertIsNotDisplayed()
        compose.onNodeWithText("Foreground").assertIsNotDisplayed()
        val previews = compose.onAllNodesWithText("Aa Bb 123")
        assertThat(previews.fetchSemanticsNodes().indices.count { previews[it].isDisplayed() }).isEqualTo(1)

        compose.onNodeWithContentDescription("Custom 1 background").performClick()
        compose.onNodeWithText("Choose background color").assertExists()
    }

    @Test
    fun `compact foreground button opens the foreground editor`() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(160.dp)) {
                    ReaderCustomThemeRow(
                        title = "Custom 2",
                        selected = true,
                        background = 0xFF000000.toInt(),
                        foreground = 0xFFFFFFFF.toInt(),
                        onSelect = {},
                        onBackgroundChange = {},
                        onForegroundChange = {},
                    )
                }
            }
        }

        compose.onNodeWithContentDescription("Custom 2 foreground").performClick()
        compose.onNodeWithText("Choose foreground color").assertExists()
    }

    @Test
    fun `wheel brightness and hex field stay synchronized when applying a color`() {
        var savedColor: Int? = null
        compose.setContent {
            MaterialTheme {
                ReaderCustomThemeRow(
                    title = "Custom 1",
                    selected = true,
                    background = 0xFFFFFFFF.toInt(),
                    foreground = 0xFF000000.toInt(),
                    onSelect = {},
                    onBackgroundChange = { savedColor = it },
                    onForegroundChange = {},
                )
            }
        }

        compose.onNodeWithText("Background").performClick()
        val hex = compose.onNode(hasSetTextAction())
        compose.onNodeWithContentDescription("Color wheel").performTouchInput {
            click(Offset(width - 0.1f, centerY))
        }
        hex.assertTextContains("FF0000")
        compose.onNodeWithContentDescription("Color wheel").performTouchInput {
            down(center)
            moveTo(centerLeft)
            moveTo(Offset(width - 0.1f, centerY))
            up()
        }
        hex.assertTextContains("FF0000")
        val brightness = compose.onNodeWithContentDescription("Brightness")
        brightness.performScrollTo()
        brightness.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        hex.assertTextContains("800000")

        hex.performTextReplacement("00FF00")
        brightness.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        hex.assertTextContains("008000")
        compose.onNodeWithText("Apply").performClick()
        assertThat(savedColor).isEqualTo(0xFF008000.toInt())
    }

    @Test
    fun `brightness can restore color after being set to black`() {
        compose.setContent {
            MaterialTheme {
                ReaderCustomThemeRow(
                    title = "Custom 2",
                    selected = false,
                    background = 0xFF000000.toInt(),
                    foreground = 0xFFFFFFFF.toInt(),
                    onSelect = {},
                    onBackgroundChange = {},
                    onForegroundChange = {},
                )
            }
        }

        compose.onNodeWithText("Foreground").performClick()
        val hex = compose.onNode(hasSetTextAction())
        hex.performTextReplacement("0040FF")
        val brightness = compose.onNodeWithContentDescription("Brightness")
        brightness.performScrollTo()
        brightness.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        hex.assertTextContains("000000")
        brightness.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        hex.assertTextContains("0040FF")

        compose.onNodeWithContentDescription("Select color 666666").performScrollTo().performClick()
        hex.assertTextContains("666666")
        brightness.performScrollTo()
        brightness.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        hex.assertTextContains("FFFFFF")
    }
}
