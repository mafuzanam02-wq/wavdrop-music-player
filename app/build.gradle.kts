import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// ── Release signing (WD-06) ───────────────────────────────────────────────────
// Secrets NEVER live in Git. Supply them via one of:
//   • keystore.properties at the repo root (gitignored; see keystore.properties.example)
//   • the matching WAVDROP_UPLOAD_* environment variables (CI encrypted secrets)
// The file wins over env vars. Three states are recognised:
//   • NONE supplied     → release build stays UNSIGNED (safe local default).
//   • ALL four supplied → release signing is configured.
//   • SOME but not all  → fail configuration with a clear GradleException naming
//                         the MISSING property names (never the supplied values).
// There is no fallback to the debug key for release.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

fun resolveSigningValue(propertyKey: String, envKey: String): String? =
    (keystoreProperties.getProperty(propertyKey) ?: System.getenv(envKey))
        ?.takeIf { it.isNotBlank() }

// Property name → resolved value. Order defines the reporting order for missing keys.
val releaseSigningValues: Map<String, String?> = linkedMapOf(
    "storeFile" to resolveSigningValue("storeFile", "WAVDROP_UPLOAD_STORE_FILE"),
    "storePassword" to resolveSigningValue("storePassword", "WAVDROP_UPLOAD_STORE_PASSWORD"),
    "keyAlias" to resolveSigningValue("keyAlias", "WAVDROP_UPLOAD_KEY_ALIAS"),
    "keyPassword" to resolveSigningValue("keyPassword", "WAVDROP_UPLOAD_KEY_PASSWORD"),
)

val suppliedSigningKeys = releaseSigningValues.filterValues { it != null }.keys
val missingSigningKeys = releaseSigningValues.filterValues { it == null }.keys

// Partial configuration is a misconfiguration — refuse rather than silently
// producing an unsigned artifact the developer believes is signed. The message
// names only which properties are MISSING; supplied secrets are never referenced.
if (suppliedSigningKeys.isNotEmpty() && missingSigningKeys.isNotEmpty()) {
    throw GradleException(
        "Incomplete release signing configuration: missing " +
            missingSigningKeys.joinToString(", ") + ". " +
            "Supply all of storeFile, storePassword, keyAlias, keyPassword via " +
            "keystore.properties or the matching WAVDROP_UPLOAD_* environment " +
            "variables, or supply none to build an unsigned release. " +
            "(Values are intentionally not echoed.)",
    )
}

val hasReleaseSigningConfig = missingSigningKeys.isEmpty()
val releaseStoreFilePath = releaseSigningValues["storeFile"]
val releaseStorePassword = releaseSigningValues["storePassword"]
val releaseKeyAlias = releaseSigningValues["keyAlias"]
val releaseKeyPassword = releaseSigningValues["keyPassword"]

// Fail early and clearly if the keystore file is absent. Only the file NAME is
// surfaced, not the full (potentially sensitive) path. Passwords are never
// validated at configuration time.
val releaseStoreFile = if (hasReleaseSigningConfig) {
    rootProject.file(releaseStoreFilePath!!).also { file ->
        if (!file.exists()) {
            throw GradleException(
                "Release keystore file not found: '${file.name}'. Check the storeFile " +
                    "path in keystore.properties / WAVDROP_UPLOAD_STORE_FILE. " +
                    "The keystore must live outside version control.",
            )
        }
    }
} else {
    null
}

android {
    namespace = "com.launchpoint.wavdrop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.launchpoint.wavdrop"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.0-beta9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Created only when real credentials are supplied outside Git. Absent
        // otherwise, so the release build produces an unsigned artifact.
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Attach the release signing config only when it exists. When it does
            // not, the build stays unsigned rather than silently using the debug key.
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    testOptions {
        unitTests {
            // Makes Android API stubs return safe defaults (0/null/false) in JVM unit tests
            // instead of throwing RuntimeException. Required for Log.d and similar calls.
            isReturnDefaultValues = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    sourceSets {
        // Make exported Room schema JSON files available to instrumentation tests
        // so MigrationTestHelper can validate migrations against the known schema.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    kapt {
        correctErrorTypes = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    // Real org.json for JVM unit tests: the Android stubs return null/0 under
    // isReturnDefaultValues, which would silently break exporter round-trip tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
