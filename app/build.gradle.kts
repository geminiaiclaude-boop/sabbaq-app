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
val keystorePath = setting("QANNAS_KEYSTORE")
val releaseStoreFile = keystorePath
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
val versionCodeValue = setting("QANNAS_VERSION_CODE") ?: "7"
val configuredVersionCode = versionCodeValue.toIntOrNull()
    ?: throw GradleException("QANNAS_VERSION_CODE يجب أن يكون عدداً صحيحاً موجباً")
require(configuredVersionCode > 0) { "QANNAS_VERSION_CODE يجب أن يكون عدداً صحيحاً موجباً" }
val configuredVersionName = setting("QANNAS_VERSION_NAME") ?: "1.4.0"
require(configuredVersionName.isNotBlank()) { "QANNAS_VERSION_NAME لا يمكن أن يكون فارغاً" }

android {
    namespace = "app.qannas"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.qannas.driver"
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
            storePassword = setting("QANNAS_KEYSTORE_PASSWORD")
            keyAlias = setting("QANNAS_KEY_ALIAS")
            keyPassword = setting("QANNAS_KEY_PASSWORD")
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
                add("QANNAS_KEYSTORE مطلوب ويجب أن يشير إلى ملف keystore")
            } else {
                if (!releaseStoreFile.isFile) add("QANNAS_KEYSTORE لا يشير إلى ملف موجود")
                if (!releaseStoreFile.canRead()) add("ملف QANNAS_KEYSTORE غير قابل للقراءة")
            }

            listOf(
                "QANNAS_KEYSTORE_PASSWORD" to setting("QANNAS_KEYSTORE_PASSWORD"),
                "QANNAS_KEY_ALIAS" to setting("QANNAS_KEY_ALIAS"),
                "QANNAS_KEY_PASSWORD" to setting("QANNAS_KEY_PASSWORD")
            ).forEach { (name, value) ->
                if (value.isNullOrBlank()) add("$name مطلوب لإصدار release")
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException("فشل فحص ما قبل إصدار release:\n${errors.joinToString("\n") { " - $it" }}")
        }
        try {
            val store = KeyStore.getInstance(releaseStoreFile!!, setting("QANNAS_KEYSTORE_PASSWORD")!!.toCharArray())
            val key = store.getKey(setting("QANNAS_KEY_ALIAS"), setting("QANNAS_KEY_PASSWORD")!!.toCharArray())
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
