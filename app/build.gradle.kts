plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI decodes the signing key from a GitHub secret into this file. With a fixed key every build
// has the same signature, so a new version installs over the old one and keeps the settings.
// Without it (local builds, forks) the default debug key is used.
val cluKeystore: File? = System.getenv("CLU_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.clu.quickanswer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clu.quickanswer"
        minSdk = 26
        targetSdk = 35
        // Must grow with every CI build or Android refuses to install it as an update.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.$versionCode"
    }

    signingConfigs {
        if (cluKeystore != null) {
            create("clu") {
                storeFile = cluKeystore
                storeType = "pkcs12"
                // The keystore file itself is the secret (a GitHub secret); this password only
                // satisfies the keystore format.
                storePassword = "clu-signing"
                keyAlias = "clu"
                keyPassword = "clu-signing"
            }
        }
    }

    buildTypes {
        debug {
            if (cluKeystore != null) signingConfig = signingConfigs.getByName("clu")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.anthropic:anthropic-java:2.34.0")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests, so bring the real one.
    testImplementation("org.json:json:20240303")
}
