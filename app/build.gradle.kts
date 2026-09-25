import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "be.lghs.lab"
    compileSdk = 36

    defaultConfig {
        applicationId = "be.lghs.lab"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.3.0"

        // Secrets hors git : local.properties
        val local = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        fun secret(name: String) = "\"${local.getProperty(name, "")}\""
        buildConfigField("String", "TEC_API_KEY", secret("tec.apiKey"))
        buildConfigField("String", "CAMERA_PORTE_URL", secret("camera.porte"))
        buildConfigField("String", "CAMERA_SAS_URL", secret("camera.sas"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }
    // Valeurs par défaut de la config transports = ce qui est publié sur GitHub
    sourceSets["main"].assets.srcDir("../dashboard")

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.8.0"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("androidx.core:core-ktx:1.16.0")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
}
