import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// KMP, following the pattern established for :core:network/:core:serverapi. commonMain holds
// the sign-in path: TokenManager + ServerProber (issue #54), ServerRepository + OidcAuthenticator
// + the ProgressSeeder interface (issue #60, completing Phase 1). Everything else in this
// module (the other repositories, catalog sources, sync, downloads, media) stays Android-only
// in androidMain, unchanged — those depend on other still-Android-only modules (KoSyncRepository
// specifically needs Context/Settings.Secure/raw OkHttpClient); porting them is a separate,
// larger decision.
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
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(project(":core:datastore"))
            implementation(project(":core:network"))
            implementation(project(":core:opds"))
            implementation(project(":core:format"))
            implementation(project(":core:media"))

            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.hilt.android)
            implementation(libs.hilt.work)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.kotlinx.serialization.json)
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
