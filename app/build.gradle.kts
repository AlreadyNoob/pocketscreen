plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI sets GITHUB_RUN_NUMBER, so every build gets a higher versionCode and installs over the last one.
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.hiderlab.vd"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.hiderlab.vd"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "${project.property("appVersion")}.$runNumber"
    }
    // Fixed signing key, so every build has the same signature and installs over the old one.
    signingConfigs {
        create("hider") {
            storeFile = file("hiderlab.jks")
            storePassword = "hiderlab123"
            keyAlias = "hiderlab"
            keyPassword = "hiderlab123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("hider") }
    }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
