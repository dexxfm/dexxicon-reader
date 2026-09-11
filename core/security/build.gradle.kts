import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// KMP. commonMain declares the CredentialStore contract; androidMain keeps today's
// DataStore + CryptoStore (AES-GCM, AndroidKeyStore) implementation unchanged. iosMain
// stores directly in the Keychain (already encrypted at rest by the OS, so no separate
// cipher needed there) via `multiplatform-settings`'s KeychainSettings — CryptoStore itself
// stays Android-only, since nothing on iOS calls it.
kotlin {
    androidLibrary {
        namespace = "net.dexxicon.reader.core.security"
        compileSdk = 37
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // No jvm() target: CredentialStore is genuinely Android- or iOS-specific (DataStore vs
    // Keychain) with no meaningful plain-JVM implementation, unlike :core:common/:core:model
    // which are pure enough to serve one.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.datastore.preferences)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.hilt.android)
        }
        iosMain.dependencies {
            implementation(libs.multiplatform.settings)
        }
    }
}
