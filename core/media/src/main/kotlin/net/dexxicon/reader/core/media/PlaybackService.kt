package net.dexxicon.reader.core.media

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Background audiobook playback **and browsing**. A [MediaLibraryService] so Android Auto
 * (and any `MediaBrowser`) can browse the library; Media3 also drives the media
 * notification, lock-screen controls and audio focus. Streams and cover art are fetched
 * through the shared authenticated OkHttp client so range requests carry the bearer token.
 *
 * The browse tree and media-id resolution live in [AutoLibraryCallback], backed by
 * [MediaLibraryContentSource]. Audio options that aren't on the
 * [androidx.media3.common.Player] surface (skip silence) come in as a custom session command
 * from [AudiobookPlayer].
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var progressSink: PlaybackProgressSink

    @Inject
    lateinit var contentSource: MediaLibraryContentSource

    private var mediaSession: MediaLibrarySession? = null
    private var exoPlayer: ExoPlayer? = null
    private var positionWriter: ServicePositionWriter? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setSeekForwardIncrementMs(30_000)
            .setSeekBackIncrementMs(15_000)
            .build()
        exoPlayer = player

        // Route cover-art loading through the authed client too, so lock-screen/notification
        // artwork doesn't 401.
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                dataSourceFactory,
            ),
        )

        // Deep link for the "sign in" button the car shows when a server rejects us.
        val signInIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        mediaSession = MediaLibrarySession.Builder(
            this,
            player,
            AutoLibraryCallback(player, contentSource, serviceScope, signInIntent),
        )
            .setBitmapLoader(bitmapLoader)
            .build()

        positionWriter = ServicePositionWriter(player, progressSink, serviceScope).apply { attach() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        positionWriter?.detach()
        positionWriter = null
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        exoPlayer = null
        super.onDestroy()
    }
}

/**
 * The audiobook browse tree for Android Auto / Automotive, plus resolution of a browsed
 * `"serverId::bookId"` id into a playable stream (or a downloaded file) at the right
 * resume position.
 */
