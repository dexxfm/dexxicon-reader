package net.dexxicon.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import net.dexxicon.reader.core.data.auth.SignInNotifier
import net.dexxicon.reader.core.database.DexxiconDatabase
import net.dexxicon.reader.core.network.DexxiconHttpClient
import net.dexxicon.reader.shared.di.AndroidAppContainer
import net.dexxicon.reader.ui.DexxiconApp
import net.dexxicon.reader.ui.ReauthCoordinator
import okhttp3.OkHttpClient
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var reauthCoordinator: ReauthCoordinator

    /** issue #156/#161 — see [AndroidAppContainer.get]'s doc comment; [DexxiconApplication]
     * already initializes the singleton with these same instances before this Activity is
     * ever created, but injecting them here too means `.get()` is never called without them,
     * regardless of startup order. */
    @Inject
    lateinit var database: DexxiconDatabase

    @Inject
    @DexxiconHttpClient
    lateinit var okHttpClient: OkHttpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleReauthIntent(intent)

        setContent {
            val container = remember { AndroidAppContainer.get(applicationContext, database, okHttpClient) }
            DexxiconApp(
                container = container,
                // Phase 4 Stage I (issue #146) — mirrors iOS's ContentView.swift exactly:
                // reading always hands off to a screen outside :shared's own Compose tree
                // (see ReaderActivity's doc comment for why an Activity, not a route inside
                // App()'s NavHost). url/authHeader/isManga/audiobook are ignored here — unlike
                // iOS's Swift readers, the native Hilt-injected reader ViewModels this Activity
                // hosts already resolve everything they need themselves from serverId/bookId
                // via their own repositories, the same way they did before :shared existed.
                onOpenReader = { serverId, bookId, format, _, _, _, _, _ ->
                    startActivity(ReaderActivity.intent(this, serverId, bookId, format))
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReauthIntent(intent)
    }

    private fun handleReauthIntent(intent: Intent?) {
        val serverId = intent?.getStringExtra(SignInNotifier.EXTRA_REAUTH_SERVER) ?: return
        reauthCoordinator.request(serverId)
        intent.removeExtra(SignInNotifier.EXTRA_REAUTH_SERVER)
    }
}
