#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
abi="${1:-arm64-v8a}"
case "$abi" in arm64-v8a|x86_64) ;; *) echo 'Supported ABIs: arm64-v8a x86_64' >&2; exit 1;; esac
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/28.2.13676358}"
vendor='native/vendor/FreeRDP-FreeRDP-c7e6848'
if [[ ! -f "$vendor/CMakeLists.txt" ]]; then
  mkdir -p native/vendor
  archive='native/vendor/FreeRDP-3.32.2.tar.gz'
  curl -fL --retry 3 -o "$archive" 'https://api.github.com/repos/FreeRDP/FreeRDP/tarball/3.32.2'
  printf '%s  %s\n' '8ef3b31f42e3c54499d9baa3da53c48d0d671b0e687a22660dd3290793f51b3b' "$archive" | sha256sum -c -
  tar -xzf "$archive" -C native/vendor
fi
cmake -S "$vendor/client/Android/Studio/freeRDPCore/src/main/cpp" -B "native/build/$abi" -GNinja \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-28 -DCMAKE_BUILD_TYPE=Release \
  -DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_OPUS=OFF -DWITH_WEBP=OFF \
  -DWITH_JPEG=OFF -DWITH_PNG=OFF -DWITH_CJSON=ON -DWITH_MEDIACODEC=ON
cmake --build "native/build/$abi" -j "${TERMORA_BUILD_JOBS:-4}"
dest="app/src/main/jniLibs/$abi"
mkdir -p "$dest"
cp "native/build/$abi/libfreerdp-android.so" "$dest/"
find "$vendor/client/Android/Studio/freeRDPCore/src/main/jniLibs/$abi/lib" -maxdepth 1 -type f -name '*.so' -exec cp '{}' "$dest/" \;
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$dest"/*.so
python3 scripts/prepare-rdp.py
