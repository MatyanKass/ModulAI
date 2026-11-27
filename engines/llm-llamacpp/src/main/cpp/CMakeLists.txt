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

if (GGML_HEXAGON)
    # Upstream builds the DSP-side skels (libggml-htp-v<arch>.so) in nested CMake projects
    # (ExternalProject) that use the parent's generator but look for it on PATH. Fail here
    # with the remedy rather than deep inside the build.
    find_program(MODULAI_NINJA ninja)
    if (NOT MODULAI_NINJA)
        message(FATAL_ERROR "The Hexagon skels need ninja on PATH, e.g. the SDK's cmake/<version>/bin.")
    endif ()
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

if (GGML_HEXAGON)
    # Same reason as OpenCL: registering libggml-hexagon.so loads the vendor's libcdsprpc.so
    # and talks to the DSP over FastRPC, so it is renamed out of ggml's auto-load pattern and
    # loaded by LlamaNative.loadNpuBackend under the NPU crash guard.
    set_target_properties(ggml-hexagon PROPERTIES OUTPUT_NAME modulai-ggml-hexagon)

    # The skels are Hexagon code built by upstream's ExternalProjects (one per DSP generation),
    # which AGP neither builds nor packages: it builds and packages CMake library targets only.
    # This target builds them before ggml-hexagon and copies them to MODULAI_HTP_SKEL_DIR, which
    # build.gradle.kts adds to the APK's assets (see there for why not to jniLibs).
    get_target_property(HTP_BINARY_DIR ggml-hexagon BINARY_DIR)
    get_property(HTP_DIR_TARGETS DIRECTORY ${LLAMA_SRC}/ggml/src/ggml-hexagon PROPERTY BUILDSYSTEM_TARGETS)
    set(HTP_SKEL_PROJECTS)
    set(HTP_SKELS)
    foreach (T ${HTP_DIR_TARGETS})
        if (T MATCHES "^htp-(v[0-9]+)$")
            list(APPEND HTP_SKEL_PROJECTS ${T})
            list(APPEND HTP_SKELS ${HTP_BINARY_DIR}/libggml-${T}.so)
        endif ()
    endforeach ()
    if (NOT HTP_SKEL_PROJECTS)
        message(FATAL_ERROR "ggml-hexagon defines no htp-v<arch> skel projects; has upstream changed?")
    endif ()
    message(STATUS "modulai: Hexagon skels ${HTP_SKEL_PROJECTS}")
    if (MODULAI_HTP_SKEL_DIR)
        add_custom_target(modulai-htp-skels
            COMMAND ${CMAKE_COMMAND} -E make_directory ${MODULAI_HTP_SKEL_DIR}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${HTP_SKELS} ${MODULAI_HTP_SKEL_DIR}
            COMMENT "Copying Hexagon skels to ${MODULAI_HTP_SKEL_DIR}"
            VERBATIM)
        add_dependencies(modulai-htp-skels ${HTP_SKEL_PROJECTS})
        add_dependencies(ggml-hexagon modulai-htp-skels)
    endif ()
endif ()

add_library(modulai-llama SHARED modulai_llama.cpp)

target_include_directories(modulai-llama PRIVATE
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include)

target_link_libraries(modulai-llama llama llama-common android log)
