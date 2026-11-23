cmake_minimum_required(VERSION 3.22.1)

project(modulai-audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# The app also ships llama.cpp's shared libggml*.so. This library carries its own static ggml,
# so nothing but the JNI entry points may be exported or the two copies would bind to each other.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)

if(ANDROID)
    # AGP always compiles with -g; drop the symbols at link time.
    add_link_options(-Wl,--strip-all)
    # One static CPU backend means one instruction set. Every 64-bit phone core since the
    # Cortex-A55/A75 generation has these; WhisperEngine refuses to run on anything older.
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

set(WHISPER_SRC ${CMAKE_CURRENT_LIST_DIR}/whisper.cpp)
add_subdirectory(${WHISPER_SRC} build-whisper EXCLUDE_FROM_ALL)

add_library(modulai-audio SHARED ${CMAKE_CURRENT_LIST_DIR}/modulai_audio.cpp)
target_include_directories(modulai-audio PRIVATE
    ${WHISPER_SRC}/include
    ${WHISPER_SRC}/ggml/include
    ${MODULAI_EXTRA_INCLUDES})
target_link_libraries(modulai-audio PRIVATE whisper)
target_link_options(modulai-audio PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
if(ANDROID)
    target_link_libraries(modulai-audio PRIVATE log)
endif()
