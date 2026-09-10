import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

// Phase 0 spike: the shared Compose Multiplatform surface. commonMain holds one App()
// composable rendered on both an Android activity and an iOS ComposeUIViewController.
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.shared"
        compileSdk = 37
        minSdk = 29
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    val xcfName = "SharedKit"
    iosArm64 { binaries.framework { baseName = xcfName; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = xcfName; isStatic = true } }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:model"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
        }
    }
}
