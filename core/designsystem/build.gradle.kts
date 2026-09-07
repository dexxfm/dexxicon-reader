plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

apply(from = "$rootDir/gradle/android-common.gradle")

android {
    namespace = "net.dexxicon.reader.core.designsystem"
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    api(libs.androidx.compose.material3.adaptive)
    api(libs.coil.compose)
}
