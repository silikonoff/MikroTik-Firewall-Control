plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.nikmadz.mikrotikcontrol"
    compileSdk = 35
    defaultConfig {
        applicationId = "ru.nikmadz.mikrotikcontrol"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.2.11"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Optional local debug signing path for restricted Windows build environments.
    // Without the property, keep Android's existing debug signing identity.
    providers.gradleProperty("debugKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
    }
    sourceSets.getByName("test").java.srcDir("../tests/shared")
    buildTypes { getByName("release") { isMinifyEnabled = false } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
