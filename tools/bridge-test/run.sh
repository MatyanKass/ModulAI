#!/bin/sh
# Builds the JNI bridge for this machine and runs it against two small models.
# Needs: git submodules checked out, cmake, a C++17 compiler, a JDK, curl.
#   OUT=<dir>   build and model cache (default: build/bridge-test)
#   $1          optional GGUF file to benchmark instead of the small test model
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-$ROOT/build/bridge-test}

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
PATH=$JAVA_HOME/bin:$PATH
JNI=$JAVA_HOME/include
mkdir -p "$OUT/models" "$OUT/classes"

fetch() {
    [ -s "$OUT/models/$1" ] || curl -fL --retry 3 -o "$OUT/models/$1" "$2"
}
fetch smol.gguf "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf"
fetch qwen.gguf "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf"

cmake -S "$ROOT/tools/bridge-test" -B "$OUT/native" -DCMAKE_BUILD_TYPE=Release -DJNI_INCLUDE="$JNI" \
    -DBUILD_SHARED_LIBS=OFF -DGGML_NATIVE=ON -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF \
    -DLLAMA_BUILD_COMMON=ON -DLLAMA_BUILD_APP=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_OPENSSL=OFF > "$OUT/cmake.log" 2>&1 \
    || { echo "configure failed, see $OUT/cmake.log" >&2; exit 1; }
cmake --build "$OUT/native" --target modulai-llama -j "$(nproc)" > "$OUT/build.log" 2>&1 \
    || { echo "build failed, see $OUT/build.log" >&2; exit 1; }

javac -d "$OUT/classes" "$ROOT"/tools/bridge-test/java/com/modulai/engine/llamacpp/*.java
java -cp "$OUT/classes" com.modulai.engine.llamacpp.LlamaNative "$OUT/native" "$OUT/models" "$@"
