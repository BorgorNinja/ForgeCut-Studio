#!/usr/bin/env bash
# Builds a static FFmpeg CLI (with libx264) for Android arm64 and saves it as out/libffmpeg.so
set -euo pipefail

NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:?Android NDK not found}}"
API=31
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
TRIPLE=aarch64-linux-android
CC="$TC/bin/${TRIPLE}${API}-clang"
CXX="$TC/bin/${TRIPLE}${API}-clang++"
AR="$TC/bin/llvm-ar"; RANLIB="$TC/bin/llvm-ranlib"; STRIP="$TC/bin/llvm-strip"; NM="$TC/bin/llvm-nm"

ROOT="$PWD"
WORK="$ROOT/build-ffmpeg"; PREFIX="$WORK/prefix"; OUT="$ROOT/out"
mkdir -p "$WORK" "$PREFIX" "$OUT"
cd "$WORK"

echo "== x264 =="
git clone --depth 1 https://code.videolan.org/videolan/x264.git
(
  cd x264
  CC="$CC" ./configure --prefix="$PREFIX" --host=aarch64-linux-android \
    --cross-prefix="$TC/bin/llvm-" --enable-static --enable-pic \
    --disable-cli --disable-opencl
  make -j"$(nproc)"
  make install
)

echo "== FFmpeg =="
git clone --depth 1 --branch release/7.1 https://github.com/FFmpeg/FFmpeg.git ffmpeg
cd ffmpeg
export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig"
./configure \
  --prefix="$PREFIX" \
  --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
  --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM" \
  --enable-gpl --enable-libx264 --enable-pic \
  --disable-shared --enable-static \
  --disable-doc --disable-debug --disable-ffplay --disable-ffprobe --disable-network \
  --pkg-config=pkg-config --pkg-config-flags="--static" \
  --extra-cflags="-I$PREFIX/include -O3 -fPIE" \
  --extra-ldflags="-L$PREFIX/lib" \
  --extra-ldexeflags="-pie"
make -j"$(nproc)"

"$STRIP" --strip-unneeded ffmpeg
cp ffmpeg "$OUT/libffmpeg.so"
ls -lh "$OUT/libffmpeg.so"
