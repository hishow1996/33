#!/usr/bin/env bash
set -euo pipefail

CT2_TAG=v4.8.3
CT2_COMMIT=8d91aa08225004755360daa43deb62b35b1ed65d
SPM_TAG=v0.2.2
SPM_COMMIT=e0cce7d37b065b5140349dbe12c6bcf6192fdd78
ABSL_TAG=20260526.0
ABSL_COMMIT=5650e9cf76d3be4318d5fa3af38ee483ddfd5e4a
SUBMODULES="third_party/ruy third_party/spdlog third_party/cpu_features"

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
NDK=${ANDROID_NDK:-${ANDROID_NDK_HOME:-}}
ANDROID_HOME=${ANDROID_HOME:-}
if [[ -z "$NDK" && -n "$ANDROID_HOME" ]]; then NDK="$ANDROID_HOME/ndk/29.0.14206865"; fi
[[ -f "$NDK/build/cmake/android.toolchain.cmake" ]] || {
    echo "CTranslate2 Android build needs NDK r29 or set ANDROID_NDK." >&2; exit 1;
}
NDK=$(cd "$NDK" && pwd -P)
CMAKE_BIN="$ANDROID_HOME/cmake/3.31.6/bin"
if [[ -d "$CMAKE_BIN" ]]; then export PATH="$CMAKE_BIN:$PATH"; fi
BIN=$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -n 1)
command -v cmake >/dev/null || { echo "CMake is missing; install Android SDK cmake;3.31.6." >&2; exit 1; }
command -v ninja >/dev/null || { echo "Ninja is missing from PATH." >&2; exit 1; }

CACHE=${OPUSMT_CT2_CACHE:-$HOME/.cache/opusmt-ct2}
SRC="$CACHE/src"
CT2="$SRC/ctranslate2"
SPM="$SRC/sentencepiece"
ABSL="$SPM/third_party/abseil-cpp"
BUILD="$CACHE/build-arm64-v8a"
OUT=${OPUSMT_CT2_OUT:-$REPO/app/build/generated/opusmtct2/jniLibs}
mkdir -p "$SRC" "$CACHE"

fresh_clone() {
    local target="$1"; shift
    rm -rf "$target"
    git clone --quiet --depth 1 "$@" "$target"
}
check_commit() {
    local dir="$1" expected="$2" name="$3"
    local actual
    actual=$(git -C "$dir" rev-parse HEAD)
    [[ "$actual" == "$expected" ]] || { echo "$name checkout mismatch: $actual (expected $expected)." >&2; exit 1; }
}

if [[ ! -d "$CT2/.git" ]]; then fresh_clone "$CT2" --branch "$CT2_TAG" https://github.com/OpenNMT/CTranslate2; fi
check_commit "$CT2" "$CT2_COMMIT" "CTranslate2"
git -C "$CT2" submodule update --init --depth 1 $SUBMODULES
git -C "$CT2/third_party/ruy" submodule update --init --depth 1 third_party/cpuinfo
POOL="$CT2/src/thread_pool.cc"
if ! grep -q 'defined(__ANDROID__)' "$POOL"; then
    sed -i.orig 's/#if !defined(__linux__) || defined(_OPENMP)$/#if !defined(__linux__) || defined(_OPENMP) || defined(__ANDROID__)/' "$POOL"
    rm -f "$POOL.orig"
    grep -q 'defined(__ANDROID__)' "$POOL" || { echo "Could not patch CTranslate2 Android thread-affinity path." >&2; exit 1; }
fi

if [[ ! -d "$SPM/.git" ]]; then fresh_clone "$SPM" --branch "$SPM_TAG" https://github.com/google/sentencepiece; fi
check_commit "$SPM" "$SPM_COMMIT" "SentencePiece"
if [[ ! -d "$ABSL/.git" ]]; then fresh_clone "$ABSL" --branch "$ABSL_TAG" https://github.com/abseil/abseil-cpp; fi
check_commit "$ABSL" "$ABSL_COMMIT" "Abseil"

mkdir -p "$BUILD"
cmake -S "$HERE" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_static \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 -DCT2_SRC="$CT2" -DSPM_SRC="$SPM"
cmake --build "$BUILD" --target opusmtct2 --parallel 2
DEST="$OUT/arm64-v8a"
mkdir -p "$DEST"
"$BIN/llvm-strip" --strip-unneeded -o "$DEST/libopusmtct2.so" "$BUILD/libopusmtct2.so"
test -s "$DEST/libopusmtct2.so"
echo "Built arm64-v8a/libopusmtct2.so ($(wc -c < "$DEST/libopusmtct2.so") bytes)"
