package net.dexxicon.reader.feature.reader.epub

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import net.dexxicon.reader.core.model.Server
import net.dexxicon.reader.core.model.ServerType
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer
import net.dexxicon.reader.shared.settings.SettingsScreen
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FontSettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<FontOverlayTestActivity>()

    @Test
    fun downloadDisableAndRemoveFamily() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/v1/server-fonts" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""[{"id":1,"familyName":"Test Family","format":"ttf","weight":400,"style":"normal","fileSize":4}]""")
                "/api/v1/fonts" -> MockResponse().setHeader("Content-Type", "application/json").setBody("[]")
                "/api/v1/server-fonts/1/file" -> MockResponse().setBody("font")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val container = createAppContainer(PlatformContext(compose.activity.applicationContext))
        try {
            runBlocking {
                container.serverRepository.save(
                    Server("font-test", "Font server", server.url("/").toString(), ServerType.BOOKORBIT),
                    password = null,
                )
            }
            compose.setContent {
                SettingsScreen(
                    container = container,
                    onManageServers = {},
                    onOpenAudiobookDefaults = {},
                    onOpenBookDefaults = {},
                    onOpenHomeLayout = {},
                )
            }
            compose.waitUntil(60_000) {
                compose.onAllNodesWithText("Find server fonts").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Find server fonts").performScrollTo().performClick()
            compose.waitUntil(60_000) {
                compose.onAllNodesWithText("Download").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Download").performScrollTo().performClick()
            compose.waitUntil(60_000) { container.fontLibrary.families.value.size == 1 }
            compose.onNodeWithContentDescription("Enable Test Family").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(60_000) { container.fontLibrary.families.value.singleOrNull()?.enabled == false }
            compose.onNodeWithText("Remove").performScrollTo().performClick()
            compose.waitUntil(60_000) { container.fontLibrary.families.value.isEmpty() }
        } finally {
            server.shutdown()
        }
    }
}
