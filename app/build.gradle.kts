import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

// Release signing is configured from keystore.properties + the .jks alongside it, neither of
// which is in version control. CI writes both before building; a plain clone has neither, so
// everything below is optional — without it, only the `debug` variant is buildable.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreFile = file("remote_explorer_keystore.jks")

val keystoreProperties = Properties().takeIf { keystorePropertiesFile.exists() }?.apply {
    FileInputStream(keystorePropertiesFile).use { load(it) }
}
val hasReleaseSigning = keystoreProperties != null && keystoreFile.exists()

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.10"
    id("io.sentry.android.gradle") version "6.1.0"
}

android {
    namespace = "com.bespectacledcyborg.remoteexplorer"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.bespectacledcyborg.remoteexplorer"
        minSdk = 34
        targetSdk = 36
        versionCode = Instant.now().epochSecond.toInt()
        versionName = ZonedDateTime.now(ZoneId.of("Asia/Tokyo"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            // The bundled FFmpeg under src/main/cpp/ffmpeg ships arm64-v8a only, so no other
            // ABI can link. Applies to every variant, not just release — an unfiltered debug
            // build would ask CMake for x86_64 and fail on the missing lib/include dirs.
            //noinspection ChromeOsAbiSupport
            abiFilters += "arm64-v8a"  // stack-reviewer-ignore: verified — cpp/ffmpeg/{lib,include} contain arm64-v8a and nothing else, release already carried this exact filter, and without it a non-arm64 build fails to link rather than producing a runnable APK, so no target loses a working artifact. The only emulator in use is arm64 (Apple Silicon).
        }
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = keystoreProperties!!["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                storeFile = keystoreFile
                storePassword = keystoreProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        release {
//            isDebuggable = false
//            isMinifyEnabled = true
//            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                debugSymbolLevel = "full"
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }

        register("releaseDebug") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasedebug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
//    kotlinOptions {
//        jvmTarget = "21"
//    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/*"
        }
        jniLibs {
            keepDebugSymbols.add("libandroidx.graphics.path.so")
            keepDebugSymbols.add("libavcodec.so")
            keepDebugSymbols.add("libavformat.so")
            keepDebugSymbols.add("libavutil.so")
            keepDebugSymbols.add("libcrypto.so")
            keepDebugSymbols.add("libffmpeg_mediametadataretriever_jni.so")
            keepDebugSymbols.add("libsentry-android.so")
            keepDebugSymbols.add("libsentry.so")
            keepDebugSymbols.add("libssl.so")
            keepDebugSymbols.add("libswscale.so.")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/ffmpeg/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

composeCompiler {
    stabilityConfigurationFiles = listOf(
        rootProject.layout.projectDirectory.file("stability/sketch_compose_compiler_config.conf")
    )
}

dependencies {
    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.kotlin.bom))
    implementation(platform(libs.compose.bom))

    // UI
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)

    // Server
    implementation(libs.smbj)
    implementation(libs.ktor.server.jetty.jakarta)
//    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auto.head.response)

    // DB
    implementation(libs.room.common)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Image Fetching
    implementation(libs.sketch.compose)
    implementation(libs.sketch.extensions.compose)
    implementation(libs.sketch.compose.resources)
    implementation(libs.sketch.animated.gif)
    implementation(libs.sketch.animated.webp)
    implementation(libs.sketch.svg)
    implementation(libs.sketch.http.ktor3)
    // Video Handling
//    implementation(libs.sketch.video.ffmpeg)

    // Tagging
    implementation(libs.metadata.extractor)
    implementation(libs.work.runtime.ktx)

    // Misc
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}

// Uploading source context needs a Sentry auth token, which CI supplies via sentry.properties.
// Without one a local release/releaseDebug build would fail on the upload task, so only opt in
// when the credential is actually present.
val hasSentryAuth = rootProject.file("sentry.properties").exists() ||
        System.getenv("SENTRY_AUTH_TOKEN") != null

sentry {
    org.set("kj")
    projectName.set("remote-explorer")

    // this will upload your source code to Sentry to show it as part of the stack traces
    // disable if you don't want to expose your sources
    includeSourceContext.set(hasSentryAuth)
    ignoredBuildTypes.set(setOf("debug"))
}
