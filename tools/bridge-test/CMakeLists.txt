# Builds the JNI bridge for the machine it runs on (not Android), so the same
# C++ that ships in the APK can be exercised from a plain JVM. See run.sh.
cmake_minimum_required(VERSION 3.22)
project(bridge-test LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SRC ${CMAKE_CURRENT_LIST_DIR}/../../engines/llm-llamacpp/src/main/cpp)
add_subdirectory(${SRC}/llama.cpp build-llama)

add_library(modulai-llama SHARED ${SRC}/modulai_llama.cpp)
target_include_directories(modulai-llama PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/stub
    ${JNI_INCLUDE} ${JNI_INCLUDE}/linux
    ${SRC}/llama.cpp/common ${SRC}/llama.cpp/include ${SRC}/llama.cpp/ggml/include)
target_link_libraries(modulai-llama llama llama-common)
