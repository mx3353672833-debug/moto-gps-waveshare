import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val repositoryRoot = rootProject.projectDir.resolve("../..").canonicalFile

// Release signing material never lives in the repository. Point
// MOTO_KEYSTORE_PROPERTIES (or -PmotoKeystoreProperties) at a local file with
// storeFile / storePassword / keyAlias / keyPassword.
val keystoreProperties = Properties().apply {
    val path = (findProperty("motoKeystoreProperties") as String?)
        ?: System.getenv("MOTO_KEYSTORE_PROPERTIES")
        ?: rootProject.file("keystore.properties").path
    val file = File(path)
    if (file.isFile) file.inputStream().use(::load)
}

// Optional default gateway baked into a private build. Public builds leave it
// empty so riders configure their own deployment in the app.
val bundledGatewayBaseUrl = (findProperty("motoGatewayBaseUrl") as String?) ?: ""

android {
    namespace = "top.maler.motogps"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "top.maler.motogps"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BUNDLED_GATEWAY_BASE_URL", "\"$bundledGatewayBaseUrl\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DMOTO_REPOSITORY_ROOT=${repositoryRoot.path}")
                cppFlags += "-std=c++17"
            }
        }
    }

    signingConfigs {
        if (keystoreProperties.getProperty("storeFile") != null) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // Unit tests read the same shared fixtures as the iOS and C++ tests.
    sourceSets["test"].resources.srcDirs(
        repositoryRoot.resolve("shared/demo_fixture"),
        repositoryRoot.resolve("platforms/ios/AppTests/Fixtures"),
    )

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    // The bundled city map is copied out of the APK; keep it uncompressed so
    // its length can be compared without decompressing 10 MB.
    androidResources {
        noCompress += "sqlite"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

/**
 * Copies the shared, checked-in contracts the app needs at runtime (golden BLE
 * bytes, the Jinan demo route, the bundled offline map) into generated assets,
 * so the APK never carries a second hand-edited copy of them.
 */
abstract class CopySharedAssets : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val target = outputDir.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        sources.files.forEach { it.copyTo(target.resolve(it.name), overwrite = true) }
    }
}

val copySharedAssets = tasks.register<CopySharedAssets>("copySharedAssets") {
    sources.from(
        repositoryRoot.resolve("shared/protocol/fixtures/ble-navigation-v1.golden.txt"),
        repositoryRoot.resolve("shared/demo_fixture/jinan_big_data_to_inspur.json"),
        repositoryRoot.resolve("shared/demo_fixture/jinan_map_scene_sample.json"),
        repositoryRoot.resolve("shared/offline_map/jinan-v1.sqlite"),
        repositoryRoot.resolve("shared/offline_map/ATTRIBUTION.txt"),
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copySharedAssets, CopySharedAssets::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
