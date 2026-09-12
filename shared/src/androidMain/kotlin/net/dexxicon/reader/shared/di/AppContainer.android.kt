package net.dexxicon.reader.shared.di

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import net.dexxicon.reader.core.data.download.AndroidDownloadRepository
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.PlatformStorageContext
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.security.CredentialStore
import net.dexxicon.reader.core.security.CryptoStore

/**
 * Android [actual]: wraps the plain [Context] the DB path and [CredentialStore]'s DataStore
 * need. A wrapper class rather than `actual typealias PlatformContext = Context` — a
 * typealias to Android's `Context` (itself an abstract class) makes `PlatformContext`
 * abstract too, which then conflicts with the commonMain `expect class PlatformContext`
 * (implicitly final) and, worse, wouldn't let the iOS actual stay a plain concrete class.
 */
actual class PlatformContext(val context: Context)

/**
 * A plain OkHttp engine (not `:app`'s Hilt-provided [okhttp3.OkHttpClient] with its cookie
 * jar/logging) — `:shared` has no access to `:app`'s Hilt graph (see [AppContainer]'s doc
 * comment on [AuthHeaderProviderImpl][net.dexxicon.reader.core.data.auth.AuthHeaderProviderImpl]
 * for how it gets a real one anyway).
 *
 * `coil3.PlatformContext` is `android.content.Context` itself on Android (a typealias, per
 * Coil's own `androidMain`) — [appContext] satisfies the [AppContainer] constructor's
 * `coilPlatformContext` param with no extra wrapping needed.
 */
actual fun createAppContainer(context: PlatformContext): AppContainer {
    val appContext = context.context.applicationContext
    val database = getDatabaseBuilder(appContext).finish(Dispatchers.IO)
    val credentialStore = CredentialStore(appContext, CryptoStore())
    val appPreferences = AppPreferencesStore(PlatformStorageContext(appContext))
    val downloadRepository = AndroidDownloadRepository(
        context = appContext,
        dao = database.downloadDao(),
        appPreferences = appPreferences,
        io = Dispatchers.IO,
    )
    val syncStateStore = SyncStateStore(PlatformStorageContext(appContext))
    return AppContainer(
        engine = OkHttp.create(),
        credentialStore = credentialStore,
        database = database,
        io = Dispatchers.IO,
        coilPlatformContext = appContext,
        downloadRepository = downloadRepository,
        syncStateStore = syncStateStore,
        koSyncRawDeviceId = deviceId(appContext),
        koSyncDeviceModel = Build.MODEL ?: "Android",
        appPreferences = appPreferences,
    )
}

@SuppressLint("HardwareIds")
private fun deviceId(context: Context): String =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "dexxicon"
