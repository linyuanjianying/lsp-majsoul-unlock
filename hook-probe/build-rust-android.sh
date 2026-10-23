#!/bin/sh
# Cross-build the Rust AI library for arm64 Android on an aarch64 host.
#
# The NDK ships an x86_64 LLVM toolchain, so its ELF binaries are relaunched
# through qemu-x86_64. The NDK directory is never modified: wrappers live in
# build/ndk-wrap and go first on PATH. lld decides its flavour from its own file
# name, so the emulated copy is generated as "ld.lld" and selected explicitly.
set -e

NDK=${ANDROID_NDK_HOME:-/workspace/android-sdk/ndk/27.2.12479018}
BIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
QEMU=/usr/bin/qemu-x86_64-static
ROOT=$(cd "$(dirname "$0")" && pwd)
WRAP=$ROOT/build/ndk-wrap

rm -rf "$WRAP"
mkdir -p "$WRAP" "$WRAP/lld" "$WRAP/ld.lld"

# clang and friends: emulated, keeping their original file names. `clang++` is a
# symlink to `clang`, so resolve it instead of skipping it.
for tool in clang clang++ llvm-ar llvm-ranlib llvm-strip llvm-objcopy; do
    [ -f "$BIN/$tool" ] || continue
    real=$(readlink -f "$BIN/$tool")
    [ -f "$real" ] || continue
    printf '#!/bin/sh\nexec %s %s "$@"\n' "$QEMU" "$real" > "$WRAP/$tool"
    chmod +x "$WRAP/$tool"
done

# lld dispatches on its invocation name, so the emulated binary must literally
# be called ld.lld; the wrapper is found via -fuse-ld=<dir+name>.
cp "$BIN/lld" "$WRAP/ld.lld/ld.lld"
chmod +x "$WRAP/ld.lld/ld.lld"
printf '#!/bin/sh\nexec %s %s "$@"\n' "$QEMU" "$WRAP/ld.lld/ld.lld" > "$WRAP/lld/ld.lld"
chmod +x "$WRAP/lld/ld.lld"

# The NDK's *-clang launchers are bash scripts that call "$bin_dir/clang"; copy
# them verbatim so they pick up the emulated clang from this PATH.
for tool in "$BIN"/aarch64-linux-android*-clang "$BIN"/aarch64-linux-android*-clang++; do
    [ -f "$tool" ] || continue
    cp "$tool" "$WRAP/$(basename "$tool")"
    chmod +x "$WRAP/$(basename "$tool")"
done

export PATH="$WRAP:$PATH"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$WRAP/aarch64-linux-android24-clang
export CC_aarch64_linux_android=$WRAP/aarch64-linux-android24-clang
export CXX_aarch64_linux_android=$WRAP/aarch64-linux-android24-clang++
export AR_aarch64_linux_android=$WRAP/llvm-ar
export RANLIB_aarch64_linux_android=$WRAP/llvm-ranlib
export ANDROID_NDK_HOME=$NDK
# -fuse-ld=<path/ld.lld> makes clang exec the emulated linker directly.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-fuse-ld=$WRAP/lld/ld.lld"

cd "$ROOT/rust-ai"
cargo build --release --target aarch64-linux-android --target-dir "$ROOT/build/rust"
echo "built: $ROOT/build/rust/aarch64-linux-android/release/libmajsoulai.so"
