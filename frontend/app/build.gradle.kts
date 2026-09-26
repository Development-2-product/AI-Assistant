import java.util.Properties
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Endpoints and the debug-only development token come only from the ignored local.properties.
// They are embedded at build time and cannot be changed inside the installed app.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String, default: String = ""): String = localProps.getProperty(name)?.trim() ?: default
/** All Android build configuration, including signing, comes from ignored local.properties. */
fun privateCfg(name: String): String = localProps.getProperty(name)?.trim().orEmpty()

// Outlook (Microsoft Graph) sign-in. From your Azure app registration; see docs/OUTLOOK_SETUP.md.
// Debug and release are signed with different keys, so each has its own signature hash.
//   iamode.msal.clientId=<Application (client) ID>
//   iamode.msal.signatureHash.debug=<base64 hash of iamode-debug.keystore>
//   iamode.msal.signatureHash.release=<base64 hash of ia-mode-release.keystore>
val msalClientId = cfg("iamode.msal.clientId", "")
val msalHashDebug = cfg("iamode.msal.signatureHash.debug", "")
val msalHashRelease = cfg("iamode.msal.signatureHash.release", "")

fun normalisedUrl(value: String): String = if (value.endsWith("/")) value else "$value/"
fun requiredBackendUrl(name: String): String {
    val value = cfg(name)
    if (value.isBlank()) throw GradleException("$name is required in the ignored frontend/local.properties")
    return normalisedUrl(value)
}
val requestsDebugTask = gradle.startParameter.taskNames.any { it.contains("debug", ignoreCase = true) }
val debugBackendUrl = cfg("iamode.backendUrl")
val releaseBackendUrl = cfg("iamode.backendUrl.release")
val devToken = cfg("iamode.devToken", "")
val releaseStoreFile = privateCfg("RELEASE_STORE_FILE")
val releaseStorePassword = privateCfg("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = privateCfg("RELEASE_KEY_ALIAS")
val releaseKeyPassword = privateCfg("RELEASE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all(String::isNotBlank)
val requestsReleaseTask = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }

if (requestsDebugTask && debugBackendUrl.isBlank()) {
    throw GradleException("iamode.backendUrl is required in the ignored frontend/local.properties for debug builds.")
}
val debugHost = runCatching { URI(debugBackendUrl).host }.getOrNull()
if (requestsDebugTask && (debugHost.isNullOrBlank() || !(debugBackendUrl.startsWith("http://", true) || debugBackendUrl.startsWith("https://", true)))) {
    throw GradleException("iamode.backendUrl must be a valid HTTP(S) URL for debug builds.")
}

if (requestsReleaseTask && !hasReleaseSigning) {
    throw GradleException("Release signing credentials are missing. Set RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS and RELEASE_KEY_PASSWORD in ignored frontend/local.properties.")
}
val releaseHost = runCatching { URI(releaseBackendUrl).host }.getOrNull()
val rawIpHost = releaseHost?.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) == true || releaseHost?.contains(":") == true
if (requestsReleaseTask && (releaseBackendUrl.isBlank() || !releaseBackendUrl.startsWith("https://", ignoreCase = true) || releaseHost.isNullOrBlank() || rawIpHost)) {
    throw GradleException("iamode.backendUrl.release must be an HTTPS domain. Release builds never use LAN, HTTP, or raw public-IP endpoints.")
}

val rootFirebaseConfig = file("google-services.json")
if (rootFirebaseConfig.exists()) {
    throw GradleException("Root app/google-services.json is not allowed. Use only src/debug and src/release variant configs.")
}
val hasGoogleServices = sequenceOf(
    file("src/debug/google-services.json"),
    file("src/release/google-services.json"),
).any { it.exists() }
val hasReleaseFirebaseConfig = file("src/release/google-services.json").isFile
val hasDebugFirebaseConfig = file("src/debug/google-services.json").isFile
if (requestsReleaseTask && !hasReleaseFirebaseConfig) {
    throw GradleException("Release Firebase config is required at app/src/release/google-services.json.")
}
if (!hasDebugFirebaseConfig) logger.warn("Debug Firebase config is missing at app/src/debug/google-services.json; debug authentication will use iamode.devToken.")

