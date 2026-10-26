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
        // Must grow with every CI build or Android refuses to install it as an update. The 1100 keeps
        // CI builds above every earlier build: the old repository's runs (from 1), this repository's
        // first runs (1000 + run) and the hand-built 1.0.1050 / 1.0.1051 shared with the owner.
        versionCode = 1100 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0)
        versionName = "1.0.$versionCode"
        // The OCR model runs on onnxruntime-android; only 64-bit ARM phones are supported, which
        // keeps the APK free of the other three ABIs' native libraries.
        ndk {
            abiFilters += "arm64-v8a"
        }
        // Names shown on the phone; a beta build (below) gets its own, so the two apps installed
        // side by side (launcher, accessibility settings) are told apart.
        manifestPlaceholders["appLabel"] = "Clu"
        manifestPlaceholders["a11yLabel"] = "Clu: زر الصوت واللمس الاختياري"
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
            // PR validation is signed by the normal debug key, not the user's
            // release keystore. Give it its own app id so it can be tested
            // alongside a signed production install without uninstalling it.
            if (System.getenv("CI_BETA_BUILD") == "true") {
                applicationIdSuffix = ".beta"
                versionNameSuffix = "-beta"
                manifestPlaceholders["appLabel"] = "Clu beta"
                manifestPlaceholders["a11yLabel"] = "Clu beta: زر الصوت واللمس الاختياري"
            }
        }
        // The build that is shared as Clu.apk, signed like the debug build (the fixed key when there
        // is one) so it installs over it. Not shrunk: the owner wants the whole app, and R8 could
        // break something only a phone would show. Turn both on (rules in proguard-rules.pro) to
        // get the 27.7 MB build instead of about 34 MB.
        release {
            if (System.getenv("CI_BETA_BUILD") == "true") {
                applicationIdSuffix = ".beta"
                versionNameSuffix = "-beta"
                manifestPlaceholders["appLabel"] = "Clu beta"
                manifestPlaceholders["a11yLabel"] = "Clu beta: زر الصوت واللمس الاختياري"
            }
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (cluKeystore != null) signingConfigs.getByName("clu") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // The APK is shared as a file, so its download size is what counts: compressed native
        // libraries (onnxruntime 33 MB, ML Kit 11 MB stored as-is by default) make it about half.
        // Android unpacks them once at install time.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST")
        }
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.all {
            it.maxHeapSize = "1g"
            // The OCR tests print per-item output and timing; show it in the Gradle log.
            it.testLogging {
                events("passed", "failed", "skipped")
                showStandardStreams = true
            }
        }
    }
}

// onnxruntime-android and the desktop onnxruntime jar contain the same ai.onnxruntime classes, but
// the Android build loads its native library differently and cannot run on a JVM. The app compiles
// and ships against the Android artifact; local unit tests get the desktop jar instead, so the
// Android artifact is kept off every unit-test classpath.
configurations.matching { it.name.contains("UnitTest") }.configureEach {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Claude is called with plain HttpURLConnection + org.json (see ClaudeApi), like Gemini: the
    // Anthropic SDK brought Jackson, OkHttp, Apache HttpClient and more for a single request.
    // Arabic OCR (PP-OCRv5 recogniser). Pinned to 1.30.0; the desktop jar used by unit tests must match.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    // Bundled Latin OCR is ready offline on first launch (no Google Play download).
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests, so bring the real one.
    testImplementation("org.json:json:20240303")
    // Desktop onnxruntime so the OCR pipeline can run in local unit tests (see the exclude above).
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.30.0")
}
