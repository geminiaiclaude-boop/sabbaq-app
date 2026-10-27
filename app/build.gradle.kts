import java.net.URI
import java.security.KeyStore
import java.security.PrivateKey
import java.util.Base64
import java.util.Properties
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val publicBackend = Properties().apply {
    rootProject.file("config/public-backend.properties").inputStream().use { load(it) }
}

fun setting(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: publicBackend.getProperty(name)?.takeIf { it.isNotBlank() }

// ---- Automatic, collision-proof versioning (part of the build, independent of git) --------
// Every build stamps its own identity from the build clock, so no two builds can share a
// versionCode and nobody has to remember to bump a number. This lives entirely in the build
// configuration — it does not read git history or any repository metadata — so it behaves the
// same from a full checkout, a shallow CI checkout, or a plain source archive. A manual override
// stays available through the SABBAQ_VERSION_CODE / SABBAQ_VERSION_NAME settings for a pinned
// release, and an optional SABBAQ_BUILD_EPOCH_MS lets CI pass a fixed instant for a reproducible
// build.
const val VERSION_CODE_EPOCH_SECONDS = 1_577_836_800L // 2020-01-01T00:00:00Z
const val VERSION_CODE_FLOOR = 14 // one above the last hand-numbered build (13); never regress below it
const val VERSION_CODE_CEILING = 2_100_000_000L // Android's hard limit on versionCode

val buildEpochMs: Long = setting("SABBAQ_BUILD_EPOCH_MS")?.toLongOrNull() ?: System.currentTimeMillis()

// Whole minutes since 2020: strictly increasing as the clock advances (so a later build always
// outranks an earlier one), unique per build, far above the last hand-numbered code (13), and
// inside Android's ceiling for ~thousands of years.
fun buildVersionCode(): Int =
    ((buildEpochMs / 1000L - VERSION_CODE_EPOCH_SECONDS) / 60L)
        .coerceIn(VERSION_CODE_FLOOR.toLong(), VERSION_CODE_CEILING).toInt()

// yyyyMMdd.HHmm (UTC): a human-readable build stamp, matching the telemetry/admin shape
// [A-Za-z0-9._-] within 32 chars, so every installed build is identifiable without git.
fun buildStamp(): String = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd.HHmm")
    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(buildEpochMs))

fun buildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(character)
        }
    }
    append('"')
}

val supabaseUrl = setting("SUPABASE_URL").orEmpty()
val supabaseAnonKey = setting("SUPABASE_ANON_KEY").orEmpty()
val googleWebClientId = setting("GOOGLE_WEB_CLIENT_ID").orEmpty()
val keystorePath = setting("SABBAQ_KEYSTORE")
val releaseStoreFile = keystorePath
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
// Base marketing version. Bump this for a real release; the build identity below stays unique
// regardless, so two builds of the same base can never collide.
val versionNameBase = "2.0.3"

// Explicit setting wins (manual release control); otherwise the build stamps its own.
val configuredVersionCode = (setting("SABBAQ_VERSION_CODE")?.toIntOrNull() ?: buildVersionCode())
require(configuredVersionCode >= VERSION_CODE_FLOOR) {
    "versionCode $configuredVersionCode أقل من الحد الأدنى $VERSION_CODE_FLOOR؛ قد يرفض أندرويد التحديث"
}
// versionName carries the build stamp so every installed build is identifiable in telemetry and
// support. Kept within the 32-char, [A-Za-z0-9._-] shape the telemetry and admin layers validate.
val configuredVersionName = setting("SABBAQ_VERSION_NAME") ?: "$versionNameBase-${buildStamp()}"
require(configuredVersionName.isNotBlank() && configuredVersionName.length <= 32 &&
    configuredVersionName.matches(Regex("^[A-Za-z0-9._-]+$"))) {
    "versionName غير صالح: '$configuredVersionName'"
}

android {
    namespace = "app.sabbaq"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.sabbaq.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = configuredVersionCode
        versionName = configuredVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SUPABASE_URL", buildConfigString(supabaseUrl))
        buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(supabaseAnonKey))
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", buildConfigString(googleWebClientId))
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "DIRECT_DISTRIBUTION", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "DIRECT_DISTRIBUTION", "false")
        }
    }

    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = it }
            storePassword = setting("SABBAQ_KEYSTORE_PASSWORD")
            keyAlias = setting("SABBAQ_KEY_ALIAS")
            keyPassword = setting("SABBAQ_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
    }
}

