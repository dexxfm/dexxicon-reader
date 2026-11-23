package net.dexxicon.reader.feature.reader.epub

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.shared.reader.ColorSelectionDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * issue #261 — RobinD42's colour wheel (from PR #359), tested on the dialog itself now that the
 * custom themes open it from the theme list.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w800dp-h800dp")
class ColorSelectionDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `wheel brightness and hex field stay synchronized when applying a color`() {
        var savedColor: Int? = null
        compose.setContent {
            MaterialTheme {
                ColorSelectionDialog(
                    title = "background",
                    color = 0xFFFFFFFF.toInt(),
                    onDismiss = {},
                    onSelect = { savedColor = it },
                )
            }
        }

        compose.onNodeWithText("Choose background color").assertExists()
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
                ColorSelectionDialog(
                    title = "text",
                    color = 0xFFFFFFFF.toInt(),
                    onDismiss = {},
                    onSelect = {},
                )
            }
        }

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
