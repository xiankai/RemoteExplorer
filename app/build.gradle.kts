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

val headCommitTime: Instant = providers.exec {
    commandLine("git", "log", "-1", "--format=%ct")
}.standardOutput.asText.get().trim().toLongOrNull()
    ?.let(Instant::ofEpochSecond)
    ?: error("versionCode is the HEAD commit's timestamp, so the app builds only from a git checkout")

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
    id("io.sentry.android.gradle") version "6.23.0"
}

android {
    namespace = "com.bespectacledcyborg.remoteexplorer"
    compileSdk = 37
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.bespectacledcyborg.remoteexplorer"
        minSdk = 34
        targetSdk = 36
        versionCode = headCommitTime.epochSecond.toInt()
        versionName = ZonedDateTime.ofInstant(headCommitTime, ZoneId.of("Asia/Tokyo"))
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
            manifestPlaceholders["sentryEnvironment"] = "production"
            manifestPlaceholders["sentryDebug"] = "false"
        }
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["sentryEnvironment"] = "debug"
            manifestPlaceholders["sentryDebug"] = "true"
        }

        // Runs the release configuration on a dev machine, side by side with debug. It is
        // never distributed — no CI job builds it — so the two departures below buy local
        // usability at no cost to what ships. What it is actually for is the packaging and
        // native half of release: the 16 KB page-size fix, the ktor/Jetty breakage and an NDK
        // segfault all reproduced only in release, and none of them turns on either setting.
        register("releaseDebug") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasedebug"
            manifestPlaceholders["sentryEnvironment"] = "releasedebug"
            // `run-as` is refused on a non-debuggable package, and it is the only way into the
            // app's private data directory on a Play-image emulator, where `adb root` is not
            // available. Without this, tools/dev-seed-connection.sh cannot write the fixture
            // connection and the variant cannot be driven past the empty Connections screen.
            isDebuggable = true
            // initWith copies release's signing config, which a plain clone does not have —
            // keystore.properties and the .jks live only in CI secrets. An unsigned APK still
            // builds and then fails at install with INSTALL_PARSE_FAILED_NO_CERTIFICATES, so
            // fall back to the debug keystore, which the SDK generates on demand.
            if (!hasReleaseSigning) {  // stack-reviewer-ignore: defined at the top of this file (line 17) and already the guard on both signingConfigs.create("release") and release's own signingConfig assignment, so it is true exactly when the release config was registered. Outside the diff hunk, hence invisible to the review. Verified: signingReport, assembleReleaseDebug, testDebugUnitTest and lintDebug all configure and pass.
                signingConfig = signingConfigs.getByName("debug")
            }
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
    sourceSets {
        // Room's MigrationTestHelper reads the exported schemas through the asset manager, and
        // Robolectric serves the VARIANT's merged assets — a `test` source set entry is simply
        // not seen, which fails as "Cannot find the schema file in the assets folder".
        //
        // Hanging them off `debug` rather than `main` is what keeps ~40 KB of schema JSON out
        // of the sideload release APK: unit tests run against the debug variant, so that is the
        // only variant that needs them.
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
    }
    testOptions {
        unitTests {
            // Robolectric serves the assets above from the merged unit-test resources, which
            // only exist when this is on.
            isIncludeAndroidResources = true
        }
    }
    lint {
        // CI reads findings from the text report; without this only HTML is written, which
        // is useless in a log.
        textReport = true
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
    // Declaring externalNativeBuild makes AGP resolve ndkVersion at CONFIGURATION time, so
    // it auto-downloads the NDK for any task at all — `lintDebug` and `testDebugUnitTest`
    // included, neither of which compiles a line of native code. On a CI runner with no
    // layer cache that is a multi-GB download on every run, for nothing.
    //
    // -Prex.skipNative drops the native build from the configuration entirely. It must NOT
    // be used for anything that produces an installable artifact: the APK would be missing
    // libffmpeg.so and would die inside System.loadLibrary at the first video thumbnail.
    if (!providers.gradleProperty("rex.skipNative").isPresent) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/ffmpeg/CMakeLists.txt")
                version = "3.22.1"
            }
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
    // Pinch and double-tap zoom in the image viewer, sharp at depth by decoding tiles.
    implementation(libs.zoomimage.compose.sketch4)
    // Video Handling
//    implementation(libs.sketch.video.ffmpeg)

    // Tagging
    implementation(libs.metadata.extractor)
    implementation(libs.work.runtime.ktx)

    // Misc
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Room's MigrationTestHelper plus a JVM SQLite, so the migration chain is exercised by
    // `testDebugUnitTest` in CI rather than needing the arm64 emulator CI cannot run.
    testImplementation(libs.room.testing)
    testImplementation(libs.sqlite.bundled)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
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

    includeSourceContext.set(hasSentryAuth)
    ignoredBuildTypes.set(setOf("debug"))

    autoInstallation {
        sentryVersion.set("8.60.0")  // stack-reviewer-ignore: deliberate. Plugin 6.23.0 defaults to SDK 8.58.0; the releaseDebug runtime classpath resolves every io.sentry artifact to 8.60.0 with no split versions, and that build starts cleanly with Sentry auto-init on the Tab S11.
    }
}
