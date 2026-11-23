# Builds the whisper JNI bridge for the machine it runs on (not Android), so the same C++
# that ships in the APK can be exercised from a plain JVM. See run.sh.
cmake_minimum_required(VERSION 3.22)
project(audio-test LANGUAGES C CXX)

set(MODULAI_EXTRA_INCLUDES ${CMAKE_CURRENT_LIST_DIR}/stub ${JNI_INCLUDE} ${JNI_INCLUDE}/linux)
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../engines/audio/src/main/cpp build-audio)
