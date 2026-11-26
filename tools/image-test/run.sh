#!/bin/sh
# Builds the image JNI bridge for this machine and runs it against a small diffusion
# model and a small upscaler. Images it produces are left in $OUT/out for inspection.
# Needs: git submodules checked out, cmake, a C++17 compiler, a JDK, curl.
#   OUT=<dir>     build and model cache (default: build/image-test)
#   MODEL=<url>   diffusion model to test with (default: SDXS-512, a one-step model, 0.7 GB)
#   STEPS, CFG    sampling settings that suit MODEL (default: 1 and 1)
#   EDIT_STEPS    steps for the image-to-image check; 0 (default) for a one-step model,
#                 which cannot keep its source and is only checked for running at all
#   TAESD=1       also generate with TAESD as the decoder (for SD 1.x / 2.x models)
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${OUT:-$ROOT/build/image-test}
MODEL=${MODEL:-https://huggingface.co/concedo/sdxs-512-tinySDdistilled-GGUF/resolve/main/sdxs-512-tinySDdistilled_Q8_0.gguf}

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
# Cached per URL, so switching MODEL never reuses the wrong weights.
MODELS=$OUT/models/$(printf %s "$MODEL" | cksum | cut -d' ' -f1)
mkdir -p "$MODELS" "$OUT/classes" "$OUT/out"

# Downloaded under a temporary name so an interrupted transfer is not mistaken for a model.
fetch() {
    [ -s "$MODELS/$1" ] || { curl -fL --retry 3 -o "$MODELS/$1.part" "$2" && mv "$MODELS/$1.part" "$MODELS/$1"; }
}
fetch model.gguf "$MODEL"
fetch esrgan.pth "https://huggingface.co/leonelhs/realesrgan/resolve/main/RealESRGAN_x4plus_anime_6B.pth"
[ -z "$TAESD" ] || fetch taesd.safetensors "https://huggingface.co/madebyollin/taesd/resolve/main/diffusion_pytorch_model.safetensors"

cmake -S "$ROOT/tools/image-test" -B "$OUT/native" -DCMAKE_BUILD_TYPE=Release -DJNI_INCLUDE="$JNI" \
    -DGGML_NATIVE=ON -DGGML_OPENMP=OFF -DSD_WEBP=OFF -DSD_WEBM=OFF > "$OUT/cmake.log" 2>&1 \
    || { echo "configure failed, see $OUT/cmake.log" >&2; exit 1; }
cmake --build "$OUT/native" --target modulai-sd -j "$(nproc)" > "$OUT/build.log" 2>&1 \
    || { echo "build failed, see $OUT/build.log" >&2; exit 1; }

javac -d "$OUT/classes" "$ROOT"/tools/image-test/java/com/modulai/engine/imagesdcpp/*.java
java -cp "$OUT/classes" com.modulai.engine.imagesdcpp.SdNative "$OUT/native" "$MODELS" "$OUT/out" \
    "${STEPS:-1}" "${CFG:-1}" "${EDIT_STEPS:-0}" ${TAESD:+taesd}
