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
            // Phase 4 restructure (issue #126) — design tokens, the pill nav bar/rail, and the
            // Book Detail-shared cover/format components now live in one place instead of two
            // copies; see core/designsystem/build.gradle.kts's own doc comment.
            implementation(project(":core:designsystem"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            // Phase 4 (issue #115) — the adaptive nav shell's Home/Library/Settings icons.
            // compose.material3 alone doesn't bundle these (only a handful of glyphs ship
            // there); this is the same extended icon set native's own
            // navigation/TopLevelDestination.kt already draws Icons.Filled.Home/Settings and
            // Icons.AutoMirrored.Filled.LibraryBooks from, so both platforms render the
            // identical glyph.
            implementation(compose.materialIconsExtended)
            implementation(libs.navigation.compose.multiplatform)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            // Pkce (Slice 2, issue #70) — pure-Kotlin SHA-256 + secure random, works
            // identically on Android and iOS. See the libs.versions.toml doc comment.
            implementation(libs.kotlincrypto.hash.sha2)
            implementation(libs.kotlincrypto.random)
            // Cover images (issue #78) — the Ktor fetcher reuses AppContainer's own
            // authenticated HttpClient rather than a second, unauthenticated one.
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
