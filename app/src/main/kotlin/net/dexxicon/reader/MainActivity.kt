package net.dexxicon.reader

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint
import net.dexxicon.reader.core.data.auth.SignInNotifier
import net.dexxicon.reader.core.datastore.AppPreferences
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.AppTheme
import net.dexxicon.reader.core.designsystem.theme.DexxiconTheme
import net.dexxicon.reader.ui.DexxiconApp
import net.dexxicon.reader.ui.ReauthCoordinator
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var appPreferences: AppPreferencesStore

    @Inject
    lateinit var reauthCoordinator: ReauthCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleReauthIntent(intent)

        setContent {
            val prefs by remember { appPreferences.preferences }
                .collectAsState(initial = AppPreferences())
            val darkTheme = when (prefs.theme) {
                AppTheme.LIGHT -> false
                AppTheme.DARK -> true
                AppTheme.SYSTEM -> isSystemInDarkTheme()
            }
            DexxiconTheme(darkTheme = darkTheme) {
                DexxiconApp()
            }
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