// Firebase is optional during development: without google-services.json the app
// uses the DEV_API_TOKEN to talk to the backend.
if (hasGoogleServices) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
}

android {
    namespace = "com.iamode.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iamode.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 10
        versionName = "1.9.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                // rootProject.file handles both a relative local path and an absolute Windows path.
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            buildConfigField("String", "BACKEND_URL", "\"${normalisedUrl(debugBackendUrl.ifBlank { "https://example.invalid" })}\"")
            buildConfigField("String", "DEV_API_TOKEN", "\"$devToken\"")
            // Debug normally targets a local AUTH_MODE=dev backend, even when
            // google-services.json is present for release builds.
            buildConfigField("boolean", "USE_FIREBASE_AUTH", "false")
            buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
            buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalHashDebug\"")
            manifestPlaceholders["msalSignatureHash"] = msalHashDebug.ifBlank { "not-configured" }
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release uses Firebase authentication. A production backend must use
            // AUTH_MODE=firebase and the matching Firebase project.
            buildConfigField("String", "BACKEND_URL", "\"${normalisedUrl(releaseBackendUrl)}\"")
            buildConfigField("String", "DEV_API_TOKEN", "\"\"")
            // A release APK must use Firebase; without google-services.json it
            // fails sign-in rather than silently sending a dev credential.
            buildConfigField("boolean", "USE_FIREBASE_AUTH", "true")
            buildConfigField("String", "MSAL_CLIENT_ID", "\"$msalClientId\"")
            buildConfigField("String", "MSAL_SIGNATURE_HASH", "\"$msalHashRelease\"")
            manifestPlaceholders["msalSignatureHash"] = msalHashRelease.ifBlank { "not-configured" }
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

room { schemaDirectory("$projectDir/schemas") }

// Keep generated Android translation resources in sync on every normal build. The extractor is
// deliberately not invoked here: it heuristically rewrites Kotlin source and must stay a reviewed,
// developer-initiated change. Generation is deterministic from the checked-in translation table.
val repositoryRoot = rootProject.projectDir.parentFile
val i18nToolsDir = repositoryRoot.resolve("tools/i18n")
val pythonExecutable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3"
val generateI18n by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Generates Android i18n resources from tools/i18n/translations.json."
    workingDir(repositoryRoot)
    commandLine(pythonExecutable, i18nToolsDir.resolve("generate.py").absolutePath)
    inputs.files(i18nToolsDir.resolve("generate.py"), i18nToolsDir.resolve("translations.json"))
    outputs.files(
        file("src/main/java/com/iamode/app/core/i18n/I18nKeys.kt"),
        file("src/main/res/values/strings_i18n.xml"),
        file("src/main/res/values-te/strings_i18n.xml"),
        file("src/main/res/values-hi/strings_i18n.xml"),
    )
}

tasks.named("preBuild").configure { dependsOn(generateI18n) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite.ktx)

    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime.ktx)

    implementation(libs.retrofit)
    // On-device PDF text extraction for document ranking (first pages only; nothing leaves the phone)
    implementation(libs.pdfbox.android)
    // Outlook / Microsoft 365 sign-in (Microsoft Graph)
    implementation(libs.msal)
    // On-device OCR for scanned PDFs and photos (model delivered by Play services, not bundled in the APK)
    implementation(libs.mlkit.text.recognition)
    // Hindi / Marathi (Devanagari) OCR, used when Latin OCR finds little text
    implementation(libs.mlkit.text.recognition.devanagari)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.play.services.location)
    implementation(libs.play.services.auth)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.crashlytics)
    implementation(libs.glance.appwidget)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
