import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Optional Hexagon NPU backend (ggml/src/ggml-hexagon, docs/backend/snapdragon). It needs
// Qualcomm's Hexagon SDK, which is proprietary and large and is never part of the repository:
// it is built only when the SDK is found, from (first match wins) the Gradle property
// hexagon.sdk.root, the same key in local.properties, or the HEXAGON_SDK_ROOT environment
// variable; hexagon.tools.root / HEXAGON_TOOLS_ROOT optionally names the Hexagon toolchain
// (upstream otherwise reads it from the SDK's hexagon_sdk.json). Without the SDK the module
// builds exactly as before: CPU and OpenCL only.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.reader()?.use { load(it) }
}
fun hexagonSetting(key: String, env: String): String? =
    (providers.gradleProperty(key).orNull ?: localProperties.getProperty(key) ?: providers.environmentVariable(env).orNull)
        ?.takeIf { it.isNotBlank() }

val hexagonSdk: File? = hexagonSetting("hexagon.sdk.root", "HEXAGON_SDK_ROOT")?.let { file(it) }?.also {
    require(File(it, "hexagon_sdk.json").isFile) {
        "Hexagon SDK root $it has no hexagon_sdk.json; unset HEXAGON_SDK_ROOT / hexagon.sdk.root to build without the NPU"
    }
}
val hexagonTools: String? = hexagonSetting("hexagon.tools.root", "HEXAGON_TOOLS_ROOT")
// The DSP-side skels go to the APK's assets, not to jniLibs: they are Hexagon ELF files, and
// in lib/<abi>/ the app's packaging would strip them, which breaks the CRC that upstream's
// htp-swiv.py stores over their loadable segments (the ELF header among them), and the
// 16 KB page-size checks for ARM libraries would see 4 KB-aligned files.
val htpSkelAssets = layout.buildDirectory.dir("generated/htp-skels").get().asFile
// Upstream's skel projects require CMake 3.22.2; AGP needs the exact version installed in the
// SDK (sdkmanager "cmake;3.31.6").
val cmakeVersion = if (hexagonSdk != null) "3.31.6" else "3.22.1"

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
                if (hexagonSdk != null) {
                    // Host library libmodulai-ggml-hexagon.so plus one skel per DSP generation
                    // (v73 and newer), as upstream's arm64-android-snapdragon preset builds them.
                    arguments += listOfNotNull(
                        "-DGGML_HEXAGON=ON",
                        "-DHEXAGON_SDK_ROOT=$hexagonSdk",
                        hexagonTools?.let { "-DHEXAGON_TOOLS_ROOT=$it" },
                        "-DPREBUILT_LIB_DIR=android_aarch64",
                        "-DMODULAI_HTP_SKEL_DIR=${File(htpSkelAssets, "hexagon")}",
                    )
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = cmakeVersion
        }
    }
    if (hexagonSdk != null) {
        sourceSets["main"].assets.srcDir(htpSkelAssets)
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

// CMake writes the skels while building the native code, so every task that merges or packages
// assets waits for that build.
if (hexagonSdk != null) {
    val assetTask = Regex("(?:merge|package)(\\w+?)Assets")
    tasks.configureEach {
        val variant = assetTask.matchEntire(name)?.groupValues?.get(1) ?: return@configureEach
        // Test variants (ReleaseUnitTest, DebugAndroidTest) have no native build of their own.
        dependsOn(tasks.matching { it.name == "externalNativeBuild$variant" })
    }
}

dependencies {
    api(project(":core:runtime"))
    implementation(project(":core:device"))
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
