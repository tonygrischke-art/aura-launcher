#!/bin/bash
# Build the Hermes phone app without Gradle: aapt2 + javac + d8 + zipalign + apksigner.
# Output: build/hermes-mobile.apk (signed with a local key in ~/.android/hermes-mobile.keystore, or $KEYSTORE).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export JAVA_HOME PATH="$JAVA_HOME/bin:$PATH"
PLATFORM=35          # targetSdk
COMPILE_PLATFORM=36  # compile against Android 16 APIs (Live Updates)
BT="$SDK/build-tools/35.0.0"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/26.1.10909125}"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
ANDROID_JAR="$SDK/platforms/android-$COMPILE_PLATFORM/android.jar"
VERSION_NAME="${VERSION_NAME:-0.1.0}"
VERSION_CODE="${VERSION_CODE:-1}"
KS="${KEYSTORE:-$HOME/.android/hermes-mobile.keystore}"  # CI passes the release key here

[ -f "$ANDROID_JAR" ] || { echo "missing $ANDROID_JAR (sdkmanager \"platforms;android-$COMPILE_PLATFORM\")"; exit 1; }
[ -x "$BT/aapt2" ] || { echo "missing build-tools 35.0.0"; exit 1; }
[ -x "$CMAKE" ] || { echo "missing cmake 3.22.1"; exit 1; }
[ -d "$NDK" ] || { echo "missing NDK at $NDK"; exit 1; }

# 0. Build native library (llama.cpp + mtmd + JNI)
echo "Building native library..."
LLVM_BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
READELF="$LLVM_BIN/llvm-readelf"; NM="$LLVM_BIN/llvm-nm"
NATIVE=build/native/arm64-v8a
rm -rf build/native src/main/jniLibs
mkdir -p "$NATIVE"

"$CMAKE" -S src/main/cpp -B "$NATIVE" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_BUILD_COMMON=ON \
    -DLLAMA_OPENSSL=OFF \
    -DBUILD_SHARED_LIBS=ON \
    -DGGML_BACKEND_DL=ON \
    -DGGML_CPU_ALL_VARIANTS=ON \
    -DGGML_LLAMAFILE=OFF \
    -DCMAKE_C_FLAGS="-O3 -DNDEBUG" \
    -DCMAKE_CXX_FLAGS="-O3 -DNDEBUG"
"$CMAKE" --build "$NATIVE" --config Release -- -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"

# Collect EVERY library the APK needs: our JNI lib, llama.cpp's libs (llama.cpp writes them to <build>/bin),
# the ggml CPU backend modules (dlopen'd, not in DT_NEEDED), and libc++_shared.
JNI=src/main/jniLibs/arm64-v8a
mkdir -p "$JNI"
cp "$NATIVE/libhermes-llama.so" "$JNI/"
find "$NATIVE" -name 'lib*.so' -not -path "$NATIVE/libhermes-llama.so" -exec cp -f {} "$JNI/" \;
cp "$LLVM_BIN/../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$JNI/"

# Gate: fail the build rather than ship an APK that cannot load.
SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libandroid.so "
for so in "$JNI"/*.so; do
  base="$(basename "$so")"
  case "$base" in *.so.[0-9]*) echo "FAIL: versioned library name $base"; exit 1;; esac
  soname="$("$READELF" -d "$so" | sed -n 's/.*SONAME.*\[\(.*\)\]/\1/p')"
  [ -z "$soname" ] || [ "$soname" = "$base" ] || { echo "FAIL: $base has SONAME $soname"; exit 1; }
  for need in $("$READELF" -d "$so" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'); do
    case "$SYSTEM_LIBS" in *" $need "*) continue;; esac
    [ -f "$JNI/$need" ] || { echo "FAIL: $base needs $need which is not packaged"; exit 1; }
  done
done
"$NM" -D --defined-only "$JNI/libhermes-llama.so" | grep -q 'Java_com_omarqaterge_hermesmobile_LocalLlmImpl_nativeLoad' \
  || { echo "FAIL: JNI symbols missing from libhermes-llama.so"; exit 1; }
ls "$JNI"/libggml-cpu-*.so >/dev/null 2>&1 || { echo "FAIL: no libggml-cpu-*.so backend modules (GGML_BACKEND_DL)"; exit 1; }
echo "Native libs OK:"; ls -l "$JNI"

# 1. web UI → assets
( cd ../web && npx vite build >/dev/null )
mkdir -p assets/www
cp ../web/dist/index.html assets/www/index.html
# Settings → Uninstall Hermes runs this in Termux (Uninstall.java); shipped in the APK so it matches the app.
mkdir -p assets/phone
cp ../phone/uninstall.sh assets/phone/uninstall.sh

rm -rf build && mkdir -p build/gen build/classes build/dex

# 2. resources + manifest
"$BT/aapt2" compile --dir res -o build/res.zip
RES_ZIPS=(build/res.zip)
# Emulator tests only: the setup flow's installer command fetches this URL instead of main's bootstrap.sh (a working tree
# served from the Mac). MainActivity accepts only http://10.0.2.2:<port>/… (the emulator's host). Never set for a release.
if [ -n "${HM_TEST_BOOTSTRAP_URL:-}" ]; then
  echo "TEST BUILD: installer command uses $HM_TEST_BOOTSTRAP_URL" >&2
  mkdir -p build/res-test/values
  printf '<resources><string name="hm_test_bootstrap" translatable="false">%s</string></resources>\n' "$HM_TEST_BOOTSTRAP_URL" > build/res-test/values/test.xml
  "$BT/aapt2" compile --dir build/res-test -o build/res-test.zip
  RES_ZIPS+=(build/res-test.zip)
fi
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
  --min-sdk-version 26 --target-sdk-version "$PLATFORM" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java build/gen -o build/unsigned.apk "${RES_ZIPS[@]}"

# 3. Java → classes → dex
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt
"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
( cd build/dex && zip -q -j ../unsigned.apk classes.dex )

# 4. Add native libraries to APK (stored uncompressed so zipalign -p can page-align them)
mkdir -p build/apk_lib/lib/arm64-v8a
cp src/main/jniLibs/arm64-v8a/*.so build/apk_lib/lib/arm64-v8a/
( cd build/apk_lib && zip -q -0 -r ../unsigned.apk lib )

# 5. align + sign
[ -f "$KS" ] || { mkdir -p "$(dirname "$KS")"; keytool -genkeypair -keystore "$KS" -storepass hermesmobile -keypass hermesmobile \
  -alias hermes -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Hermes Mobile, O=omarqaterge" >/dev/null 2>&1; }
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:hermesmobile --key-pass pass:hermesmobile \
  --out build/hermes-mobile.apk build/aligned.apk
"$BT/apksigner" verify build/hermes-mobile.apk
ls -la build/hermes-mobile.apk