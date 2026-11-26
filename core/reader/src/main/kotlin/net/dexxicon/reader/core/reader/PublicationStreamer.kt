package net.dexxicon.reader.core.reader

import org.readium.r2.shared.util.http.HttpContainer
import org.readium.r2.shared.publication.Metadata
import org.readium.r2.shared.publication.Manifest
import org.readium.r2.shared.publication.LocalizedString
import org.readium.r2.shared.publication.Link
import net.dexxicon.reader.core.model.PageStreamInfo
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.Outcome
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.PerResourcePositionsService
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a Readium [Publication] from a remote URL (streamed via the shared authenticated
 * OkHttp client — no full download) or from an already-downloaded local file. Shared by
 * every in-app reader (EPUB, comic, PDF…).
 */
@Singleton
class PublicationStreamer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: HttpClient,
    @Dispatcher(DexxiconDispatcher.IO) private val io: CoroutineDispatcher,
) {
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)

    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = PdfiumDocumentFactory(context),
        ),
    )

    suspend fun open(
        url: String,
        mediaType: MediaType,
        onCreatePublication: Publication.Builder.() -> Unit = {},
    ): Outcome<Publication> = withContext(io) {
        val absolute = AbsoluteUrl(url)
            ?: return@withContext Outcome.Failure(DexxiconError.Parse("This book has an invalid address"))
        val asset = assetRetriever.retrieve(absolute, mediaType).getOrNull()
            ?: return@withContext Outcome.Failure(
                DexxiconError.Network("Couldn't open the book file on the server"),
            )
        openAsset(asset, onCreatePublication)
    }

    suspend fun open(
        file: File,
        mediaType: MediaType,
        onCreatePublication: Publication.Builder.() -> Unit = {},
    ): Outcome<Publication> = withContext(io) {
        val asset = assetRetriever.retrieve(file, mediaType).getOrNull()
            ?: return@withContext Outcome.Failure(
                DexxiconError.Parse("Couldn't read the downloaded file"),
            )
        openAsset(asset, onCreatePublication)
    }

    /**
     * issue #349 — a comic served page by page (a Kavita chapter made of several files): a
     * publication whose reading order is the page images themselves, each fetched over HTTP as
     * the reader reaches it.
     */
    fun openPages(stream: PageStreamInfo, title: String): Outcome<Publication> {
        val urls = (0 until stream.count).map { index ->
            AbsoluteUrl(stream.pageUrl(index))
                ?: return Outcome.Failure(DexxiconError.Parse("This comic has an invalid page address"))
        }
        val imageType = MediaType(stream.mediaType) ?: MediaType.JPEG
        val manifest = Manifest(
            metadata = Metadata(localizedTitle = LocalizedString(title)),
            readingOrder = urls.map { Link(href = it, mediaType = imageType) },
        )
        val container = HttpContainer(baseUrl = null, entries = urls.toSet(), client = httpClient)
        // One position per page, as the CBZ parser gives an archive: the reader's locators then
        // carry the page and the share read, which is what progress sync goes by.
        val services = Publication.ServicesBuilder(positions = PerResourcePositionsService.createFactory(imageType))
        return Outcome.Success(Publication(manifest, container, services))
    }

    private suspend fun openAsset(
        asset: Asset,
        onCreatePublication: Publication.Builder.() -> Unit,
    ): Outcome<Publication> =
        publicationOpener.open(asset, allowUserInteraction = false, onCreatePublication = onCreatePublication).fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(DexxiconError.Parse(it.message)) },
        )
}
