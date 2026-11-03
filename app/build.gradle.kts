import org.gradle.kotlin.dsl.withGroovyBuilder

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.pystudio.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pystudio.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // arm64 covers practically every 4-8 GB phone; x86_64 is for emulators.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK is directly installable.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1"
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Embedded CPython (Chaquopy). The Chaquopy Gradle DSL moved from `android.defaultConfig.python {}`
// to a top-level `chaquopy {}` block between plugin versions, so the same settings are applied to
// whichever of the two exists. That keeps the build working if you bump the plugin version.
// Build-time requirement: Python 3.8+ on PATH (python3.11 recommended), used only to fetch wheels.
// ---------------------------------------------------------------------------------------------
val pyRequirements = listOf(
    "pip==24.2",           // runtime package manager (pip install / uninstall inside the app)
    "jedi==0.19.1",        // code completion, the engine behind python-lsp-server
    "pyflakes==3.2.0",     // linting
    "pycodestyle==2.12.1", // style diagnostics
    "autopep8==2.3.1"      // code formatting
)
val pyExtract = arrayOf("pip", "jedi", "parso") // packages that read their own data files from disk

fun configureChaquopy(target: Any) {
    target.withGroovyBuilder {
        setProperty("version", "3.11")
        "pip" { pyRequirements.forEach { req -> "install"(req) } }
        try {
            "extractPackages"(*pyExtract)
        } catch (e: Exception) {
            logger.warn("Chaquopy: extractPackages is not available (${e.message})")
        }
    }
}

val chaquopyExtension = extensions.findByName("chaquopy")
if (chaquopyExtension != null) {
    chaquopyExtension.withGroovyBuilder { "defaultConfig" { configureChaquopy(delegate) } }
} else {
    android.defaultConfig.withGroovyBuilder { "python" { configureChaquopy(delegate) } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}
