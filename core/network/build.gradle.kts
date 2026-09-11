import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// KMP. commonMain holds the platform-agnostic pieces: AuthHeaderProvider (Ktor's Url type,
// not OkHttp's), ConnectivityMonitor's expect declaration, and a new (not yet
// Android-wired — :core:serverapi still uses Retrofit+OkHttp) Ktor HttpClient + auth plugin
// ready for that module's own future port. androidMain keeps AuthInterceptor,
// PersistentCookieJar, ReadiumHttpClient and the DexxiconHttpClient qualifier exactly as they
// were (still real OkHttp Interceptor/CookieJar — Retrofit and Readium's own HTTP client both
// still need them), plus ConnectivityMonitor's actual (unchanged ConnectivityManager code).
// iosMain supplies ConnectivityMonitor's actual and the Darwin Ktor engine.
//
// No Hilt/KSP plugin here — the Hilt Gradle plugin refuses to apply to a KMP module at all
// ("can only be applied to an Android project"), so NetworkModule (the @Module with the
// @Provides functions) moved to :app, same as :core:common's DispatchersModule. The
// `@Inject constructor` classes that remain here (AuthInterceptor, PersistentCookieJar,
// ConnectivityMonitor) need no local KSP processing for that — same as :core:common's
// CrashReporter/DiagnosticsArchive — Hilt aggregates modules at :app.
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.network"
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
            implementation(libs.kotlinx.serialization.json)
            // api: AuthHeaderProvider's public interface exposes io.ktor.http.Url, so every
            // consumer (e.g. :core:data's AuthHeaderProviderImpl) needs this type visible too.
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        androidMain.dependencies {
            implementation(project(":core:security"))
            api(libs.okhttp)
            implementation(libs.okhttp.logging)
            api(libs.readium.shared)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.hilt.android)
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.connectivity.core)
            implementation(libs.connectivity.apple)
        }
    }
}
