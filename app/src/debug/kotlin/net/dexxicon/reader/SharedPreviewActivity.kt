package net.dexxicon.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import net.dexxicon.reader.shared.App
import net.dexxicon.reader.shared.di.PlatformContext
import net.dexxicon.reader.shared.di.createAppContainer

/**
 * Renders the shared Compose Multiplatform [App] in a bare Android activity, so the exact
 * same composable can be compared against the iOS build. Debug-only.
 *
 * Builds its own [net.dexxicon.reader.shared.di.AppContainer] via `:shared`'s manual
 * composition root rather than reusing `:app`'s Hilt graph — `:shared` has no access to it
 * (see [AppContainer][net.dexxicon.reader.shared.di.AppContainer]'s doc comment). It still
 * opens the *same* `dexxicon.db`/credential store the real app uses (same file, same package
 * — [net.dexxicon.reader.core.database.getDatabaseBuilder] resolves the path from the app's
 * own data dir either way) — a server added here shows up in the real app too, and vice
 * versa, as a second Room connection to that one file.
 *
 *   adb shell am start -n com.dexxfm.dexxicon_reader.debug/net.dexxicon.reader.SharedPreviewActivity
 */
class SharedPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val container = remember { createAppContainer(PlatformContext(applicationContext)) }
            App(container)
        }
    }
}
