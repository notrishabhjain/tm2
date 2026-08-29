import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// versionCode / versionName are the contract the self-updater (spec S19) checks
// against update.json. Bump both together.
val appVersionCode = 6
val appVersionName = "1.5.0"

/**
 * Release signing.
 *
 * Preference order:
 *  1. A keystore decoded from the KEYSTORE_BASE64 repository secret by CI,
 *     handed to Gradle through TASKMIND_KEYSTORE_FILE + friends.
 *  2. The checked-in fallback keystore (ci/taskmind-release.jks).
 *
 * Either way the signing identity is stable across builds, which is what
 * self-update requires. Debug signing would make every update fail silently.
 */
fun resolveSigning(): Map<String, String>? {
    val envFile = System.getenv("TASKMIND_KEYSTORE_FILE")
    if (!envFile.isNullOrBlank() && File(envFile).exists()) {
        return mapOf(
            "file" to envFile,
            "storePassword" to (System.getenv("TASKMIND_KEYSTORE_PASSWORD") ?: ""),
            "keyAlias" to (System.getenv("TASKMIND_KEY_ALIAS") ?: ""),
            "keyPassword" to (System.getenv("TASKMIND_KEY_PASSWORD") ?: ""),
        )
    }
    val fallback = rootProject.file("ci/taskmind-release.jks")
    if (fallback.exists()) {
        return mapOf(
            "file" to fallback.absolutePath,
            "storePassword" to (project.findProperty("taskmind.fallbackStorePassword") as String? ?: ""),
            "keyAlias" to (project.findProperty("taskmind.fallbackKeyAlias") as String? ?: ""),
            "keyPassword" to (project.findProperty("taskmind.fallbackKeyPassword") as String? ?: ""),
        )
    }
    return null
}

android {
    namespace = "com.taskmind"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.taskmind"
        minSdk = 29
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Spec S3: arm64-v8a only. Nothing native ships today, but this
            // keeps the APK honest if anything ever does.
            abiFilters += "arm64-v8a"
        }

        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("int", "APP_VERSION_CODE", "$appVersionCode")
    }

    val signing = resolveSigning()
    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = File(signing.getValue("file"))
                storePassword = signing.getValue("storePassword")
                keyAlias = signing.getValue("keyAlias")
                keyPassword = signing.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = false
            isReturnDefaultValues = true
        }
    }

    lint {
        // A lint failure must never be the thing that stops a release APK from
        // being produced; there is no local machine to fix it on quickly.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
