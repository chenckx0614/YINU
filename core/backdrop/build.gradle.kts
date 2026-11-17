plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "com.yinu.backdrop"
        compileSdk = 37
        minSdk = 26
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.kyant.shapes)
            implementation(libs.jetbrains.annotations)
        }
    }
}
