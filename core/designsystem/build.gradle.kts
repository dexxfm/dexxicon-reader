import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Phase 4 restructure (issue #126): KMP, following the pattern established for
// :core:data/:core:network/:core:serverapi. This module is pure Compose Multiplatform UI —
// tokens, shapes, the cover/format/context-menu/browse-controls components, and the pill nav
// bar/rail — with no Hilt anywhere. Dynamic color (Android 12+ wallpaper-seeded theme) is the
// one genuinely platform-specific piece, behind theme/DynamicColor.kt's expect/actual.
// PageTurnGesture.kt stays androidMain-only: it draws on android.graphics.Bitmap/Canvas/View
// directly (it embeds Readium's Android-only PDF/comic Fragment navigators), not something
// any commonMain caller (including :shared, which has no reader UI at all — see
// shared/ReaderLaunch.kt's OnOpenReader doc comment) will ever need.
compose.resources {
    packageOfResClass = "net.dexxicon.reader.core.designsystem.generated.resources"
}

kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.designsystem"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTestBuilder {}.configure {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            api(libs.coil.compose)
        }
    }
}
