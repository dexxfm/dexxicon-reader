package net.dexxicon.reader.shared.di

import coil3.PlatformContext as CoilPlatformContext
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.Dispatchers
import net.dexxicon.reader.core.data.download.IosDownloadRepository
import net.dexxicon.reader.core.database.finish
import net.dexxicon.reader.core.database.getDatabaseBuilder
import net.dexxicon.reader.core.datastore.AppPreferencesStore
import net.dexxicon.reader.core.datastore.PlatformStorageContext
import net.dexxicon.reader.core.datastore.SyncStateStore
import net.dexxicon.reader.core.security.CredentialStore
import platform.UIKit.UIDevice

/** iOS [actual]: no platform handle is needed — [getDatabaseBuilder] resolves the app's own
 * Documents directory, and [CredentialStore]'s iOS actual takes no constructor args. */
actual class PlatformContext

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
    return AppContainer(
        engine = Darwin.create(),
        credentialStore = credentialStore,
        database = database,
        io = Dispatchers.Default,
        // Coil's non-Android PlatformContext is a plain singleton — nothing to configure,
        // unlike Android's (which really is a Context).
        coilPlatformContext = CoilPlatformContext.INSTANCE,
        // Honest "not supported yet" (issue #126) — no iOS equivalent of WorkManager-backed
        // background downloads exists in this app today; see IosDownloadRepository's doc
        // comment.
        downloadRepository = IosDownloadRepository(),
        syncStateStore = SyncStateStore(PlatformStorageContext()),
        appPreferences = AppPreferencesStore(PlatformStorageContext()),
        // identifierForVendor resets if every app from this vendor is uninstalled, unlike
        // Android's ANDROID_ID — acceptable here: a fresh kosync device id just looks like a
        // new device to KOReader's server, same as reinstalling on Android would after a
        // factory reset.
        koSyncRawDeviceId = UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "dexxicon",
        koSyncDeviceModel = UIDevice.currentDevice.model,
    )
}
