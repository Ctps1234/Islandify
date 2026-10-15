import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}
// Every CI artifact gets a monotonically increasing code, so Android accepts it as an update.
val ciVersionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 10_000 + it }

android {
    namespace = "com.islandify.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.islandify.app"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode ?: 3
        versionName = "1.3"
    }

    buildFeatures { compose = true }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(keystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    // Install THIS (release) build for real speed. Debug builds are 3-5x slower in Compose.
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // CI/local releases use a production key when keystore.properties exists. The
            // debug key remains a convenient fallback for contributors and pull requests.
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.05.01")   // last line that still builds with AGP 8.7 + compileSdk 35
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.materialkolor:material-kolor:2.0.0")   // real Material 3 (HCT) palette from a seed color
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")   // only for the Material 3 XML theme (Theme.Material3.DayNight)
    implementation("androidx.core:core-splashscreen:1.0.1")   // clean launch screen, no white/black flash
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")   // pre-compiles Compose on install
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
