import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// KMP, following :core:network's pattern (issue #48 / PR #51). Every API domain is now on
// Ktor in commonMain — the sign-in path (NativeAuthApi/NativeAuthClient, OidcApi/OidcClient,
// issue #52) plus browse/bookmark/kosync/annotation/progress/user (issue #56). No androidMain
// source left at all: this module doesn't need one, since nothing in it is Android-specific
// any more — the androidLibrary target below just makes it consumable from :app/:core:data.
//
// No Hilt/KSP plugin here — same reason as :core:network: the Hilt Gradle plugin refuses to
// apply to a KMP module. ServerApiModule/ServerAuthModule (the @Provides bindings) live in
// :app — they can't carry @Inject in commonMain (javax.inject isn't available on iOS).
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.serverapi"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Opt-in required by the KMP androidLibrary DSL — plain unit tests (no device) live
        // in src/androidHostTest, not src/test as with the classic com.android.library plugin.
        // commonTest sources (all of this module's tests, now) run there too, alongside
        // iosSimulatorArm64Test on Codemagic's ios-ci.
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
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.serialization.kotlinx.json)
        }
    }
}
