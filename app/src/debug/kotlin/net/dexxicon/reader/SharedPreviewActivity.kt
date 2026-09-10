package net.dexxicon.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import net.dexxicon.reader.shared.App

/**
 * Phase 0 spike: renders the shared Compose Multiplatform [App] in a bare Android activity,
 * so the exact same composable can be compared against the iOS build. Debug-only.
 *
 *   adb shell am start -n com.dexxfm.dexxicon_reader.debug/net.dexxicon.reader.SharedPreviewActivity
 */
class SharedPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
