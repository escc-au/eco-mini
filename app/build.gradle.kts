import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional: set eco.signing.properties=/path/to/keystore.properties
// in ~/.gradle/gradle.properties. Without it the release APK is unsigned (as F-Droid wants).
val signingProps: Properties? = (findProperty("eco.signing.properties") as String?)
    ?.let { file(it) }
    ?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "org.escc.ecomini"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "org.escc.ecomini"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    signingConfigs {
        if (signingProps != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs {
            // ggml loads its backends with dlopen from nativeLibraryDir, so they must be extracted.
            useLegacyPackaging = true
            // Link-time stub only: at runtime the phone's own vendor libOpenCL.so is used.
            excludes += "**/libOpenCL.so"
        }
    }

    androidResources { noCompress += "gguf" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // No Google-encrypted dependency metadata blob in the APK (F-Droid requirement).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // arm64-v8a only on purpose: the app needs a recent phone with 8 GB of RAM.
        // ObsoleteSdkInt: aapt needs adaptive icons in mipmap-anydpi-v26 even with minSdk 31.
        disable += listOf("ObsoleteSdkInt", "ChromeOsAbiSupport", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

// Unit tests run the real C++ vector search: build it for the host and hand its path to the tests.
val hostSearchLib = layout.buildDirectory.file("host/libecosearch.so")
val buildHostSearchLib by tasks.registering(Exec::class) {
    val src = file("src/main/cpp/vecsearch.cpp")
    val javaHome = System.getProperty("java.home")
    inputs.file(src)
    outputs.file(hostSearchLib)
    doFirst { hostSearchLib.get().asFile.parentFile.mkdirs() }
    commandLine(
        "c++", "-O2", "-std=c++17", "-shared", "-fPIC",
        "-I$javaHome/include", "-I$javaHome/include/linux",
        src.absolutePath, "-o", hostSearchLib.get().asFile.absolutePath,
    )
}
tasks.withType<Test>().configureEach {
    dependsOn(buildHostSearchLib)
    systemProperty("eco.hostSearchLib", hostSearchLib.get().asFile.absolutePath)
    systemProperty("eco.projectDir", projectDir.absolutePath)
}
