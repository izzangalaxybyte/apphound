import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// API URL and token come from the repo's local .env (written by scripts/deploy.sh), or from the
// environment in CI. Neither is ever committed.
val dotenv: Map<String, String> = rootProject.file("../.env").takeIf { it.exists() }
    ?.readLines()
    ?.filter { it.isNotBlank() && !it.trimStart().startsWith("#") && "=" in it }
    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    .orEmpty()
fun env(key: String): String = System.getenv(key) ?: dotenv[key] ?: ""

// Release signing: android/keystore.properties (git-ignored) with storeFile, storePassword, keyAlias.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toInt() ?: 1

android {
    namespace = "com.thegalaxybyte.apphound"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thegalaxybyte.apphound"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.1.$appVersionCode"
        buildConfigField("String", "API_URL", "\"${env("APPHOUND_API_URL").trimEnd('/')}\"")
        buildConfigField("String", "API_TOKEN", "\"${env("APPHOUND_API_TOKEN")}\"")
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storeType = "pkcs12"
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.coil.compose)

    debugImplementation(libs.androidx.ui.tooling)
}
