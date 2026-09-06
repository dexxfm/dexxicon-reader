package net.dexxicon.reader.core.reader

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.common.DexxiconDispatcher
import net.dexxicon.reader.core.common.DexxiconError
import net.dexxicon.reader.core.common.Dispatcher
import net.dexxicon.reader.core.common.Outcome
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.mediatype.MediaType
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
            pdfFactory = null,
        ),
    )

    suspend fun open(url: String, mediaType: MediaType): Outcome<Publication> = withContext(io) {
        val absolute = AbsoluteUrl(url)
            ?: return@withContext Outcome.Failure(DexxiconError.Parse("This book has an invalid address"))
        val asset = assetRetriever.retrieve(absolute, mediaType).getOrNull()
            ?: return@withContext Outcome.Failure(
                DexxiconError.Network("Couldn't open the book file on the server"),
            )
        openAsset(asset)
    }

    suspend fun open(file: File, mediaType: MediaType): Outcome<Publication> = withContext(io) {
        val asset = assetRetriever.retrieve(file, mediaType).getOrNull()
            ?: return@withContext Outcome.Failure(
                DexxiconError.Parse("Couldn't read the downloaded file"),
            )
        openAsset(asset)
    }

    private suspend fun openAsset(asset: Asset): Outcome<Publication> =
        publicationOpener.open(asset, allowUserInteraction = false).fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(DexxiconError.Parse(it.message)) },
        )
}
