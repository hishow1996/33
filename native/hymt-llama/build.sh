#!/usr/bin/env bash
set -euo pipefail

# HY-MT1.5 2-bit GGUF uses Q2_0C, which is currently carried by llama.cpp PR 19357.
# Pin the exact reviewed source commit instead of silently building an incompatible main branch.
LLAMA_COMMIT=2af64dd00a6689a7bfaf69b4768a944d0ec6bade

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
ANDROID_HOME=${ANDROID_HOME:-}
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
if [[ -z "$NDK" && -n "$ANDROID_HOME" ]]; then
    NDK="$ANDROID_HOME/ndk/29.0.14206865"
fi
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
    echo "HY-MT1.5 Android runtime needs Android NDK r29 or set ANDROID_NDK." >&2
    exit 1
}
NDK=$(cd "$NDK" && pwd -P)

CMAKE_BIN="$ANDROID_HOME/cmake/3.31.6/bin"
if [[ -d "$CMAKE_BIN" ]]; then export PATH="$CMAKE_BIN:$PATH"; fi
command -v cmake >/dev/null || { echo "CMake 3.31.6 is required." >&2; exit 1; }
command -v ninja >/dev/null || { echo "Ninja is required." >&2; exit 1; }

CACHE=${HYMT_LLAMA_CACHE:-$HOME/.cache/hymt-llama}
LLAMA="$CACHE/llama.cpp"
BUILD="$CACHE/build-arm64-v8a"
OUT=${HYMT_LLAMA_OUT:-$REPO/app/build/generated/hymt/jniLibs}
mkdir -p "$CACHE"

if [[ ! -d "$LLAMA/.git" ]]; then
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$LLAMA"
fi
git -C "$LLAMA" fetch --depth 1 origin "$LLAMA_COMMIT"
git -C "$LLAMA" checkout --detach "$LLAMA_COMMIT"
ACTUAL=$(git -C "$LLAMA" rev-parse HEAD)
[[ "$ACTUAL" == "$LLAMA_COMMIT" ]] || {
    echo "llama.cpp checkout mismatch: $ACTUAL (expected $LLAMA_COMMIT)." >&2
    exit 1
}

cmake -S "$HERE" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DLLAMA_SRC="$LLAMA" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF \
    -DGGML_CPU_KLEIDIAI=ON \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_CUDA=OFF \
    -DGGML_VULKAN=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_METAL=OFF \
    -DLLAMA_BUILD_COMMON=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_CURL=OFF

cmake --build "$BUILD" --target hymt --parallel 2

BIN=$(find "$NDK/toolchains/llvm/prebuilt" -path '*/bin/llvm-strip' -type f | head -n 1)
[[ -n "$BIN" ]] || { echo "Android NDK llvm-strip is missing." >&2; exit 1; }
DEST="$OUT/arm64-v8a"
mkdir -p "$DEST"
"$BIN" --strip-unneeded -o "$DEST/libhymt.so" "$BUILD/libhymt.so"
test -s "$DEST/libhymt.so"
echo "Built arm64-v8a/libhymt.so ($(wc -c < "$DEST/libhymt.so") bytes)"
