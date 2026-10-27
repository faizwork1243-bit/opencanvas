plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.opencanvas.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.opencanvas.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "0.3.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
