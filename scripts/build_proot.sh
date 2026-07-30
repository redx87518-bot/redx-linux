#!/bin/bash
# ============================================================
# Build static PRoot binaries for Android (ARM64, ARMv7, x86_64)
# using the Android NDK and a minimal talloc stub.
# Outputs binaries to app/src/main/assets/
# ============================================================
set -euo pipefail

NDK_VER="27.2.12479018"
API=26
PROOT_TAG="v5.4.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ASSETS_DIR="$REPO_DIR/app/src/main/assets"
TALLOC_STUB="$SCRIPT_DIR/talloc_stub"

mkdir -p "$ASSETS_DIR"

# ── NDK setup ──────────────────────────────────────────────
if [ -z "${ANDROID_HOME:-}" ]; then
  echo "ERROR: ANDROID_HOME not set"
  exit 1
fi

NDK="$ANDROID_HOME/ndk/$NDK_VER"
if [ ! -d "$NDK" ]; then
  echo "Installing NDK $NDK_VER..."
  yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VER" 2>/dev/null || \
  yes | "$ANDROID_HOME/cmdline-tools/tools/bin/sdkmanager" "ndk;$NDK_VER" 2>/dev/null || true
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
echo "Toolchain: $TOOLCHAIN"

# ── Download proot source once ─────────────────────────────
PROOT_SRC="/tmp/proot_src"
if [ ! -d "$PROOT_SRC" ]; then
  echo "Downloading proot $PROOT_TAG..."
  mkdir -p "$PROOT_SRC"
  wget -q "https://github.com/proot-me/proot/archive/refs/tags/${PROOT_TAG}.tar.gz" \
       -O /tmp/proot.tar.gz
  tar xzf /tmp/proot.tar.gz -C "$PROOT_SRC" --strip-components=1
fi

# ── Build function ─────────────────────────────────────────
build_abi() {
  local ABI="$1"      # e.g. arm64-v8a
  local TRIPLE="$2"   # e.g. aarch64-linux-android
  local CC_TRIPLE="$3" # e.g. aarch64-linux-android26

  echo ""
  echo "======================================="
  echo "Building PRoot for $ABI"
  echo "======================================="

  local CC="$TOOLCHAIN/bin/${CC_TRIPLE}-clang"
  local AR="$TOOLCHAIN/bin/llvm-ar"
  local RANLIB="$TOOLCHAIN/bin/llvm-ranlib"

  if [ ! -f "$CC" ]; then
    echo "WARNING: Compiler not found: $CC — skipping $ABI"
    return 0
  fi

  # ── Build minimal talloc stub ──────────────────────────
  local TALLOC_BUILD="/tmp/talloc_build_$ABI"
  mkdir -p "$TALLOC_BUILD"

  echo "Compiling talloc stub for $ABI..."
  "$CC" -c "$TALLOC_STUB/talloc.c" \
    -I"$TALLOC_STUB" \
    -O2 \
    -o "$TALLOC_BUILD/talloc.o"

  "$AR" rcs "$TALLOC_BUILD/libtalloc.a" "$TALLOC_BUILD/talloc.o"

  # Create a fake pkg-config .pc file so proot's Makefile finds talloc
  local PC_DIR="/tmp/pkgconfig_$ABI"
  mkdir -p "$PC_DIR"
  cat > "$PC_DIR/talloc.pc" <<EOF
Name: talloc
Description: Minimal talloc stub
Version: 2.4.2
Libs: $TALLOC_BUILD/libtalloc.a
Cflags: -I$TALLOC_STUB
EOF

  # ── Compile proot ──────────────────────────────────────
  local BUILD_DIR="/tmp/proot_build_$ABI"
  rm -rf "$BUILD_DIR"
  cp -r "$PROOT_SRC" "$BUILD_DIR"
  cd "$BUILD_DIR/src"

  echo "Compiling proot for $ABI..."

  # Override pkg-config to find our stub
  export PKG_CONFIG_PATH="$PC_DIR"
  export PKG_CONFIG_LIBDIR="$PC_DIR"

  # Suppress loader build (not needed for basic operation)
  # Build with seccomp disabled for Android compatibility
  make -j$(nproc) \
    CC="$CC" \
    AR="$AR" \
    RANLIB="$RANLIB" \
    CPPFLAGS="-I$TALLOC_STUB -DPROOT_NO_SECCOMP -D_GNU_SOURCE -D_FILE_OFFSET_BITS=64" \
    LDFLAGS="-static -L$TALLOC_BUILD" \
    LIBS="-ltalloc" \
    proot 2>&1 | tail -30

  local OUT="$ASSETS_DIR/proot-$ABI"
  cp "proot" "$OUT"
  chmod 755 "$OUT"

  local SIZE
  SIZE=$(du -sh "$OUT" | cut -f1)
  echo "✓ Built $OUT ($SIZE)"
}

# ── Build for each supported ABI ───────────────────────────
build_abi "arm64-v8a"   "aarch64-linux-android"    "aarch64-linux-android${API}"
build_abi "armeabi-v7a" "armv7a-linux-androideabi"  "armv7a-linux-androideabi${API}"
build_abi "x86_64"      "x86_64-linux-android"      "x86_64-linux-android${API}"

echo ""
echo "=== PRoot binaries ==="
ls -lh "$ASSETS_DIR"/proot-* 2>/dev/null || echo "No binaries found!"
echo "Done."
