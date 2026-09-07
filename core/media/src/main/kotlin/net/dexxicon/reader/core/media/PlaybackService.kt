package net.dexxicon.reader.core.media

import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Background audiobook playback **and browsing**. A [MediaLibraryService] so Android Auto
 * (and any `MediaBrowser`) can connect and browse the library; Media3 also drives the media
 * notification, lock-screen controls and audio focus. Streams and cover art are fetched
 * through the shared authenticated OkHttp client so range requests carry the bearer token.
 *
 * The browse tree and media-id resolution live in [AutoLibraryCallback]. Audio options that
 * aren't on the [androidx.media3.common.Player] surface (skip silence) come in as a custom
 * session command from [AudiobookPlayer].
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var progressSink: PlaybackProgressSink

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

        mediaSession = MediaLibrarySession.Builder(this, player, AutoLibraryCallback(exoPlayer))
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
 * The [MediaLibrarySession.Callback]. Phase 1: only the session/skip-silence plumbing — the
 * browse tree (`onGetLibraryRoot` / `onGetChildren` / `onGetItem` / `onSearch`) and media-id
 * resolution land in Phase 2.
 */
private class AutoLibraryCallback(
    private val player: ExoPlayer?,
) : MediaLibrarySession.Callback {

    private val skipSilence = SessionCommand(PlaybackCommands.SET_SKIP_SILENCE, Bundle.EMPTY)

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
}
