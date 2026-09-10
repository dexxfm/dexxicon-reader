import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// KMP. commonMain holds the pure bits (Outcome/DexxiconError, htmlToPlainText,
// DexxiconDispatcher). androidMain keeps the Android-only crash reporting + the
// javax.inject qualifier annotations; the Hilt @Module that provides the dispatchers
// moved to :app (Hilt modules compile only where the components are).
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.common"
        compileSdk = 37
        minSdk = 29
        // Match the rest of the project — the AGP KMP plugin otherwise targets the
        // running JDK (25), which can't be inlined into the target-17 modules.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
        }
        androidMain.dependencies {
            implementation(libs.hilt.android)
        }
    }
}
