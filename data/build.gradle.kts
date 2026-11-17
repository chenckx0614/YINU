plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.yinu.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":core:database"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

val versionOneSchema = project(":core:database").file("schemas/com.yinu.database.YinuDatabase/1.json")
val versionFourSchema = project(":core:database").file("schemas/com.yinu.database.YinuDatabase/4.json")
tasks.withType<Test>().configureEach {
    inputs.file(versionOneSchema)
    inputs.file(versionFourSchema)
    systemProperty("yinu.schema.v1", versionOneSchema.absolutePath)
    systemProperty("yinu.schema.v4", versionFourSchema.absolutePath)
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
}
