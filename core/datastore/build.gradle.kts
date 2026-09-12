import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Phase 4 restructure (issue #126) — KMP. SyncStateStore (KoSyncRepository/NativeProgressSync's
// "last synced" timestamps, both now commonMain in :core:data) moves to commonMain on
// DataStore's multiplatform Preferences API (androidx.datastore:datastore-preferences-core) —
// its `PreferenceDataStoreFactory.createWithPath` takes an okio.Path file location instead of
// the Android-only `Context.preferencesDataStore` extension, so each platform supplies its own
// producer (androidMain: Context.filesDir; iosMain: NSDocumentDirectory).
//
// AppPreferencesStore and PlayerPreferencesStore stay Android-only in androidMain, unchanged —
// nothing outside :app's own Hilt-injected screens needs them yet (confirmed: Book Detail's
// dependency chain never touches them — DownloadRepository's Android actual is the only thing
// that uses AppPreferencesStore, and it only ever runs on Android anyway).
//
// No Hilt/KSP plugin here — same reason as :core:data: the Hilt Gradle plugin refuses to
// apply to a KMP module. Hilt's :app-level KSP pass still wires up @Inject constructors
// living in androidMain just fine (same as :core:data's androidMain classes today).
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.datastore"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:common"))
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.hilt.android)
        }
    }
}
