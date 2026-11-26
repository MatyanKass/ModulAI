#!/bin/sh
# Checks things in a built APK that unit tests cannot see and that have broken on a
# real device before. Usage: tools/check-apk.sh <apk> [build-tools dir]
set -e
APK=$1
BT=${2:-$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/* | sort -V | tail -1)}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
fail=0

# llama.cpp dlopen()s its backends by path, so they must be extracted on install.
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -q 'extractNativeLibs.*=true' \
    || { echo "FAIL extractNativeLibs is not true"; fail=1; }

unzip -l "$APK" | grep -q 'lib/arm64-v8a/libggml-cpu' \
    || { echo "FAIL no ggml CPU backend library in the APK"; fail=1; }

# Optional GPU backend (ggml-opencl, renamed; see engines/llm-llamacpp/src/main/cpp/CMakeLists.txt).
# It must come without an OpenCL loader of its own: a bundled libOpenCL.so would be found
# before the vendor driver and the GPU would never work.
unzip -l "$APK" | grep -q 'lib/arm64-v8a/libmodulai-ggml-opencl\.so' \
    || { echo "FAIL no ggml OpenCL backend library in the APK"; fail=1; }
# From Android 12 an app may load the vendor's libOpenCL.so only if the manifest asks for it.
"$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -A2 'E: uses-native-library' \
    | grep -q '"libOpenCL.so"' \
    || { echo "FAIL the manifest does not declare <uses-native-library> for libOpenCL.so"; fail=1; }
if unzip -l "$APK" | grep -q 'lib/arm64-v8a/libOpenCL\.so'; then
    echo "FAIL the APK bundles libOpenCL.so, which shadows the vendor driver"; fail=1
fi
# ggml loads every libggml-opencl*.so on its own, before the engine's crash guard is in place.
if unzip -l "$APK" | grep -q 'lib/arm64-v8a/libggml-opencl'; then
    echo "FAIL the OpenCL backend is packaged under a name ggml loads unguarded"; fail=1
fi

# The JNI bridge calls the PieceCallback methods by name; the shrinker must keep them.
unzip -q -o "$APK" 'classes*.dex' -d "$TMP"
for method in onPiece onToolCall; do
    found=0
    for dex in "$TMP"/classes*.dex; do
        if "$BT/dexdump" "$dex" 2>/dev/null | grep -q "name          : '$method'"; then found=1; fi
    done
    [ "$found" = 1 ] || { echo "FAIL PieceCallback.$method was renamed or removed"; fail=1; }
done

[ "$fail" = 0 ] && echo "APK checks passed"
exit $fail
