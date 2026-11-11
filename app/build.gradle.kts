import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    kotlin("kapt")
}

android {
    namespace = "com.bot4vps.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.bot4vps.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 28
        versionName = "1.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // An unconfigured release is unsigned. Signing is opt-in and never creates keys.
    val releaseProperties = Properties().apply {
        providers.environmentVariable("BOT4VPS_SIGNING_PROPERTIES").orNull?.let {
            val path = rootProject.file(it)
            require(path.isFile) { "BOT4VPS_SIGNING_PROPERTIES file does not exist" }
            path.inputStream().use { load(it) }
        }
    }
    fun signingValue(envName: String, property: String): String? =
        providers.environmentVariable(envName).orNull ?: releaseProperties.getProperty(property)
    val releaseStore = signingValue("BOT4VPS_SIGNING_STORE", "storeFile")
    val releaseStorePassword = signingValue("BOT4VPS_SIGNING_STORE_PASSWORD", "storePassword")
    val releaseKeyAlias = signingValue("BOT4VPS_SIGNING_KEY_ALIAS", "keyAlias")
    val releaseKeyPassword = signingValue("BOT4VPS_SIGNING_KEY_PASSWORD", "keyPassword")
    val signingRequested = providers.environmentVariable("BOT4VPS_SIGNING_PROPERTIES").isPresent ||
        listOf(releaseStore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).any { it != null }
    val releaseSigning = if (signingRequested) signingConfigs.create("releaseKey").apply {
        require(!releaseStore.isNullOrBlank() && !releaseStorePassword.isNullOrBlank() &&
            !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
            "Provide the release keystore, passwords and alias through BOT4VPS_SIGNING_*"
        }
        storeFile = rootProject.file(releaseStore!!)
        require(storeFile!!.isFile) { "Release keystore does not exist" }
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
    } else null

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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

    testOptions {
        unitTests.all {
            it.maxHeapSize = "256m"
            it.maxParallelForks = 1
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.biometric:biometric:1.1.0")
    // Camera frames are decoded on device; no external scanner or cloud service.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    kapt("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

kapt {
    correctErrorTypes = true
}
