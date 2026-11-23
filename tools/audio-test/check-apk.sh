#!/bin/sh
# Checks what the audio engine needs from a built APK and what unit tests cannot see.
# Usage: tools/audio-test/check-apk.sh <apk> [build-tools dir]
set -e
APK=$1
BT=${2:-$(ls -d "${ANDROID_HOME:-$ANDROID_SDK_ROOT}"/build-tools/* | sort -V | tail -1)}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
fail=0

for lib in libmodulai-audio.so libonnxruntime.so libonnxruntime4j_jni.so; do
    unzip -l "$APK" | grep -q "lib/arm64-v8a/$lib" || { echo "FAIL $lib is not in the APK"; fail=1; }
done

# The JNI bridge calls the WhisperCallback methods by name; the shrinker must keep them.
unzip -q -o "$APK" 'classes*.dex' -d "$TMP"
for dex in "$TMP"/classes*.dex; do "$BT/dexdump" "$dex" 2>/dev/null; done > "$TMP/dump.txt"
for method in onProgress onSegment; do
    grep -A4 "Lcom/modulai/engine/audio/WhisperCallback;" "$TMP/dump.txt" | grep -q "name          : '$method'" \
        || { echo "FAIL WhisperCallback.$method was renamed or removed"; fail=1; }
done
# ONNX Runtime's native half creates these classes by name.
for class in OnnxTensor OrtSession OrtException TensorInfo; do
    grep -q "Class descriptor  : 'Lai/onnxruntime/$class;'" "$TMP/dump.txt" \
        || { echo "FAIL ai.onnxruntime.$class was renamed or removed"; fail=1; }
done

# ONNX Runtime 1.30 and later register a telemetry provider; the pinned version must not.
if "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -qi 'onnxruntime.*telemetry'; then
    echo "FAIL the ONNX Runtime telemetry provider is in the manifest"; fail=1
fi

[ "$fail" = 0 ] && echo "Audio APK checks passed"
exit $fail
