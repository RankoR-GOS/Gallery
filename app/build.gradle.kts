import com.android.build.api.dsl.ApplicationBuildType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kspAndroid)
    alias(libs.plugins.roomPlugin)
    alias(libs.plugins.hiltAndroid)
    alias(libs.plugins.baselineProfilePlugin)
    alias(libs.plugins.kotlin.compose.compiler)
    id("gos-string-overrides")
    id("kotlin-parcelize")
    alias(libs.plugins.kotlinSerialization)
}

val baseApplicationId = providers
    .gradleProperty("baseApplicationId")
    .get()

android {
    namespace = "com.dot.gallery"
    compileSdk = 37

    defaultConfig {
        applicationId = baseApplicationId
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        base.archivesName.set("Gallery")
    }

    lint.baseline = file("lint-baseline.xml")

    signingConfigs {
        create("release") {
            storeFile = file("release_key.jks")
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        fun ApplicationBuildType.configureProvider() {
            val authority = "$baseApplicationId${applicationIdSuffix.orEmpty()}.media_provider"
            manifestPlaceholders["appProvider"] = authority
            buildConfigField("String", "CONTENT_AUTHORITY", "\"$authority\"")
        }

        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            configureProvider()
            buildConfigField("Boolean", "ENABLE_INDEXING", "false")
        }
        getByName("release") {
            configureProvider()
            isMinifyEnabled = true
            isShrinkResources = true
            setProguardFiles(
                listOf(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
            )
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("Boolean", "ENABLE_INDEXING", "true")
        }
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".staging"
            versionNameSuffix = "-staging"
            configureProvider()
            buildConfigField("Boolean", "ENABLE_INDEXING", "true")
        }

        // Use to manually check performance with release config, but debug signing
        create("perf") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            signingConfig = signingConfigs.getByName("debug")
            configureProvider()
            buildConfigField("Boolean", "ENABLE_INDEXING", "true")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        noCompress += listOf("json", "onnx", "txt")
    }

    assetPacks += listOf(":ml-models")

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
    }

    sourceSets {
        getByName("main") {
            // For APK builds, include ML assets directly since asset packs are AAB-only
            val isBundleBuild = gradle.startParameter.taskNames.any {
                it.contains("bundle", ignoreCase = true)
            }
            if (!isBundleBuild) {
                assets.srcDirs("src/main/assets", "../ml-models/src/main/assets")
            }
        }

        buildTypes.configureEach {
            getByName(name).manifest.srcFile("src/gos/AndroidManifest.xml")
            getByName(name).res.directories += "src/gos/res"
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

}

room {
    schemaDirectory("$projectDir/schemas/")
}

composeCompiler {
    includeSourceInformation = true
    stabilityConfigurationFiles = listOf(
        rootProject.layout.projectDirectory.file("stability_config.conf")
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xcontext-parameters")
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.process)
    runtimeOnly(libs.androidx.profileinstaller)
    implementation(project(":libs:cropper"))
    implementation(project(":libs:panoramaviewer"))
    "baselineProfile"(project(mapOf("path" to ":baselineprofile")))

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Core - Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.compose.lifecycle.runtime)

    // Compose
    implementation(libs.compose.activity)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.androidx.startup.runtime)

    // Compose - Shimmer
    implementation(libs.compose.shimmer)
    // Compose - Material3
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.androidx.adaptive)
    implementation(libs.androidx.adaptive.layout)
    implementation(libs.androidx.adaptive.navigation)

    // Compose - Accompanists
    implementation(libs.accompanist.permissions)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.accompanist.drawablepainter)

    // Android MDC - Material
    implementation(libs.material)

    // Kotlin - Coroutines
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly(libs.kotlinx.coroutines.android)

    // Kotlin - Immutable Collections
    implementation(libs.kotlinx.collections.immutable)

    implementation(libs.kotlinx.serialization.json)

    // Dagger - Hilt
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.dagger.hilt)
    implementation(libs.androidx.hilt.common)
    implementation(libs.androidx.hilt.work)
    ksp(libs.dagger.hilt.compiler)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // Kotlin Extensions and Coroutines support for Room
    implementation(libs.room.ktx)

    // Coders
    implementation(libs.jxl.coder.coil)
    implementation(libs.avif.coder.coil)

    // Sketch
    implementation(libs.sketch.compose)
    implementation(libs.sketch.view)
    implementation(libs.sketch.animated.gif)
    implementation(libs.sketch.animated.heif)
    implementation(libs.sketch.animated.webp)
    implementation(libs.sketch.extensions.compose)
    implementation(libs.sketch.http.ktor)
    implementation(libs.sketch.svg)
    implementation(libs.sketch.video)

    // Glide
    implementation(libs.glide.compose)
    ksp(libs.glide.ksp)

    // Exo Player
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)

    // Exif Interface
    implementation(libs.androidx.exifinterface)
    implementation(libs.metadata.extractor)

    // Datastore Preferences
    implementation(libs.datastore.prefs)

    // Fuzzy Search
    implementation(libs.fuzzywuzzy.kotlin)

    // Aire
    implementation(libs.aire)

    // Subsampling
    implementation(libs.zoomimage.compose.glide)

    // Splashscreen
    implementation(libs.androidx.core.splashscreen)

    // Jetpack Security
    implementation(libs.androidx.biometric)

    // Composables - Core
    implementation(libs.core)

    // Worker
    implementation(libs.androidx.work.runtime.ktx)

    // Composable - Scrollbar
    implementation(libs.lazycolumnscrollbar)

    // ONNX Runtime (CPU + NNAPI)
    implementation(libs.onnxruntime.android)

    // Haze
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.tooling)
    debugRuntimeOnly(libs.compose.ui.test.manifest)
}
