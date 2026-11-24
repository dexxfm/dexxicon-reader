package net.dexxicon.reader.feature.reader.epub

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertThat
import net.dexxicon.reader.core.designsystem.theme.rememberReaderFullscreen
import net.dexxicon.reader.shared.reader.comic.ComicReaderScreen
import net.dexxicon.reader.shared.reader.comic.ComicReaderUiState
import net.dexxicon.reader.shared.reader.epub.EpubReaderScreen
import net.dexxicon.reader.shared.reader.epub.EpubReaderUiState
import net.dexxicon.reader.shared.reader.pdf.PdfReaderScreen
import net.dexxicon.reader.shared.reader.pdf.PdfReaderUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h800dp")
class ReaderFullscreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `fullscreen button toggles in each reader and remains accessible on a narrow screen`() {
        var reader by mutableIntStateOf(0)
        var fullscreen by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val toggle = { fullscreen = !fullscreen }
                when (reader) {
                    0 -> EpubReaderScreen(
                        state = EpubReaderUiState.Ready("Book", emptyList()),
                        onBack = {},
                        fullscreen = fullscreen,
                        onToggleFullscreen = toggle,
                    )
                    1 -> PdfReaderScreen(
                        state = PdfReaderUiState.Ready("Book", emptyList(), 1),
                        onBack = {},
                        fullscreen = fullscreen,
                        onToggleFullscreen = toggle,
                    )
                    else -> ComicReaderScreen(
                        state = ComicReaderUiState.Ready("Book", 1),
                        onBack = {},
                        fullscreen = fullscreen,
                        onToggleFullscreen = toggle,
                    )
                }
            }
        }

        repeat(3) { index ->
            compose.runOnIdle { reader = index }
            compose.onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Enter fullscreen").assertIsDisplayed()
        }
        compose.runOnIdle { reader = 0 }
        compose.onNodeWithContentDescription("Display settings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun `fullscreen button is omitted when platform does not provide it`() {
        compose.setContent {
            MaterialTheme {
                EpubReaderScreen(EpubReaderUiState.Ready("Book", emptyList()), onBack = {})
            }
        }
        compose.onNodeWithContentDescription("Enter fullscreen").assertDoesNotExist()
    }

    @Test
    fun `reader viewport stays outside cutouts with and without chrome`() {
        var reader by mutableIntStateOf(0)
        var chromeVisible by mutableStateOf(false)
        var fullscreen by mutableStateOf(false)
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        Box(Modifier.fillMaxSize().testTag("viewport"))
                    }
                    when (reader) {
                        0 -> EpubReaderScreen(
                            state = EpubReaderUiState.Ready("Book", emptyList()),
                            onBack = {},
                            fullscreen = fullscreen,
                            chromeVisible = chromeVisible,
                            readerContent = content,
                        )
                        1 -> PdfReaderScreen(
                            state = PdfReaderUiState.Ready("Book", emptyList(), 2),
                            onBack = {},
                            fullscreen = fullscreen,
                            readerContent = content,
                        )
                        else -> ComicReaderScreen(
                            state = ComicReaderUiState.Ready("Book", 2),
                            onBack = {},
                            fullscreen = fullscreen,
                            chromeVisible = chromeVisible,
                            readerContent = content,
                        )
                    }
                }
            }
        }

        val cutout = Insets.of(20, 50, 30, 40)
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                composeView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), cutout)
                    .build(),
            )
        }
        repeat(3) { index ->
            for (full in listOf(false, true)) {
                for (visible in listOf(false, true)) {
                    compose.runOnIdle {
                        reader = index
                        fullscreen = full
                        chromeVisible = visible
                    }
                    val root = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
                    val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
                    assertThat(viewport.left).isWithin(0.5f).of(root.left + cutout.left)
                    assertThat(viewport.top).isAtLeast(root.top + cutout.top)
                    assertThat(viewport.right).isWithin(0.5f).of(root.right - cutout.right)
                    assertThat(viewport.bottom).isAtMost(root.bottom - cutout.bottom)
                    if (index != 1 && !visible) {
                        assertThat(viewport.top).isWithin(0.5f).of(root.top + cutout.top)
                        assertThat(viewport.bottom).isWithin(0.5f).of(root.bottom - cutout.bottom)
                    }
                }
            }
        }
        compose.runOnIdle {
            reader = 0
            chromeVisible = false
            fullscreen = true
            ViewCompat.dispatchApplyWindowInsets(composeView, WindowInsetsCompat.Builder().build())
        }
        val root = compose.onNodeWithTag("root").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("viewport").fetchSemanticsNode().boundsInRoot
        assertThat(viewport).isEqualTo(root)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `cutout area follows epub page background changes`() {
        var background by mutableStateOf(Color(0xFF123456))
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    EpubReaderScreen(
                        state = EpubReaderUiState.Ready("Book", emptyList()),
                        onBack = {},
                        chromeVisible = false,
                        fullscreen = true,
                        pageBackground = background,
                    )
                }
            }
        }
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                composeView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(20, 50, 0, 0))
                    .build(),
            )
        }

        assertThat(compose.onNodeWithTag("root").captureToImage().toPixelMap()[5, 5])
            .isEqualTo(Color(0xFF123456))
        compose.runOnIdle { background = Color(0xFF654321) }
        assertThat(compose.onNodeWithTag("root").captureToImage().toPixelMap()[5, 5])
            .isEqualTo(Color(0xFF654321))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `comic cutout area matches native page background override`() {
        lateinit var composeView: View
        compose.setContent {
            composeView = LocalView.current
            MaterialTheme {
                Box(Modifier.fillMaxSize().testTag("root")) {
                    ComicReaderScreen(
                        state = ComicReaderUiState.Ready("Book", 1),
                        onBack = {},
                        chromeVisible = false,
                        fullscreen = true,
                        pageBackground = Color.Black,
                        readerContent = { Box(Modifier.fillMaxSize().background(Color.Black)) },
                    )
                }
            }
        }
        compose.runOnIdle {
            ViewCompat.dispatchApplyWindowInsets(
                composeView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(20, 50, 0, 0))
                    .build(),
            )
        }

        val pixels = compose.onNodeWithTag("root").captureToImage().toPixelMap()
        assertThat(pixels[5, 5]).isEqualTo(Color.Black)
        assertThat(pixels[5, 5]).isEqualTo(pixels[30, 60])
    }

    @Suppress("DEPRECATION")
    @Config(sdk = [29])
    @Test
    fun `system bars are restored after toggling and disposing the reader`() {
        var mounted by mutableStateOf(true)
        var fullscreen by mutableStateOf(false)
        lateinit var decor: View
        compose.setContent {
            decor = LocalView.current.rootView
            if (mounted) {
                val state = rememberReaderFullscreen()
                state.value = fullscreen
            }
        }

        val bars = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        compose.runOnIdle { assertThat(decor.systemUiVisibility and bars).isEqualTo(0) }
        compose.runOnIdle { fullscreen = true }
        compose.runOnIdle { assertThat(decor.systemUiVisibility and bars).isEqualTo(bars) }
        compose.runOnIdle { fullscreen = false }
        compose.runOnIdle { assertThat(decor.systemUiVisibility and bars).isEqualTo(0) }
        compose.runOnIdle { fullscreen = true }
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { assertThat(decor.systemUiVisibility and bars).isEqualTo(0) }
    }
}
