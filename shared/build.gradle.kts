import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Phase 2 Slice 1 (issue #62): the shared Compose Multiplatform UI — app shell + native
// sign-in — wired to the Phase 1 data layer (:core:data/:core:serverapi/:core:security via
// AppContainer, di/AppContainer.kt). commonMain holds the composables and the manual
// (non-Hilt) composition root; androidMain/iosMain each supply the platform primitives
// AppContainer needs (Ktor engine, CredentialStore, the Room database builder).
//
// No `jvm()` target: it was a harmless Phase 0 leftover while :shared only depended on the
// pure-Kotlin :core:model, but :core:security/:core:serverapi/:core:data/:core:database
// (needed from here on) only target androidLibrary + iOS — there's no plain-JVM variant of
// them to resolve against, and nothing in this project ever built or consumed :shared's jvm
// target.
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.shared"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    val xcfName = "SharedKit"
    iosArm64 { binaries.framework { baseName = xcfName; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = xcfName; isStatic = true } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:network"))
            implementation(project(":core:security"))
            implementation(project(":core:serverapi"))
            implementation(project(":core:database"))
            implementation(project(":core:data"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.navigation.compose.multiplatform)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