private class AutoLibraryCallback(
    private val player: ExoPlayer?,
    private val content: MediaLibraryContentSource,
    private val scope: CoroutineScope,
    private val signInIntent: android.app.PendingIntent?,
) : MediaLibrarySession.Callback {

    private val skipSilence = SessionCommand(PlaybackCommands.SET_SKIP_SILENCE, Bundle.EMPTY)

    /** Last search served, so `onGetSearchResult` doesn't re-query what `onSearch` fetched. */
    @Volatile
    private var cachedSearch: Pair<String, List<AudiobookCard>>? = null

    private val rootParams = LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
                )
                putInt(
                    MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                    MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
                )
            },
        )
        .build()

    private val browseRoot = folder(ROOT_ID, "Audiobooks")
    private val recentRoot = folder(RECENT_ROOT_ID, "Audiobooks")

    // ---- session plumbing ----

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(skipSilence)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(commands)
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        if (customCommand.customAction == PlaybackCommands.SET_SKIP_SILENCE) {
            player?.skipSilenceEnabled = args.getBoolean(PlaybackCommands.ARG_ENABLED, false)
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
    }

    // ---- browse tree ----

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        if (params?.isRecent == true) {
            Futures.immediateFuture(LibraryResult.ofItem(recentRoot, params))
        } else {
            Futures.immediateFuture(LibraryResult.ofItem(browseRoot, rootParams))
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        // The car's resumption row: the single last-played book.
        if (parentId == RECENT_ROOT_ID) {
            val item = content.lastPlayed()?.let(::bookItem)
            return@future LibraryResult.ofItemList(
                ImmutableList.copyOf(listOfNotNull(item)),
                params,
            )
        }

        // Nodes that need a server — surface a "sign in" resolution when the session is dead.
        if (parentId == LIBRARY_ID || parentId.startsWith(LIB_PREFIX)) {
            val serverId = when {
                parentId.startsWith(LIB_PREFIX) -> parentId.removePrefix(LIB_PREFIX)
                else -> {
                    val libs = content.libraries()
                    when {
                        libs.isEmpty() -> return@future LibraryResult.ofError(
                            LibraryResult.RESULT_ERROR_SESSION_SETUP_REQUIRED,
                            errorParams("Open app"),
                        )
                        libs.size > 1 -> return@future LibraryResult.ofItemList(
                            ImmutableList.copyOf(libs.map { folder(it.id, it.title) }),
                            params,
                        )
                        else -> libs.first().serverId()
                    }
                }
            }
            val pageResult = content.audiobooks(serverId, page, pageSize)
            return@future if (pageResult.authExpired) {
                LibraryResult.ofError(
                    LibraryResult.RESULT_ERROR_SESSION_AUTHENTICATION_EXPIRED,
                    errorParams("Sign in"),
                )
            } else {
                LibraryResult.ofItemList(
                    ImmutableList.copyOf(pageResult.items.map(::bookItem)),
                    params,
                )
            }
        }

        val items: List<MediaItem> = when (parentId) {
            ROOT_ID -> listOf(
                folder(CONTINUE_ID, "Continue listening"),
                folder(DOWNLOADED_ID, "Downloaded"),
                folder(LIBRARY_ID, "All audiobooks"),
            )
            CONTINUE_ID -> content.continueListening().map(::bookItem)
            DOWNLOADED_ID -> content.downloaded().map(::bookItem)
            else -> emptyList()
        }
        LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = future {
        when (mediaId) {
            ROOT_ID -> LibraryResult.ofItem(browseRoot, rootParams)
            RECENT_ROOT_ID -> LibraryResult.ofItem(recentRoot, null)
            CONTINUE_ID -> LibraryResult.ofItem(folder(CONTINUE_ID, "Continue listening"), null)
            DOWNLOADED_ID -> LibraryResult.ofItem(folder(DOWNLOADED_ID, "Downloaded"), null)
            LIBRARY_ID -> LibraryResult.ofItem(folder(LIBRARY_ID, "All audiobooks"), null)
            else -> content.resolve(mediaId)
                ?.let { LibraryResult.ofItem(playableItem(it), null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }
    }

    // ---- search ----

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = future {
        val results = content.search(query)
        cachedSearch = query to results
        session.notifySearchResultChanged(browser, query, results.size, params)
        LibraryResult.ofVoid()
    }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
        val cached = cachedSearch
        val results = if (cached?.first == query) cached.second else content.search(query)
        val from = page * pageSize
        val window = results.drop(from).take(pageSize)
        LibraryResult.ofItemList(ImmutableList.copyOf(window.map(::bookItem)), params)
    }

    // ---- playback resumption (car resume, BT play button with nothing loaded) ----

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val playable = content.lastPlayed()?.mediaId?.let { content.resolve(it) }
            ?: throw IllegalStateException("nothing to resume")
        MediaSession.MediaItemsWithStartPosition(
            listOf(playableItem(playable)),
            0,
            playable.startPositionMs,
        )
    }

    // ---- media-id -> playable ----

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = future {
        mediaItems.mapNotNull { resolveOrPassThrough(it) }.toMutableList()
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        val resolved = mediaItems.map { content.resolve(it.mediaId) }
        val items = resolved.mapIndexedNotNull { i, p ->
            p?.let(::playableItem) ?: mediaItems.getOrNull(i)?.takeIf { it.localConfiguration != null }
        }
        val start = resolved.firstOrNull { it != null }?.startPositionMs ?: startPositionMs
        MediaSession.MediaItemsWithStartPosition(
            items,
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            start,
        )
    }

    private suspend fun resolveOrPassThrough(item: MediaItem): MediaItem? =
        content.resolve(item.mediaId)?.let(::playableItem)
            ?: item.takeIf { it.localConfiguration != null }

    // ---- builders ----

    private fun folder(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setTitle(title)
                .build(),
        )
        .build()

    private fun bookItem(card: AudiobookCard): MediaItem = MediaItem.Builder()
        .setMediaId(card.mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setTitle(card.title)
                .setArtist(card.author)
                .setArtworkUri(card.artworkUri?.let(Uri::parse))
                .setExtras(completionExtras(card.progress))
                .build(),
        )
        .build()

    private fun playableItem(p: PlayableAudiobook): MediaItem = MediaItem.Builder()
        .setMediaId(p.mediaId)
        .setUri(p.uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .setTitle(p.title)
                .setArtist(p.author)
                .setArtworkUri(p.artworkUri?.let(Uri::parse))
                .build(),
        )
        .build()

    /** `LibraryParams` that make the car show a "sign in" button wired to the app. */
    private fun errorParams(label: String): LibraryParams = LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, label)
                signInIntent?.let {
                    putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, it)
                }
            },
        )
        .build()

    private fun completionExtras(progress: Double?): Bundle = Bundle().apply {
        if (progress == null) return@apply
        val status = when {
            progress >= 0.985 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
            progress > 0.0 -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
            else -> MediaConstants.EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
        }
        putInt(MediaConstants.EXTRAS_KEY_COMPLETION_STATUS, status)
        putDouble(MediaConstants.EXTRAS_KEY_COMPLETION_PERCENTAGE, progress.coerceIn(0.0, 1.0))
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            runCatching { block() }
                .onSuccess { f.set(it) }
                .onFailure { f.setException(it) }
        }
        return f
    }

    private fun LibraryNode.serverId(): String = id.removePrefix(LIB_PREFIX)

    private companion object {
        const val ROOT_ID = "root"
        const val RECENT_ROOT_ID = "root_recent"
        const val CONTINUE_ID = "continue"
        const val DOWNLOADED_ID = "downloaded"
        const val LIBRARY_ID = "library"
        const val LIB_PREFIX = "lib:"
    }
}
