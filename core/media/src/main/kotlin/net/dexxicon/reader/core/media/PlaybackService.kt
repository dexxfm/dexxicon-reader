package net.dexxicon.reader.core.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import net.dexxicon.reader.core.network.di.DexxiconHttpClient
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Background audiobook playback. Media3 handles the media notification, lock-screen
 * controls and audio focus; the stream is fetched through the shared authenticated OkHttp
 * client so range requests carry the server's bearer token.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: OkHttpClient

    private var mediaSession: MediaSession? = null

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

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
