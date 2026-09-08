plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

apply(from = "$rootDir/gradle/android-common.gradle")

android {
    namespace = "net.dexxicon.reader.baselineprofile"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Absolute timings from an emulator aren't real-device-representative, but the
        // relative before/after (None vs BaselineProfile vs Full) still is. A physical
        // device drops this and reports trustworthy numbers.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
}

// Generate against a connected device (the project has no GMD images set up). Any non-rooted
// API 33+ phone/emulator works; `./gradlew :app:generateBaselineProfile`.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
}
