package net.dexxicon.reader.shared.di

import coil3.PlatformContext as CoilPlatformContext
import io.ktor.client.engine.darwin.Darwin
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.dexxicon.reader.core.data.download.IosDownloadRepository
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.PlatformStorageContext
import net.dexxicon.reader.core.datastore.PlayerPreferencesStore
import net.dexxicon.reader.core.datastore.ReaderPreferencesStore
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.network.AuthHeaderProvider
import net.dexxicon.reader.core.network.createHttpClient
import net.dexxicon.reader.core.security.CredentialStore
import platform.Foundation.NSBundle
import platform.UIKit.UIDevice

/** iOS [actual]: no platform handle is needed — [getDatabaseBuilder] resolves the app's own
 * Documents directory, and [CredentialStore]'s iOS actual takes no constructor args. */
actual class PlatformContext

/**
 * Forwards to [delegate] once it's set — [IosDownloadRepository] (issue #174) needs an
 * authenticated [io.ktor.client.HttpClient] (downloads hit the same Bearer/cookie-protected
 * book-content endpoints everything else does), but it has to exist *before* [AppContainer]
 * (it's one of that class's own constructor parameters), and [AppContainer]'s real auth
 * machinery is only built *inside* that constructor. Same shape as [AppContainer]'s own
 * `realAuthHeaderProvider` forwarding wrapper, just one layer further out — wired up via
 * [delegate] right after [AppContainer] itself is constructed, below.
 */
private class DeferredAuthHeaderProvider : AuthHeaderProvider {
    var delegate: AuthHeaderProvider? = null
    override fun authHeader(url: Url) = delegate?.authHeader(url)
    override fun refreshAuthHeader(url: Url) = delegate?.refreshAuthHeader(url)
}

/**
 * Uses [Dispatchers.Default] where the Android actual uses `Dispatchers.IO` — Kotlin/Native
 * has no equivalent of the JVM's large-pool blocking-IO dispatcher, and
 * `kotlinx.coroutines.Dispatchers.IO` itself is `internal` (not public API) on Native; a
 * core-sized `Default` pool is the standard KMP substitute (see [AppContainer]'s doc comment
 * on [io][AppContainer]).
 */
actual fun createAppContainer(context: PlatformContext): AppContainer {
    val database = getDatabaseBuilder().finish(Dispatchers.Default)
    val credentialStore = CredentialStore()
    // issue #174 — real downloads now: a dedicated authenticated HttpClient (via the same
    // createHttpClient every other API call uses) plus a process-lifetime scope for the
    // download coroutines themselves to run on, independent of any one screen's lifecycle.
    val deferredAuth = DeferredAuthHeaderProvider()
    val downloadRepository = IosDownloadRepository(
        httpClient = createHttpClient(Darwin.create(), deferredAuth),
        dao = database.downloadDao(),
        appPreferences = AppPreferencesStore(PlatformStorageContext()),
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        io = Dispatchers.Default,
    )
    val container = AppContainer(
        engine = Darwin.create(),
        credentialStore = credentialStore,
        database = database,
        io = Dispatchers.Default,
        // Coil's non-Android PlatformContext is a plain singleton — nothing to configure,
        // unlike Android's (which really is a Context).
        coilPlatformContext = CoilPlatformContext.INSTANCE,
        downloadRepository = downloadRepository,
        syncStateStore = SyncStateStore(PlatformStorageContext()),
        appPreferences = AppPreferencesStore(PlatformStorageContext()),
        readerPreferences = ReaderPreferencesStore(PlatformStorageContext()),
        playerPreferences = PlayerPreferencesStore(PlatformStorageContext()),
        // identifierForVendor resets if every app from this vendor is uninstalled, unlike
        // Android's ANDROID_ID — acceptable here: a fresh kosync device id just looks like a
        // new device to KOReader's server, same as reinstalling on Android would after a
        // factory reset.
        koSyncRawDeviceId = UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "dexxicon",
        koSyncDeviceModel = UIDevice.currentDevice.model,
        appVersionName = (NSBundle.mainBundle.infoDictionary?.get("CFBundleShortVersionString") as? String)
            ?: "unknown",
    )
    deferredAuth.delegate = container.authHeaderProvider
    return container
}
