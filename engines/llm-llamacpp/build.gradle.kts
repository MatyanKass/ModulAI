plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.modulai.engine.llamacpp"
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
                    // Shared libs + dlopen'ed backends let ggml ship one CPU library per
                    // ARM feature level and pick the best one on the device at runtime.
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    // Optional GPU backend (ggml/src/ggml-opencl, docs/backend/OPENCL.md),
                    // built as libmodulai-ggml-opencl.so; see CMakeLists.txt for the name.
                    // - Kernels are embedded: an app has no directory to read .cl files from.
                    // - Adreno kernels: the fast matmul paths for Adreno 7xx/8xx. They also make
                    //   the backend drop every non-Adreno GPU, which is intended: upstream
                    //   supports nothing else on phones, and Mali/PowerVR stay on CPU.
                    // - No Adreno binary kernel library: it targets Snapdragon X2 laptops only
                    //   and is a separate proprietary download.
                    // - Target OpenCL 3.0, the level Adreno 7xx/8xx drivers implement. The
                    //   library binds every symbol at load time, so on an older driver dlopen
                    //   fails cleanly and the engine stays on CPU.
                    "-DGGML_OPENCL=ON",
                    "-DGGML_OPENCL_EMBED_KERNELS=ON",
                    "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON",
                    "-DGGML_OPENCL_USE_ADRENO_BIN_KERNELS=OFF",
                    "-DGGML_OPENCL_TARGET_VERSION=300",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_OPENSSL=OFF",
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
    packaging {
        // The Khronos ICD loader is built only to link against (CMakeLists.txt); shipped, it
        // would be found before the vendor's libOpenCL.so and the GPU would never work.
        jniLibs.excludes += "**/libOpenCL.so"
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
    testImplementation(libs.junit)
}
