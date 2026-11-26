cmake_minimum_required(VERSION 3.22.1)

project(modulai-sd LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# AGP always compiles with -g; drop the symbols at link time or the APK grows by >100 MB.
add_link_options(-Wl,--strip-all)

# The app also ships llama.cpp with its own, different ggml as shared libraries. This copy
# is linked statically and kept out of the dynamic symbol table so the two never meet.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(SD_SRC ${CMAKE_CURRENT_LIST_DIR}/stable-diffusion.cpp)
add_subdirectory(${SD_SRC} build-sd EXCLUDE_FROM_ALL)

add_library(modulai-sd SHARED modulai_sd.cpp)
target_link_libraries(modulai-sd PRIVATE stable-diffusion android log)
target_link_options(modulai-sd PRIVATE -Wl,--exclude-libs,ALL)
