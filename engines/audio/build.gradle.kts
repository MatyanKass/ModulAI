plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.modulai.engine.audio"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
                targets += "modulai-audio"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":core:runtime"))
    implementation(libs.coroutines.android)
    // Pinned on purpose: this is the last line of releases without the telemetry content provider
    // and network permissions that 1.30 added to the Android package. tools/audio-test runs the
    // desktop build of the same version.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
