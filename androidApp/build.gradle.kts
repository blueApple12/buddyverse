import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: a keystore.properties file (storeFile, storePassword, keyAlias, keyPassword; storeFile relative to
// that file) signs with your own key. Where it is looked for, first match wins:
//   -Pbuddyverse.keystore=<path to keystore.properties>, env BUDDYVERSE_KEYSTORE=<path>, ./keystore.properties (gitignored)
// Keep it OUTSIDE the repo. Without it the release build is signed with the local debug key, so anyone can build it.
val keystoreFile: File? = ((findProperty("buddyverse.keystore") as String?) ?: System.getenv("BUDDYVERSE_KEYSTORE"))
    ?.takeIf { it.isNotBlank() }?.let(::file) ?: rootProject.file("keystore.properties").takeIf { it.exists() }
val keystoreProps = Properties().apply { keystoreFile?.takeIf { it.exists() }?.inputStream()?.use(::load) }

android {
    namespace = "app.buddyverse"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.buddyverse"
        minSdk = 28
        targetSdk = 37
        versionCode = 20
        versionName = "0.1.0"
    }
    signingConfigs {
        keystoreProps.getProperty("storeFile")?.let { path ->
            create("release") {
                storeFile = keystoreFile!!.parentFile.resolve(path)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            ndk { abiFilters += "arm64-v8a" } // debug stays universal for the x86_64 emulator
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { resources { excludes += setOf("META-INF/*.version", "META-INF/{AL2.0,LGPL2.1,INDEX.LIST}", "DebugProbesKt.bin") } }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.activity.compose)
}
