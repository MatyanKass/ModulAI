#!/bin/sh
# Runs the audio engines on this machine: builds the whisper JNI bridge natively, compiles the
# engine's Kotlin for the desktop JVM, downloads real models and checks recognition, synthesis,
# voice cloning and cancellation end to end.
# Needs: git submodules checked out, cmake, a C++17 compiler, JDK 17+, curl, about 2 GB of disk.
#   OUT=<dir>   build and model cache (default: build/audio-test)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-$ROOT/build/audio-test}

# Some distributions (Alpine) install the JDK without putting javac on PATH.
if [ -z "$JAVA_HOME" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    JAVA_HOME=
    if command -v javac > /dev/null; then
        JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
    else
        for d in /usr/lib/jvm/*; do
            [ -f "$d/include/jni.h" ] && [ -x "$d/bin/javac" ] && JAVA_HOME=$d
        done
    fi
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "No JDK found: install one or set JAVA_HOME" >&2
    exit 1
fi
export JAVA_HOME
mkdir -p "$OUT/models"

sh "$ROOT/tools/audio-test/fetch-models.sh" "$OUT/models"

cmake -S "$ROOT/tools/audio-test" -B "$OUT/native" -DCMAKE_BUILD_TYPE=Release \
    -DJNI_INCLUDE="$JAVA_HOME/include" > "$OUT/cmake.log" 2>&1 \
    || { echo "configure failed, see $OUT/cmake.log" >&2; exit 1; }
cmake --build "$OUT/native" --target modulai-audio -j "$(nproc)" > "$OUT/build.log" 2>&1 \
    || { echo "build failed, see $OUT/build.log" >&2; exit 1; }

"$ROOT/gradlew" -q -p "$ROOT/tools/audio-test" --no-daemon run \
    -Pout="$OUT/gradle" -PnativeDir="$OUT/native/build-audio" -Pmodels="$OUT/models" \
    -Psamples="$ROOT/engines/audio/src/main/cpp/whisper.cpp/samples"
