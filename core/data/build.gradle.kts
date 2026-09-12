import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// KMP, following the pattern established for :core:network/:core:serverapi. commonMain holds
// the sign-in path: TokenManager + ServerProber (issue #54), ServerRepository + OidcAuthenticator
// + the ProgressSeeder interface (issue #60, completing Phase 1); Phase 4 restructure (issue
// #126) added the reading-progress sync path — ReadingProgressRepository, NativeProgressSync,
// LibrarySeeder, BookActions, KoSyncRepository/KoReaderDigest — Book Detail's progress row
// needs on both platforms. Catalog sources, downloads (WorkManager), and media stay Android-only
// in androidMain; DownloadRepository is the one exception with a real cross-platform shape —
// see its own doc comment for the commonMain-interface/per-platform-actual split.
//
// No Hilt/KSP plugin here — same reason as :core:network/:core:serverapi: the Hilt Gradle
// plugin refuses to apply to a KMP module. DataModule (the one @Module in this module) moved
// to :app; TokenManager/ServerProber are provided from a new :app-hosted module too — they
// can't carry @Inject in commonMain (javax.inject isn't available on iOS).
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.data"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Opt-in required by the KMP androidLibrary DSL — plain unit tests (no device) live
        // in src/androidHostTest, not src/test as with the classic com.android.library plugin.
        withHostTestBuilder {}.configure {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:security"))
            implementation(project(":core:serverapi"))
            // ServerRepository (issue #60) needs ServerDao/ServerEntity directly.
            implementation(project(":core:database"))
            // AuthHeaderProviderImpl (issue #74) implements AuthHeaderProvider; io.ktor.http.Url
            // itself comes in transitively via :core:network's own api(libs.ktor.client.core).
            implementation(project(":core:network"))
            // SyncStateStore (KoSyncRepository/NativeProgressSync's "last synced" timestamps) —
            // now KMP itself (issue #126), see core/datastore/build.gradle.kts.
            implementation(project(":core:datastore"))
            implementation(libs.kotlinx.coroutines.core)
            // ReadingProgressRepository's locator JSON (issue #126) — replaces the Android-only
            // org.json.JSONObject it used before this module went cross-platform.
            implementation(libs.kotlinx.serialization.json)
            // NativeProgressSync's server-timestamp parsing — replaces java.time.Instant.
            implementation(libs.kotlinx.datetime)
            // KoSyncRepository/KoReaderDigest's MD5 (kosync auth keys + partial-document
            // digests) — replaces java.security.MessageDigest.
            implementation(libs.kotlincrypto.hash.md)
        }
        androidMain.dependencies {
            implementation(project(":core:opds"))
            implementation(project(":core:format"))
            implementation(project(":core:media"))

            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.hilt.android)
            implementation(libs.hilt.work)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
            implementation(libs.turbine)
            implementation(libs.kotlinx.coroutines.test)
            // BookOrbitCatalogSourceTest mocks BookOrbitBrowseApi's Ktor client (issue #56) —
            // MockWebServer/Retrofit are gone now that :core:serverapi is off Retrofit.
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}
