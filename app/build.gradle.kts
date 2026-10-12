plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every build is signed with the same key (app/pssender-debug.keystore). A GitHub build machine would
// otherwise make a new random key on every run, and Android refuses to install an app over a copy
// that was signed with a different key (you would have to uninstall first and lose your saved data).
val fixedKey = file("pssender-debug.keystore")

// The GitHub build number is the version. It goes up with every build, so each build counts as an
// update of the one before it. A build on another machine (no build number) stays at 1.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.example.pssender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.pssender"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    signingConfigs {
        getByName("debug") {
            if (fixedKey.exists()) {
                storeFile = fixedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0") // pull down on a web page to load it again
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("commons-net:commons-net:3.11.1")
}
