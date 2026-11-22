cmake_minimum_required(VERSION 3.22.1)

project(modulai-llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP always compiles with -g; drop the symbols at link time or the APK grows by ~150 MB.
add_link_options(-Wl,--strip-all)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(modulai-llama SHARED modulai_llama.cpp)

target_include_directories(modulai-llama PRIVATE
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include)

target_link_libraries(modulai-llama llama llama-common android log)
