import java.util.Properties

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

fun setting(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() }

val supabaseUrl = setting("SUPABASE_URL").orEmpty()
val supabaseAnonKey = setting("SUPABASE_ANON_KEY").orEmpty()
val keystorePath = setting("QANNAS_KEYSTORE")

val releaseRequested = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (releaseRequested) {
    require(supabaseUrl.startsWith("https://")) { "SUPABASE_URL must be set (https) for a release build" }
    require(supabaseAnonKey.isNotEmpty()) { "SUPABASE_ANON_KEY must be set for a release build" }
    require(keystorePath != null) { "QANNAS_KEYSTORE must be set for a release build" }
}

android {
    namespace = "app.qannas"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.qannas.driver"
        minSdk = 26
        targetSdk = 36
        versionCode = (setting("QANNAS_VERSION_CODE") ?: "1").toInt()
        versionName = setting("QANNAS_VERSION_NAME") ?: "1.0.0"

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = setting("QANNAS_KEYSTORE_PASSWORD")
                keyAlias = setting("QANNAS_KEY_ALIAS")
                keyPassword = setting("QANNAS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    implementation(libs.osmdroid)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
