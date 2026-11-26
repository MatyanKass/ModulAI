cmake_minimum_required(VERSION 3.22.1)

project(modulai-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP always compiles with -g; drop the symbols at link time or the APK grows by ~150 MB.
add_link_options(-Wl,--strip-all)

if (GGML_OPENCL)
    # The NDK ships neither OpenCL headers nor libOpenCL.so. As in docs/backend/OPENCL.md the
    # Khronos ICD loader is built for Android, but only as a link-time stand-in: it gives
    # libmodulai-ggml-opencl.so a DT_NEEDED on "libOpenCL.so", which on the phone resolves to
    # the vendor driver (/vendor/lib64/libOpenCL.so, reachable thanks to <uses-native-library>
    # in the manifest). The loader itself must never ship: phones do not populate the ICD
    # registry it reads (/system/vendor/Khronos/OpenCL/vendors), and the driver's libOpenCL.so
    # is not an ICD, so a bundled loader would shadow the driver and find no platform. AGP copies every shared library target into its jniLibs output
    # whatever its output directory, so build.gradle.kts excludes libOpenCL.so from
    # packaging and tools/check-apk.sh fails if it ever reaches the APK. It must be a shared
    # library: a static loader would be linked into the backend, which is the same mistake.
    set(OPENCL_HEADERS ${CMAKE_CURRENT_LIST_DIR}/third_party/OpenCL-Headers)
    set(OPENCL_ICD_LOADER_HEADERS_DIR ${OPENCL_HEADERS} CACHE PATH "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/OpenCL-ICD-Loader build-opencl-stub EXCLUDE_FROM_ALL)
    # Drops the loader's symbol version script: vendor drivers export unversioned symbols,
    # and version requirements in the backend would only add a way for dlopen to fail.
    set_target_properties(OpenCL PROPERTIES LINK_FLAGS "")
    # Pre-seeds FindOpenCL, which cannot search outside the NDK sysroot.
    set(OpenCL_INCLUDE_DIR ${OPENCL_HEADERS} CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
endif ()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

if (GGML_OPENCL)
    # ggml_backend_load_all_from_path() loads libggml-opencl.so whenever the file exists, and
    # loading it enumerates platforms and creates a context in the GPU driver, where any
    # error is a GGML_ASSERT that aborts the process. Under a name outside ggml's
    # "libggml-<backend>" pattern, init() loads only the CPU backends and the OpenCL one is
    # loaded by LlamaNative.loadGpuBackend, under GpuCrashGuard, so a driver that kills the
    # process costs the GPU, not the engine.
    set_target_properties(ggml-opencl PROPERTIES OUTPUT_NAME modulai-ggml-opencl)
endif ()

add_library(modulai-llama SHARED modulai_llama.cpp)

target_include_directories(modulai-llama PRIVATE
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include)

target_link_libraries(modulai-llama llama llama-common android log)
