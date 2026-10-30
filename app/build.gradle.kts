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
        versionCode = 9
        versionName = "0.5.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        // The build you install from GitHub Actions. It is signed with the standard debug key so it
        // installs anywhere, but it is built like a release: not debuggable (debug mode slows an app
        // down) and with the unused parts of the Kotlin library stripped out (smaller, faster install).
        getByName("debug") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
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
