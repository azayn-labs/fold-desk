import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: CI passes the key through environment variables (GitHub
// secrets); locally it can come from keystore.properties. Neither is committed.
// Without either, release builds fall back to the debug key (personal builds).
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(env: String, prop: String): String? = System.getenv(env) ?: keystoreProps.getProperty(prop)
val releaseKeystore = signingValue("FOLDDESK_KEYSTORE_FILE", "storeFile")

// Version from the release tag in CI (e.g. v0.2.0), else the defaults below.
val releaseVersion = System.getenv("FOLDDESK_VERSION_NAME")
val releaseVersionCode = System.getenv("FOLDDESK_VERSION_CODE")?.toIntOrNull()

android {
    namespace = "dev.home.folddesk"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.home.folddesk"
        minSdk = 34
        targetSdk = 36
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersion ?: "0.1.0"
    }

    // GitHub: the free APK on the releases page. Play: the paid store version, under
    // its own package so the two can't be mixed up, with Play's required disclosures.
    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "false")
        }
        create("play") {
            dimension = "store"
            applicationId = "com.azayn.folddesk"
            buildConfigField("boolean", "PLAY_STORE", "true")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = signingValue("FOLDDESK_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("FOLDDESK_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("FOLDDESK_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Build with JDK 21 on any machine (found locally or downloaded by Gradle).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // ActivityOptions.setLaunchWindowingMode is a hidden API; this lifts the restriction.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
