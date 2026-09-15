package net.dexxicon.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.designsystem.theme.DexxiconTheme
import net.dexxicon.reader.core.model.ContentFormat
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.feature.player.PlayerViewModel
import net.dexxicon.reader.feature.reader.comic.navigation.ComicReaderRoute
import net.dexxicon.reader.feature.reader.comic.navigation.comicReaderSection
import net.dexxicon.reader.feature.reader.epub.navigation.EpubReaderRoute
import net.dexxicon.reader.feature.reader.epub.navigation.epubReaderSection
import net.dexxicon.reader.feature.reader.pdf.navigation.PdfReaderRoute
import net.dexxicon.reader.feature.reader.pdf.navigation.pdfReaderSection
import net.dexxicon.reader.feature.player.navigation.PlayerRoute
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.shared.player.PlayerScreen
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Phase 4 Stage I (issue #146) — a book's actual reading/listening surface, presented as its
 * own Activity rather than a destination inside `:shared`'s own `App()` NavHost. Mirrors iOS's
 * architecture exactly: `ContentView.swift`'s `onOpenReader` implementation calls
 * `hostVC.present(reader, animated: true)`, pushing a fully native `UIViewController` *outside*
 * the Compose/SwiftUI tree `:shared`'s `App()` owns — the four native reader/player screens
 * here (Readium/Media3, permanently native — see the Phase 4 plan's own scope note) were never
 * going to move into `:shared`'s commonMain NavHost, so `onOpenReader`'s Android implementation
 * (see [MainActivity]) does the same thing Swift already does: hand off to a separate screen
 * outside `:shared`'s own navigation graph, instead of a route inside it.
 *
 * The four reader `*Section` builders below are untouched from their old home in
 * `DexxiconNavHost.kt` — each already resolves its own `serverId`/`bookId` from this NavHost's
 * typed route via `SavedStateHandle` (Hilt `@Inject`-ed ViewModels), so nothing about how they
 * work needed to change, only which NavHost hosts them.
 */
@AndroidEntryPoint
class ReaderActivity : FragmentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferencesStore

    /** Phase 1 of the shared-reader-chrome redesign (issue #183) — same pair [MainActivity]
     * already injects to resolve the one process-lifetime [AndroidAppContainer], now needed
     * here too so the player destination can read/command [net.dexxicon.reader.shared.player.PlayerScreen]'s
     * state from the same container the mini-player uses, instead of `feature/player`'s own
     * (now superseded) Compose UI. */
    @Inject
    lateinit var database: DexxiconDatabase

    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val serverId = intent.getStringExtra(EXTRA_SERVER_ID).orEmpty()
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        val format = intent.getStringExtra(EXTRA_FORMAT)?.let { runCatching { ContentFormat.valueOf(it) }.getOrNull() }
            ?: ContentFormat.EPUB

        setContent {
            val prefs by remember { appPreferences.preferences }.collectAsState(initial = AppPreferences())
            val darkTheme = when (prefs.theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            DexxiconTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = format.startRoute(serverId, bookId),
                ) {
                    // issue #155: this NavHost's sole destination is whichever reader/player
                    // route `format` picked above — there's nothing else to pop back to, so
                    // each section's own onBack falls through to finishing this Activity.
                    epubReaderSection(navController, onExit = ::finish)
                    comicReaderSection(navController, onExit = ::finish)
                    pdfReaderSection(navController, onExit = ::finish)
                    // issue #183: renders the shared PlayerScreen instead of feature/player's
                    // own (now superseded) Compose UI. PlayerViewModel is still injected purely
                    // for its side effect — its init{} block resolves the book and starts
                    // playback on the real AudiobookPlayer, exactly as before — but its own
                    // screen/playback StateFlows are no longer read for rendering; the shared
                    // screen reads AndroidAppContainer's playerState/playerActions instead,
                    // the same bridge DexxiconApplication.wireMiniPlayer() already populates.
                    composable<PlayerRoute> {
                        // issue #190: `screen` is read again (it wasn't at all, between #184
                        // and this fix) purely for `error` — resolving the book/starting
                        // playback failing (no audio acquisition, a catalog fetch error) — the
                        // one thing the shared screen's own `state` (AndroidAppContainer's
                        // playerState, sourced from AudiobookPlayer directly) can't represent,
                        // since it stays null until playback has actually started. Without
                        // this, that failure left the player stuck on an unexplained, permanent
                        // loading spinner — `state` never becomes non-null because `play()` is
                        // never reached.
                        val playerViewModel = hiltViewModel<PlayerViewModel>()
                        val screen by playerViewModel.screen.collectAsStateWithLifecycle()
                        val container = remember {
                            AndroidAppContainer.get(applicationContext, database, okHttpClient)
                        }
                        val state by container.playerState.collectAsStateWithLifecycle()
                        val actions = container.playerActions
                        if (actions != null) {
                            PlayerScreen(
                                state = state,
                                actions = actions,
                                onBack = { if (!navController.popBackStack()) finish() },
                                error = screen.error,
                                // issue #216 — same reasoning as `error` above: a per-open
                                // decision the shared screen's own `state` (sourced straight
                                // from the player engine) has no way to represent.
                                resumeConflict = screen.resumeConflict,
                                onResumeConflict = playerViewModel::resolveResumeConflict,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SERVER_ID = "serverId"
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_FORMAT = "format"

        /** Matches `DexxiconNavHost.kt`'s old per-format routing exactly (issue #101/#106-108):
         * comics get their own reader, PDF its own, audiobooks the player — everything else
         * (EPUB and the three formats the server already converts to EPUB: MOBI/AZW3/FB2)
         * shares the EPUB Navigator. */
        private fun ContentFormat.startRoute(serverId: String, bookId: String): Any = when (this) {
            ContentFormat.COMIC -> ComicReaderRoute(serverId, bookId)
            ContentFormat.PDF -> PdfReaderRoute(serverId, bookId)
            ContentFormat.AUDIOBOOK -> PlayerRoute(serverId, bookId)
            else -> EpubReaderRoute(serverId, bookId)
        }

        fun intent(context: Context, serverId: String, bookId: String, format: ContentFormat): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_SERVER_ID, serverId)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_FORMAT, format.name)
    }
}
