package net.dexxicon.reader

import android.app.Application
import android.content.Intent
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.dexxicon.reader.core.common.crash.CrashReporter
import net.dexxicon.reader.core.common.di.ApplicationScope
import net.dexxicon.reader.core.data.auth.SessionRefreshWorker
import net.dexxicon.reader.core.data.auth.SignInNotifier
import net.dexxicon.reader.core.data.download.CoreDataWorkerFactory
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.media.AudiobookPlayer
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.shared.player.NowPlaying
import net.dexxicon.reader.shared.player.PlayerActions
import net.dexxicon.reader.shared.player.PlayerChapter
import net.dexxicon.reader.shared.player.PlayerUiSnapshot
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class DexxiconApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: CoreDataWorkerFactory

    /** issue #161 — also handed to [AndroidAppContainer.get] (via `okHttpClient.get()`,
     * forcing this [Lazy] early rather than waiting for Coil's first image load) so this
     * container's `TokenManager`/HTTP engine share `:app`'s cookie jar instead of running on a
     * bare, cookie-less client; see that accessor's doc comment for the full story. */
    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: Lazy<OkHttpClient>

    /** Injected eagerly so it starts watching for expired OIDC sessions from launch. */
    @Inject
    lateinit var signInNotifier: SignInNotifier

    @Inject
    lateinit var crashReporter: CrashReporter

    /** Process-lifetime real player engine (Media3-backed) — see [wireMiniPlayer]'s doc
     * comment for why its state gets bridged into `:shared`'s [AndroidAppContainer] here. */
    @Inject
    lateinit var audiobookPlayer: AudiobookPlayer

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    /** issue #156 — handed to [AndroidAppContainer.get] so it reuses `:app`'s Hilt-provided
     * `RoomDatabase` instance instead of opening a second one on the same file; see that
     * accessor's doc comment for why a second instance broke live download-progress updates. */
    @Inject
    lateinit var database: DexxiconDatabase

    override fun onCreate() {
        super.onCreate()
        crashReporter.install()
        // WorkManager.getInstance() + a periodic enqueue does disk I/O; keep it off the
        // startup path — the 6-hour cadence doesn't care about a few ms of delay.
        thread(name = "session-refresh-schedule", isDaemon = true) {
            SessionRefreshWorker.schedule(this)
        }
        wireMiniPlayer()
    }

    /**
     * Phase 4 Stage I (issue #146) — `:shared`'s `MiniPlayer` composable (docked in `App.kt`,
     * now the one Compose tree [MainActivity] hosts directly) reads
     * [AndroidAppContainer]'s `nowPlaying`/`onMiniPlayer*` — plain platform-agnostic state and
     * callbacks, see [NowPlaying]'s own doc comment — rather than the real, deeply
     * Android-specific [AudiobookPlayer] (Media3 `MediaController`/`PlaybackService`) directly,
     * which `:shared` has no access to and never should. This is the one process-lifetime
     * bridge between them: forward every [AudiobookPlayer.state] change into
     * [net.dexxicon.reader.shared.di.AppContainer.updateNowPlaying], and point the mini-player's
     * play/pause/dismiss/reopen actions back at the real player. `AndroidAppContainer.get(this)`
     * is the same process-lifetime singleton [MainActivity]'s `setContent` reads — never a
     * second, independently-built one (see that accessor's own doc comment on why constructing
     * it more than once is still safe regardless).
     */
    private fun wireMiniPlayer() {
        val container = AndroidAppContainer.get(this, database, okHttpClient.get())
        container.onMiniPlayerPlayPause = audiobookPlayer::playPause
        container.onMiniPlayerDismiss = audiobookPlayer::stop
        container.onMiniPlayerReopen = {
            val book = audiobookPlayer.state.value.audiobook
            if (book != null) {
                startActivity(
                    ReaderActivity.intent(this, book.serverId, book.bookId, ContentFormat.AUDIOBOOK)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        // Phase 1 of the shared-reader-chrome redesign (issue #183) — the full player screen's
        // own bridge, alongside the mini-player's narrower slice above. Same shape as
        // onMiniPlayerPlayPause and friends: every command the shared PlayerScreen can send,
        // pointed at the same real AudiobookPlayer.
        container.playerActions = PlayerActions(
            playPause = audiobookPlayer::playPause,
            skipForward = audiobookPlayer::skipForward,
            skipBack = audiobookPlayer::skipBack,
            nextChapter = audiobookPlayer::nextChapter,
            previousChapter = audiobookPlayer::previousChapter,
            seekTo = audiobookPlayer::seekTo,
            seekToChapter = audiobookPlayer::seekToChapter,
            setSpeed = audiobookPlayer::setSpeed,
            setSleepTimer = audiobookPlayer::setSleepTimer,
            setSleepTimerEndOfChapter = audiobookPlayer::setSleepTimerEndOfChapter,
        )
        applicationScope.launch {
            audiobookPlayer.state.collect { state ->
                val book = state.audiobook
                container.updateNowPlaying(
                    book?.let {
                        NowPlaying(
                            serverId = it.serverId,
                            bookId = it.bookId,
                            title = it.title,
                            coverUrl = it.coverUrl,
                            currentChapterTitle = state.currentChapterTitle,
                            isPlaying = state.isPlaying,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                        )
                    },
                )
                container.updatePlayerState(
                    book?.let {
                        PlayerUiSnapshot(
                            serverId = it.serverId,
                            bookId = it.bookId,
                            title = it.title,
                            author = it.author,
                            narrator = it.narrator,
                            coverUrl = it.coverUrl,
                            isPlaying = state.isPlaying,
                            isBuffering = state.isBuffering,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            speed = state.speed,
                            sleepTimerEndsAtEpochMs = state.sleepTimerEndsAt,
                            sleepAtChapterEnd = state.sleepAtChapterEnd,
                            currentChapterIndex = state.currentChapterIndex,
                            chapters = it.chapters.map { chapter -> PlayerChapter(chapter.title, chapter.startMs) },
                            playbackError = state.error,
                        )
                    },
                )
            }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient.get() }))
            }
            .memoryCache {
                MemoryCache.Builder().maxSizePercent(context, 0.25).build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
}
