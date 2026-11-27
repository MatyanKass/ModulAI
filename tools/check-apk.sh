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

unzip -l "$APK" | grep -q 'lib/arm64-v8a/libmodulai-sd.so' \
    || { echo "FAIL no image engine library in the APK"; fail=1; }
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

# Optional Hexagon NPU backend: present only when the APK was built with the Hexagon SDK
# (engines/llm-llamacpp/build.gradle.kts). The host library is renamed like the OpenCL one; the
# DSP-side skels are Hexagon code and travel as assets/hexagon/libggml-htp-v<arch>.so.
NPU_HOST=$(unzip -l "$APK" | grep -c 'lib/arm64-v8a/libmodulai-ggml-hexagon\.so$' || true)
NPU_SKELS=$(unzip -l "$APK" | grep -o 'assets/hexagon/libggml-htp-v[0-9]*\.so$' | sort -u || true)
if [ "$NPU_HOST" = 0 ] && [ -z "$NPU_SKELS" ]; then
    echo "note: no Hexagon NPU backend in this APK (built without the Hexagon SDK)"
else
    [ "$NPU_HOST" != 0 ] \
        || { echo "FAIL the APK has Hexagon skels but no lib/arm64-v8a/libmodulai-ggml-hexagon.so"; fail=1; }
    [ -n "$NPU_SKELS" ] \
        || { echo "FAIL libmodulai-ggml-hexagon.so is packaged without its skels in assets/hexagon/"; fail=1; }
    # The reference device, Snapdragon 8 Elite, has a Hexagon v79 NPU.
    echo "$NPU_SKELS" | grep -q 'libggml-htp-v79\.so' \
        || { echo "FAIL no Hexagon v79 skel (Snapdragon 8 Elite) in assets/hexagon/"; fail=1; }
    # From Android 12 an app may load the vendor's FastRPC library only if the manifest asks for it.
    "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$APK" | grep -A2 'E: uses-native-library' \
        | grep -q '"libcdsprpc.so"' \
        || { echo "FAIL the manifest does not declare <uses-native-library> for libcdsprpc.so"; fail=1; }
    # Each skel must be the Hexagon ELF upstream built, byte for byte: htp-swiv.py stores a CRC
    # over the loadable segments (ELF header included), which any strip or rewrite breaks.
    if command -v python3 > /dev/null; then
        for skel in $NPU_SKELS; do
            unzip -q -o "$APK" "$skel" -d "$TMP"
            python3 -I -c '
import struct, sys, zlib
d = open(sys.argv[1], "rb").read()
if d[:4] != b"\x7fELF" or d[4] != 1 or struct.unpack_from("<H", d, 18)[0] != 164:
    sys.exit("not a 32-bit Hexagon ELF")
phoff, shoff = struct.unpack_from("<II", d, 28)
phentsize, phnum, shentsize, shnum = struct.unpack_from("<HHHH", d, 42)
crc = 0
for i in range(phnum):
    t, off, _, _, fsz, msz = struct.unpack_from("<IIIIII", d, phoff + i * phentsize)
    if t == 1:
        crc = zlib.crc32(d[off:off + fsz], crc)
        crc = zlib.crc32(bytes(msz - fsz), crc)
for i in range(shnum):
    _, typ, _, _, off, _ = struct.unpack_from("<IIIIII", d, shoff + i * shentsize)
    if typ == 0xD3574956:
        magic, stored = struct.unpack_from("<4sI", d, off)
        sys.exit(0 if magic == b"SWIV" and stored == crc & 0xFFFFFFFF else "SWIV CRC mismatch")
sys.exit("no SWIV section")
' "$TMP/$skel" || { echo "FAIL $skel is not the skel upstream built"; fail=1; }
        done
    else
        echo "note: python3 not found, Hexagon skel integrity not checked"
    fi
fi
# ggml loads every libggml-hexagon*.so on its own, before the engine's crash guard is in place,
# and skels under lib/ would be stripped by the app's packaging (see the check above).
if unzip -l "$APK" | grep -q 'lib/arm64-v8a/libggml-hexagon'; then
    echo "FAIL the Hexagon backend is packaged under a name ggml loads unguarded"; fail=1
fi
if unzip -l "$APK" | grep -q 'lib/arm64-v8a/libggml-htp-'; then
    echo "FAIL Hexagon skels are packaged as native libraries, where they get stripped"; fail=1
fi

# The JNI bridge calls the PieceCallback methods by name; the shrinker must keep them.
unzip -q -o "$APK" 'classes*.dex' -d "$TMP"
for method in onPiece onToolCall onProgress; do
    found=0
    for dex in "$TMP"/classes*.dex; do
        if "$BT/dexdump" "$dex" 2>/dev/null | grep -q "name          : '$method'"; then found=1; fi
    done
    [ "$found" = 1 ] || { echo "FAIL JNI callback $method was renamed or removed"; fail=1; }
done

[ "$fail" = 0 ] && echo "APK checks passed"
exit $fail
