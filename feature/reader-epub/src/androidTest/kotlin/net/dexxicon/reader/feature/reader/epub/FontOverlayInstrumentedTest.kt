package net.dexxicon.reader.feature.reader.epub

import android.content.Intent
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.CompositeContainer
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.resource.Resource
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class FontOverlayTestActivity : FragmentActivity()

@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class FontOverlayInstrumentedTest {
    @Test
    fun downloadedFontRendersInLocalAndStreamedEpubs() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fontBytes = File("/system/fonts/Roboto-Regular.ttf").readBytes()
        val epubBytes = fixtureEpub()
        val directory = File(context.cacheDir, "font-overlay-test-${System.nanoTime()}")
        check(directory.mkdir())
        val fontFile = File(directory, "font.ttf")
        val epubFile = File(directory, "book.epub")
        val server = MockWebServer()
        try {
            server.dispatcher = FixtureDispatcher(fontBytes, epubBytes)
            server.start()
            withContext(Dispatchers.IO) {
                val connection = URL(server.url("/api/v1/server-fonts/1/file").toString())
                    .openConnection() as HttpURLConnection
                try {
                    connection.setRequestProperty("Authorization", "Bearer prototype-token")
                    check(connection.responseCode == 200)
                    connection.inputStream.use { input -> fontFile.outputStream().use { input.copyTo(it) } }
                } finally {
                    connection.disconnect()
                }
                epubFile.writeBytes(epubBytes)
            }
            assertArrayEquals(fontBytes, fontFile.readBytes())

            renderAndCheck(openPublication(context, fontFile, epubFile, null), fontFile)
            renderAndCheck(
                openPublication(context, fontFile, null, server.url("/book.epub").toString()),
                fontFile,
            )
            val dispatcher = server.dispatcher as FixtureDispatcher
            assertTrue("The remote EPUB must be read with HTTP ranges", dispatcher.rangeRequests.get() > 0)
            assertEquals(1, dispatcher.fontRequests.get())
            assertArrayEquals(epubBytes, epubFile.readBytes())
        } finally {
            server.shutdown()
            fontFile.delete()
            epubFile.delete()
            directory.delete()
        }
    }

    private suspend fun openPublication(
        context: android.content.Context,
        fontFile: File,
        epubFile: File?,
        epubUrl: String?,
    ): Publication = withContext(Dispatchers.IO) {
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val asset = if (epubFile != null) {
            retriever.retrieve(epubFile, MediaType.EPUB).getOrNull()
        } else {
            retriever.retrieve(checkNotNull(AbsoluteUrl(checkNotNull(epubUrl))), MediaType.EPUB).getOrNull()
        }
        val opener = PublicationOpener(
            publicationParser = DefaultPublicationParser(
                context = context,
                httpClient = http,
                assetRetriever = retriever,
                pdfFactory = PdfiumDocumentFactory(context),
            ),
        )
        var overlayAdded = false
        val opened = opener.open(checkNotNull(asset), allowUserInteraction = false, onCreatePublication = {
            if (overlayAdded) return@open
            val href = checkNotNull(Url(FONT_PATH))
            check(href !in container.entries && manifest.linkWithHref(href) == null) {
                "Font path already present: container=${href in container.entries}, manifest=${manifest.linkWithHref(href)}"
            }
            val fontContainer = object : Container<Resource> {
                override val entries = setOf(href)
                override fun get(url: Url): Resource? = if (url == href) FileResource(fontFile) else null
                override fun close() = Unit
            }
            container = CompositeContainer(fontContainer, container)
            manifest = manifest.copy(resources = manifest.resources + Link(href = href, mediaType = MediaType("font/ttf")))
            overlayAdded = true
        }).getOrNull()
        checkNotNull(opened) { "Could not open EPUB" }
    }

    private suspend fun renderAndCheck(publication: Publication, fontFile: File) {
        try {
            val resource = checkNotNull(publication.get(publication.readingOrder.single()))
            try {
                assertArrayEquals(CHAPTER.toByteArray(Charsets.UTF_8), resource.read().getOrNull())
            } finally {
                resource.close()
            }
            val context = InstrumentationRegistry.getInstrumentation().context
            val intent = Intent(context, FontOverlayTestActivity::class.java)
            val scenario = ActivityScenario.launch<FontOverlayTestActivity>(intent)
            try {
                var navigator: EpubNavigatorFragment? = null
                scenario.onActivity { activity ->
                    val frame = FrameLayout(activity).apply { id = android.view.View.generateViewId() }
                    activity.setContentView(frame)
                    val configuration = EpubNavigatorFragment.Configuration().apply {
                        addFontFamilyDeclaration(FontFamily(FONT_FAMILY)) {
                            addFontFace {
                                addSource(checkNotNull(AbsoluteUrl("https://readium_package/$FONT_PATH")))
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.NORMAL)
                            }
                        }
                    }
                    val factory = EpubNavigatorFactory(publication).createFragmentFactory(
                        initialLocator = null,
                        initialPreferences = EpubPreferences(fontFamily = FontFamily(FONT_FAMILY)),
                        configuration = configuration,
                    )
                    activity.supportFragmentManager.fragmentFactory = factory
                    activity.supportFragmentManager.beginTransaction()
                        .add(frame.id, EpubNavigatorFragment::class.java, null, "navigator")
                        .commitNow()
                    navigator = activity.supportFragmentManager.findFragmentByTag("navigator") as EpubNavigatorFragment
                }
                assertNotNull(navigator)
                assertTrue(fontFile.isFile)
                withTimeout(30_000) {
                    while (true) {
                        val result = withContext(Dispatchers.Main) {
                            navigator?.evaluateJavascript(
                                """(function() {
                                    const face = Array.from(document.fonts).find(f => f.family === "$FONT_FAMILY");
                                    return !!face && face.status === "loaded" &&
                                        getComputedStyle(document.body).fontFamily.includes("$FONT_FAMILY");
                                })()""".trimIndent(),
                            )
                        }
                        if (result == "true") break
                        delay(200)
                    }
                }
            } finally {
                scenario.close()
            }
        } finally {
            publication.close()
        }
    }

    private class FixtureDispatcher(
        private val font: ByteArray,
        private val epub: ByteArray,
    ) : Dispatcher() {
        val fontRequests = AtomicInteger()
        val rangeRequests = AtomicInteger()

        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/api/v1/server-fonts/1/file") {
                if (request.getHeader("Authorization") != "Bearer prototype-token") {
                    return MockResponse().setResponseCode(401)
                }
                fontRequests.incrementAndGet()
                return MockResponse().setHeader("Content-Type", "font/ttf").setBody(Buffer().write(font))
            }
            if (request.path != "/book.epub") return MockResponse().setResponseCode(404)
            val range = request.getHeader("Range")
            if (range == null) {
                val response = MockResponse().setHeader("Content-Type", "application/epub+zip")
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Length", epub.size)
                return if (request.method == "HEAD") response else response.setBody(Buffer().write(epub))
            }
            val match = checkNotNull(Regex("bytes=(\\d+)-(\\d*)").matchEntire(range)) { "Unexpected range $range" }
            val start = match.groupValues[1].toInt()
            val end = match.groupValues[2].toIntOrNull()?.coerceAtMost(epub.lastIndex) ?: epub.lastIndex
            rangeRequests.incrementAndGet()
            return MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/epub+zip")
                .setHeader("Accept-Ranges", "bytes")
                .setHeader("Content-Range", "bytes $start-$end/${epub.size}")
                .setBody(Buffer().write(epub, start, end - start + 1))
        }
    }

    private fun fixtureEpub(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun add(path: String, bytes: ByteArray, stored: Boolean = false) {
                val entry = ZipEntry(path)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
            fun add(path: String, text: String) = add(path, text.toByteArray(Charsets.UTF_8))
            add("mimetype", "application/epub+zip".toByteArray(), stored = true)
            add("META-INF/container.xml", """<?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent())
            add("OEBPS/content.opf", """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="id">overlay-test</dc:identifier><dc:title>Overlay test</dc:title>
                    <dc:language>en</dc:language>
                  </metadata>
                  <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
                  <spine><itemref idref="chapter"/></spine>
                </package>""".trimIndent())
            add("OEBPS/chapter.xhtml", CHAPTER)
            val padding = ByteArray(6 * 1024 * 1024).also { Random(1).nextBytes(it) }
            add("padding.bin", padding)
        }
        return output.toByteArray()
    }

    private companion object {
        const val FONT_PATH = "dexxicon-prototype/font.ttf"
        const val FONT_FAMILY = "DexxiconPrototypeFont"
        val CHAPTER = """<html xmlns="http://www.w3.org/1999/xhtml">
            <head><title>Test</title></head><body><p>Downloaded font sample text.</p></body></html>""".trimIndent()
    }
}
