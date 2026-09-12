package net.dexxicon.reader

import android.os.Bundle
import android.widget.Toast
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
            App(container, onOpenReader = { _, _, format, _, _, _, _ ->
                // issue #99 — this preview activity exists only to compare :shared's
                // rendering against native, never to actually read a book: the real, already-
                // working native reader stack (Readium/Media3) lives entirely in :app's own
                // separate Hilt graph, which this debug-only composition root deliberately
                // has no access to. iOS's actual (MainViewController) hands the same
                // callback to a real Readium Swift Toolkit reader instead.
                Toast.makeText(
                    this@SharedPreviewActivity,
                    "Reading isn't available in this shared-UI preview ($format) — open Dexxicon Reader to read this book.",
                    Toast.LENGTH_LONG,
                ).show()
            })
        }
    }
}
