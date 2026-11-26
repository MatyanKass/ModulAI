plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.modulai.engine.imagesdcpp"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // Only the bridge library is wanted; sd.cpp and ggml are linked into it.
                targets += "modulai-sd"
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DGGML_NATIVE=OFF",
                    // A static library cannot pick a CPU variant at runtime the way the
                    // llama.cpp module does, so one baseline is fixed: every ARMv8.2 core
                    // with dot product and half-float arithmetic (Cortex-A55/A75 and newer).
                    // SdNative.cpuSupported() refuses older processors.
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DGGML_OPENMP=OFF",
                    "-DSD_BUILD_EXAMPLES=OFF",
                    "-DSD_WEBP=OFF",
                    "-DSD_WEBM=OFF",
                )
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
}
