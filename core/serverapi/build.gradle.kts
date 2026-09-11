import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// KMP, following :core:network's pattern (issue #48 / PR #51). commonMain holds the sign-in
// path — NativeAuthApi/NativeAuthClient, OidcApi/OidcClient — on Ktor, the actual Phase 1
// milestone (issue #52: "sign in to BookOrbit + Grimmory from iOS, hold + refresh a
// session"). androidMain keeps every other API (browse/bookmark/kosync/annotation/progress/
// user) on Retrofit, unchanged, until each gets its own follow-up port — same "port what's
// needed now" approach :core:network used for AuthInterceptor/PersistentCookieJar.
//
// No Hilt/KSP plugin here — same reason as :core:network: the Hilt Gradle plugin refuses to
// apply to a KMP module. ServerApiModule (the Retrofit bindings for the untouched APIs)
// moved to :app; the new Ktor-backed classes are provided from a new :app-hosted module too
// — they can't carry @Inject in commonMain (javax.inject isn't available on iOS).
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.serverapi"
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
            api(project(":core:common"))
            implementation(project(":core:network"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
        }
        androidMain.dependencies {
            api(libs.retrofit)
            implementation(libs.retrofit.kotlinx.serialization)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okhttp)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.hilt.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
            implementation(libs.mockwebserver)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
