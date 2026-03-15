import java.io.FileInputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties

// Create a variable called keystorePropertiesFile, and initialize it to your
// keystore.properties file, in the rootProject folder.
val keystorePropertiesFile = rootProject.file("keystore.properties")

// Initialize a new Properties() object called keystoreProperties.
val keystoreProperties = Properties()

// Load your keystore.properties file into the keystoreProperties object.
keystoreProperties.load(FileInputStream(keystorePropertiesFile))

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
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file("remote_explorer_keystore.jks")
            storePassword = keystoreProperties["storePassword"] as String
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
            signingConfig = signingConfigs.getByName("release")
            ndk {
                debugSymbolLevel = "full"
                //noinspection ChromeOsAbiSupport
                abiFilters += "arm64-v8a"
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

sentry {
    org.set("kj")
    projectName.set("remote-explorer")

    // this will upload your source code to Sentry to show it as part of the stack traces
    // disable if you don't want to expose your sources
    includeSourceContext.set(true)
    ignoredBuildTypes.set(setOf("debug"))
}