kotlin {
    jvmToolchain(17)
}

val verifyReleaseConfiguration by tasks.registering {
    group = "verification"
    description = "يتحقق من إعدادات الخدمة والتوقيع اللازمة قبل إنتاج حزمة release."

    doLast {
        val errors = buildList {
            val parsedSupabaseUrl = runCatching { URI(supabaseUrl) }.getOrNull()
            if (parsedSupabaseUrl?.scheme != "https" || parsedSupabaseUrl.host.isNullOrBlank() ||
                parsedSupabaseUrl.userInfo != null || parsedSupabaseUrl.query != null || parsedSupabaseUrl.fragment != null) {
                add("SUPABASE_URL يجب أن يكون رابط HTTPS كاملاً لإصدار release")
            }
            if (supabaseAnonKey.isBlank()) add("SUPABASE_ANON_KEY مطلوب لإصدار release")
            if (supabaseAnonKey.any { it.code < 0x20 || it.code == 0x7f } || supabaseAnonKey != supabaseAnonKey.trim()) {
                add("SUPABASE_ANON_KEY يحتوي محارف غير صالحة للترويسات")
            }
            val jwtPayload = supabaseAnonKey.split('.').getOrNull(1)?.let {
                runCatching { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }.getOrNull()
            }.orEmpty()
            if (supabaseAnonKey.startsWith("sb_secret_") || Regex("\"role\"\\s*:\\s*\"service_role\"").containsMatchIn(jwtPayload)) {
                add("مفتاح الخدمة الخاص لا يجوز تضمينه في تطبيق Android؛ استخدم anon/publishable فقط")
            }
            if (!Regex("^[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com$").matches(googleWebClientId)) {
                add("GOOGLE_WEB_CLIENT_ID مطلوب بصيغة Web OAuth client ID صحيحة للإصدار")
            }

            if (releaseStoreFile == null) {
                add("SABBAQ_KEYSTORE مطلوب ويجب أن يشير إلى ملف keystore")
            } else {
                if (!releaseStoreFile.isFile) add("SABBAQ_KEYSTORE لا يشير إلى ملف موجود")
                if (!releaseStoreFile.canRead()) add("ملف SABBAQ_KEYSTORE غير قابل للقراءة")
            }

            listOf(
                "SABBAQ_KEYSTORE_PASSWORD" to setting("SABBAQ_KEYSTORE_PASSWORD"),
                "SABBAQ_KEY_ALIAS" to setting("SABBAQ_KEY_ALIAS"),
                "SABBAQ_KEY_PASSWORD" to setting("SABBAQ_KEY_PASSWORD")
            ).forEach { (name, value) ->
                if (value.isNullOrBlank()) add("$name مطلوب لإصدار release")
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException("فشل فحص ما قبل إصدار release:\n${errors.joinToString("\n") { " - $it" }}")
        }
        try {
            val store = KeyStore.getInstance(releaseStoreFile!!, setting("SABBAQ_KEYSTORE_PASSWORD")!!.toCharArray())
            val key = store.getKey(setting("SABBAQ_KEY_ALIAS"), setting("SABBAQ_KEY_PASSWORD")!!.toCharArray())
            if (key !is PrivateKey) throw GradleException("Alias التوقيع لا يحتوي مفتاحاً خاصاً")
        } catch (_: Exception) {
            throw GradleException("تعذر فتح مفتاح التوقيع؛ راجع نوع الملف وكلمات المرور والـ alias دون نشرها")
        }
    }
}

val releaseArtifactTasks = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "signReleaseBundle",
    "validateSigningRelease"
)
tasks.configureEach {
    if (name in releaseArtifactTasks || name.matches(Regex(
            "(assemble|bundle|package|validateSigning)(Direct|Play)Release|sign(Direct|Play)ReleaseBundle"))) {
        dependsOn(verifyReleaseConfiguration)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.okhttp)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.osmdroid)
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
