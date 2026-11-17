plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.yinu.database"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    api(libs.room.runtime)
    ksp(libs.room.compiler)
}
