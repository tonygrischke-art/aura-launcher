import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeBomVersion: String by project
val activityComposeVersion: String by project
val lifecycleVersion: String by project
val coreKtxVersion: String by project
val coroutinesVersion: String by project

android {
    namespace = "com.aetheria.aura"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aetheria.aura"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-aura"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing config for stable debug keystore from GitHub secrets (optional)
    signingConfigs {
        create("debugConfig") {
            val storeFilePath = System.getenv("DEBUG_KEYSTORE_PATH")
            val storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("DEBUG_KEY_ALIAS")
            val keyPassword = System.getenv("DEBUG_KEY_PASSWORD")
            if (storeFilePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = File(storeFilePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            // Use stable debug keystore if configured, otherwise use default debug keystore
            val storeFilePath = System.getenv("DEBUG_KEYSTORE_PATH")
            val storePassword = System.getenv("DEBUG_KEYSTORE_PASSWORD")
            val keyAlias = System.getenv("DEBUG_KEY_ALIAS")
            val keyPassword = System.getenv("DEBUG_KEY_PASSWORD")
            if (storeFilePath != null && storePassword != null && keyAlias != null && keyPassword != null) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:$composeBomVersion")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.animation:animation-core")
    implementation("androidx.activity:activity-compose:$activityComposeVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
    implementation("androidx.core:core-ktx:$coreKtxVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    // Material Components for View-based themes
    implementation("com.google.android.material:material:1.12.0")

    // Media3 ExoPlayer for video orb
    implementation("androidx.media3:media3-exoplayer:1.5.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.compose.ui:ui-test-manifest")
}