plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yinu.designsystem"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    api(project(":core:backdrop"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.miuix.icons) {
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-ui")
        exclude(group = "top.yukonga.miuix.kmp", module = "miuix-ui-android")
    }
}
