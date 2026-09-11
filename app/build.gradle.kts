import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

apply(from = "$rootDir/gradle/android-common.gradle")

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    // Kotlin package / R / BuildConfig namespace stays; only the install/Play identity changes.
    namespace = "net.dexxicon.reader"

    defaultConfig {
        applicationId = "com.dexxfm.dexxicon_reader"
        versionCode = 26
        versionName = "0.12.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Custom-scheme OIDC redirect for Grimmory/BookLore: grimmory://oauth2-callback
        // (whitelisted by BookLore out of the box). BookOrbit uses an in-app WebView.
        manifestPlaceholders["appAuthRedirectScheme"] = "grimmory"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Fall back to the debug key only for throw-away local `assembleRelease`
            // smoke builds. A *published* APK signed with a different key than the last
            // one forces users to uninstall (wiping all settings) to update — see the
            // taskGraph guard below, which fails any real release packaging without the
            // real keystore unless -PallowUnsignedRelease is passed.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")

            // Ship symbol tables for the prebuilt .so libs (PDFium, AndroidX) so native
            // crash/ANR traces in Play Vitals are readable. Bundled into the AAB; Play
            // ingests them on upload.
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
        )
    }
}

// Guard against shipping a release APK/AAB signed with the debug key. Every published
// build must carry the same signature or an in-place update is impossible and users have
// to uninstall — losing every setting. Fires only when a release packaging/signing task
// is actually scheduled; pass -PallowUnsignedRelease for a local build you won't distribute.
gradle.taskGraph.whenReady {
    val packagingRelease = allTasks.any { t ->
        val n = t.name
        (n.startsWith("package") || n.startsWith("sign")) && n.contains("Release")
    }
    if (packagingRelease &&
        keystoreProps.getProperty("storeFile") == null &&
        !project.hasProperty("allowUnsignedRelease")
    ) {
        throw GradleException(
            "Release packaging needs keystore.properties + the release keystore so every " +
                "published build shares one signature. Without it the APK is debug-signed and " +
                "updating over a real release forces an uninstall, wiping user settings. " +
                "Add the keystore, or pass -PallowUnsignedRelease for a throw-away local build.",
        )
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:datastore"))
    implementation(project(":core:media"))
    // ServerAuthModule builds NativeAuthApi/OidcApi/TokenManager/ServerProber directly
    // (moved here from :core:serverapi/:core:data — Hilt can't apply to a KMP module; see
    // ServerAuthModule's doc comment). CredentialStore is TokenManager's constructor param.
    implementation(project(":core:serverapi"))
    implementation(project(":core:security"))

    implementation(libs.okhttp)
    // NetworkModule (moved here from :core:network — Hilt can't apply to a KMP module)
    // builds the HttpLoggingInterceptor directly, so it needs this on :app's own classpath.
    implementation(libs.okhttp.logging)
    // ServerAuthModule builds the sign-in path's Ktor client with the OkHttp engine.
    implementation(libs.ktor.client.okhttp)
    // ServerApiModule (moved here from :core:serverapi) builds the shared Retrofit instance.
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.coil.network.okhttp)
    // Supplies the AppCompat theme attrs the Cast MediaRouteButton dialogs need.
    implementation(libs.androidx.appcompat)

    implementation(project(":feature:servers"))
    implementation(project(":feature:catalog"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader-epub"))
    implementation(project(":feature:reader-pdf"))
    implementation(project(":feature:reader-comic"))
    implementation(project(":feature:player"))
    implementation(project(":feature:annotations"))
    implementation(project(":feature:settings"))

    // Phase 0 spike: the shared Compose Multiplatform module, exercised by
    // SharedPreviewActivity (debug builds only).
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Installs the bundled baseline profile on first run (and keeps it warm).
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
