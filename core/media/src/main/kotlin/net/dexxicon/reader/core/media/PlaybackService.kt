package net.dexxicon.reader.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.google.android.gms.cast.framework.CastContext
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
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
import kotlinx.coroutines.withContext
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
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
    private var castPlayer: CastPlayer? = null
    private var positionWriter: ServicePositionWriter? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()

        // https streams go through the authed client; a downloaded book plays from its
        // `file://` copy — DefaultDataSource routes each to the right source.
        val httpDataSource = OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSource)
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

        // Now-playing / lock-screen / notification art is loaded here through the authed
        // client and handed to the session as a bitmap. (Browse-grid art is different: the
        // car fetches that itself, so those items get a `content://` [ArtworkProvider] URI.)
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                dataSourceFactory,
            ),
        )

        // Opening the app: notification tap, and the "sign in" button the car shows when a
        // server rejects us.
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            android.app.PendingIntent.getActivity(
                this,
                0,
                it,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        // An audiobook is one long track: put rewind-15 / forward-30 in the transport slots
        // where a music app would show skip-to-previous / -next.
        val seekButtons = listOf(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_15)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .setDisplayName("Rewind 15 seconds")
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .setDisplayName("Fast-forward 30 seconds")
                .build(),
        )

        mediaSession = MediaLibrarySession.Builder(
            this,
            player,
            AutoLibraryCallback(
                player,
                contentSource,
                serviceScope,
                openAppIntent,
                audioManager = getSystemService(AudioManager::class.java),
                browseArtworkUri = { src -> ArtworkProvider.uriFor(this, src) },
                loadArtwork = ::fetchArtworkBytes,
            ),
        )
            .setBitmapLoader(bitmapLoader)
            .setMediaButtonPreferences(seekButtons)
            .apply { openAppIntent?.let { setSessionActivity(it) } }
            .build()

        positionWriter = ServicePositionWriter(player, progressSink, serviceScope).apply { attach() }

        initCast()
    }

    /**
     * Hands playback to a [CastPlayer] whenever a Cast session is live, and back to the
     * local [ExoPlayer] when it ends. No-op if Google Play services / Cast is unavailable.
     */
    private fun initCast() {
        runCatching { CastContext.getSharedInstance(this, Runnable::run) }
            .getOrNull()
            ?.addOnSuccessListener { castContext ->
                val cast = CastPlayer(castContext, DefaultMediaItemConverter())
                castPlayer = cast
                cast.setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = swapPlayer(toCast = true)
                    override fun onCastSessionUnavailable() = swapPlayer(toCast = false)
                })
                if (cast.isCastSessionAvailable) swapPlayer(toCast = true)
            }
    }

    private fun swapPlayer(toCast: Boolean) {
        val session = mediaSession ?: return
        val exo = exoPlayer ?: return
        val cast = castPlayer ?: return
        val from = session.player
        val to: Player = if (toCast) cast else exo
        if (from === to) return

        val mediaId = from.currentMediaItem?.mediaId
        val positionMs = from.currentPosition.coerceAtLeast(0L)
        val wasPlaying = from.playWhenReady
        from.pause()

        session.player = to
        positionWriter?.detach()
        positionWriter = ServicePositionWriter(to, progressSink, serviceScope).apply { attach() }

        if (mediaId == null) return
        serviceScope.launch {
            val playable = runCatching { contentSource.resolve(mediaId) }.getOrNull() ?: return@launch
            val uri = (if (toCast) playable.castUri else playable.uri) ?: playable.uri
            val item = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(mediaId)
                .apply { if (toCast) playable.mimeType?.let(::setMimeType) }
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(playable.title)
                        .setArtist(playable.author)
                        .setArtworkUri(playable.artworkUri?.let(Uri::parse))
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
            to.setMediaItem(item, positionMs)
            to.prepare()
            to.playWhenReady = wasPlaying
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    /**
     * Cover art for the now-playing item, downloaded through the authed client and embedded
     * as bytes in the [MediaMetadata]. The full-screen now-playing template loads
     * `artworkUri` itself via the session's BitmapLoader, but the collapsed side/rail
     * mini-player on a head unit doesn't — so without embedded bytes it shows a placeholder.
     * Downscaled to keep the parcel well under the Binder transaction limit. Never throws.
     */
    private suspend fun fetchArtworkBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                downscaleToJpeg(resp.body.bytes())
            }
        }.getOrNull()
    }

    private fun downscaleToJpeg(raw: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = if (longest > ARTWORK_MAX_PX) {
                Integer.highestOneBit(longest / ARTWORK_MAX_PX).coerceAtLeast(1)
            } else {
                1
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size, opts) ?: return raw
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

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
        castPlayer?.setSessionAvailabilityListener(null)
        mediaSession?.release()
        // A session doesn't release its player; release both (one may be the active one).
        exoPlayer?.release()
        castPlayer?.release()
        mediaSession = null
        exoPlayer = null
        castPlayer = null
        super.onDestroy()
    }

    private companion object {
        /** Longest edge of the embedded now-playing cover, in px. */
        const val ARTWORK_MAX_PX = 1024
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
    private val openAppIntent: android.app.PendingIntent?,
    private val audioManager: AudioManager?,
    /**
     * Wraps a remote cover URL as a `content://` URI the car can load without our auth
     * headers — used for browse-grid items (which the car fetches itself) and as the
     * now-playing fallback.
     */
    private val browseArtworkUri: (String) -> Uri,
    /** Fetches + downscales the now-playing cover to embed as [MediaMetadata] bytes. */
    private val loadArtwork: suspend (String) -> ByteArray?,
) : MediaLibrarySession.Callback {

    /** url -> embedded cover bytes (empty = fetch failed, don't retry this session). */
    private val artworkBytes = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private val skipSilence = SessionCommand(PlaybackCommands.SET_SKIP_SILENCE, Bundle.EMPTY)
    private val setAudioOutput = SessionCommand(PlaybackCommands.SET_AUDIO_OUTPUT, Bundle.EMPTY)

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
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
            .buildUpon()
            .add(skipSilence)
            .add(setAudioOutput)
            .build()
        // An audiobook is one long track: hide "skip to previous/next track" so the car and
        // the media notification surface rewind / fast-forward (15s / 30s) instead.
        val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            .buildUpon()
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .remove(Player.COMMAND_SEEK_TO_NEXT)
            .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
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
        if (customCommand.customAction == PlaybackCommands.SET_AUDIO_OUTPUT) {
            val id = args.getInt(PlaybackCommands.ARG_DEVICE_ID, -1)
            val device = id.takeIf { it >= 0 }?.let { wanted ->
                audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    ?.firstOrNull { it.id == wanted }
            }
            // id >= 0 but the device is gone (unpaired mid-selection) → keep default routing.
            player?.setPreferredAudioDevice(device)
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
            p?.let { playableItem(it) } ?: mediaItems.getOrNull(i)?.takeIf { it.localConfiguration != null }
        }
        val start = resolved.firstOrNull { it != null }?.startPositionMs ?: startPositionMs
        MediaSession.MediaItemsWithStartPosition(
            items,
            startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
            start,
        )
    }

    private suspend fun resolveOrPassThrough(item: MediaItem): MediaItem? =
        content.resolve(item.mediaId)?.let { playableItem(it) }
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
                .setArtworkUri(card.artworkUri?.let(browseArtworkUri))
                .setExtras(completionExtras(card.progress))
                .build(),
        )
        .build()

    private suspend fun playableItem(p: PlayableAudiobook): MediaItem {
        val art = p.artworkUri?.let { url -> artworkFor(url) }
        return MediaItem.Builder()
            .setMediaId(p.mediaId)
            .setUri(p.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setTitle(p.title)
                    .setArtist(p.author)
                    .apply {
                        if (art != null) setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        // Fallback for any surface that fetches the URI itself.
                        setArtworkUri(p.artworkUri?.let(browseArtworkUri))
                    }
                    .build(),
            )
            .build()
    }

    /** Cached, never-throwing cover-bytes lookup. */
    private suspend fun artworkFor(url: String): ByteArray? {
        artworkBytes[url]?.let { return it.takeIf(ByteArray::isNotEmpty) }
        val bytes = runCatching { loadArtwork(url) }.getOrNull() ?: ByteArray(0)
        artworkBytes[url] = bytes
        android.util.Log.i(TAG, "now-playing cover: ${bytes.size} bytes embedded from $url")
        return bytes.takeIf(ByteArray::isNotEmpty)
    }

    /** `LibraryParams` that make the car show a "sign in" button wired to the app. */
    private fun errorParams(label: String): LibraryParams = LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, label)
                openAppIntent?.let {
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
        const val TAG = "PlaybackService"
        const val ROOT_ID = "root"
        const val RECENT_ROOT_ID = "root_recent"
        const val CONTINUE_ID = "continue"
        const val DOWNLOADED_ID = "downloaded"
        const val LIBRARY_ID = "library"
        const val LIB_PREFIX = "lib:"
    }
}
