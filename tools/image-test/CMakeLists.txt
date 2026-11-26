# Builds the image JNI bridge for the machine it runs on (not Android), so the same
# C++ that ships in the APK can be exercised from a plain JVM. See run.sh.
cmake_minimum_required(VERSION 3.22)
project(image-test LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(SRC ${CMAKE_CURRENT_LIST_DIR}/../../engines/image-sdcpp/src/main/cpp)
add_subdirectory(${SRC}/stable-diffusion.cpp build-sd EXCLUDE_FROM_ALL)

add_library(modulai-sd SHARED ${SRC}/modulai_sd.cpp)
target_include_directories(modulai-sd PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/stub ${JNI_INCLUDE} ${JNI_INCLUDE}/linux)
target_link_libraries(modulai-sd PRIVATE stable-diffusion)
target_link_options(modulai-sd PRIVATE -Wl,--exclude-libs,ALL)
