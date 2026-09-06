package net.dexxicon.reader.feature.reader.epub

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
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streams an EPUB straight from the server (Range requests via the shared authenticated
 * OkHttp client) and opens it as a Readium [Publication] — no full download.
 */
@Singleton
class EpubPublicationOpener @Inject constructor(
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

    suspend fun open(url: String): Outcome<Publication> = withContext(io) {
        val absolute = AbsoluteUrl(url)
            ?: return@withContext Outcome.Failure(DexxiconError.Parse("This book has an invalid address"))

        val asset = assetRetriever.retrieve(absolute, MediaType.EPUB).getOrNull()
            ?: return@withContext Outcome.Failure(
                DexxiconError.Network("Couldn't open the book file on the server"),
            )

        publicationOpener.open(asset, allowUserInteraction = false).fold(
            onSuccess = { Outcome.Success(it) },
            onFailure = { Outcome.Failure(DexxiconError.Parse(it.message)) },
        )
    }
}
