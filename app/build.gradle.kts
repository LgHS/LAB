import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Réglages privés hors git : local.properties (en CI : variables d'environnement)
val local = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun setting(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: local.getProperty(key)?.takeIf { it.isNotBlank() }

// Version : passée par la CI depuis le tag (v0.4.0 → -PappVersion=0.4.0), sinon valeur par défaut.
// versionCode = majeur×10000 + mineur×100 + patch : toujours croissant d'une release à l'autre,
// condition pour qu'Android accepte la mise à jour. Mineur et patch < 100, sinon collision
// (0.3.100 donnerait le même code que 0.4.0).
val releaseVersion = findProperty("appVersion") as String?
val appVersion = releaseVersion ?: "0.3.1"
val appVersionCode = Regex("""(\d+)\.(\d{1,2})\.(\d{1,2})""").matchEntire(appVersion)
    ?.destructured?.let { (major, minor, patch) -> major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt() }
    ?: error("appVersion « $appVersion » invalide : attendu X.Y.Z (tag vX.Y.Z), mineur et patch < 100")

// Clé de signature release : la même pour tous les builds (CI et local), sinon Android refuse
// d'installer une mise à jour par-dessus. Absente → clé debug (dev local uniquement).
val releaseStoreFile = setting("RELEASE_STORE_FILE", "release.storeFile")

android {
    namespace = "be.lghs.lab"
    compileSdk = 36

    defaultConfig {
        applicationId = "be.lghs.lab"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        // Affiché à l'écran : « -dev » distingue un build local d'une release CI
        versionName = if (releaseVersion != null) appVersion else "$appVersion-dev"

        // -Ppreview : s'installe à côté de l'app de la box (be.lghs.lab.preview) pour une capture
        // d'écran, sans toucher à l'app en place ni à sa mise à jour automatique
        if (findProperty("preview") != null) {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }

        fun secret(name: String) = "\"${local.getProperty(name, "")}\""
        buildConfigField("String", "TEC_API_KEY", secret("tec.apiKey"))
        buildConfigField("String", "CAMERA_PORTE_URL", secret("camera.porte"))
        buildConfigField("String", "CAMERA_SAS_URL", secret("camera.sas"))
        buildConfigField("String", "NUKI_TOKEN", secret("nuki.token"))
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = setting("RELEASE_STORE_PASSWORD", "release.storePassword")
                keyAlias = setting("RELEASE_KEY_ALIAS", "release.keyAlias")
                keyPassword = setting("RELEASE_KEY_PASSWORD", "release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    testImplementation("org.json:json:20240303") // org.json réel (celui d'android.jar est un bouchon)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
}
