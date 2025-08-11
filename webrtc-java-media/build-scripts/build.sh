#!/bin/bash
# native/build.sh
# Unified Unix/macOS/MinGW (cross) FFmpeg build script.
# This script consolidates the logic previously in native/build.sh.
# Usage examples:
#   ./build.sh                      # Native build for current host (Linux/macOS)
#   ./build.sh linux-x86_64         # Explicit native Linux x86_64
#   ./build.sh linux-arm64          # Cross to Linux aarch64 (needs aarch64-linux-gnu-* toolchain)
#   ./build.sh linux-armhf          # Cross to Linux armhf (needs arm-linux-gnueabihf-* toolchain)
#   ./build.sh darwin-arm64         # Native on Apple Silicon
#   ./build.sh darwin-x86_64        # Native on Intel macOS
#   ./build.sh windows-x86_64       # Cross to Windows (needs x86_64-w64-mingw32-* toolchain)
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"            # In this location, PROJECT_DIR is the native directory
FFMPEG_SOURCE_DIR="$PROJECT_DIR/ffmpeg"
BUILD_DIR="$PROJECT_DIR/build"
INSTALL_DIR="$PROJECT_DIR/libs"

# Resolve target from first argument or TARGET environment variable
INPUT_TARGET="$1"
TARGET="${INPUT_TARGET:-${TARGET:-}}"

# Detect host platform/arch for defaults
case "$(uname -s)" in
  Linux*)  HOST_OS=linux;;
  Darwin*) HOST_OS=darwin;;
  *) echo "Unsupported host platform: $(uname -s)"; exit 1;;
esac
case "$(uname -m)" in
  x86_64)     HOST_ARCH=x86_64;;
  arm64|aarch64) HOST_ARCH=arm64;;
  *) echo "Unsupported host architecture: $(uname -m)"; exit 1;;
esac

# If no explicit TARGET provided, use host
if [ -z "$TARGET" ]; then
  TARGET="$HOST_OS-$HOST_ARCH"
fi

# Parse TARGET into variables
TARGET_OS="${TARGET%%-*}"
TARGET_ARCH="${TARGET##*-}"

echo "Building FFmpeg with LGPL-only configuration..."
echo "Requested target: $TARGET (host: $HOST_OS-$HOST_ARCH)"

# Create build and install directories
mkdir -p "$BUILD_DIR"
mkdir -p "$INSTALL_DIR"

PLATFORM_DIR="$INSTALL_DIR/$TARGET_OS-$TARGET_ARCH"
mkdir -p "$PLATFORM_DIR"

# Configure per-target toolchain and flags
FF_TARGET_OS=""
FF_ARCH=""
CROSS_PREFIX=""
EXTRA_CONF=( )
EXTRA_CFLAGS=""
EXTRA_LDFLAGS=""
CC_SET=""

case "$TARGET_OS" in
  linux)
    FF_TARGET_OS=linux
    case "$TARGET_ARCH" in
      x86_64)
        FF_ARCH=x86_64
        ;;
      arm64)
        FF_ARCH=aarch64
        if [ "$HOST_ARCH" != "arm64" ]; then
          CROSS_PREFIX=${CROSS_PREFIX:-aarch64-linux-gnu-}
          EXTRA_CONF+=(--enable-cross-compile)
        fi
        ;;
      armhf)
        FF_ARCH=arm
        CROSS_PREFIX=${CROSS_PREFIX:-arm-linux-gnueabihf-}
        EXTRA_CONF+=(--enable-cross-compile --cpu=armv7-a)
        # Tune flags typical for armhf hard-float
        EXTRA_CFLAGS="-mfpu=neon-vfpv4 -mfloat-abi=hard"
        ;;
      *) echo "Unsupported linux arch: $TARGET_ARCH"; exit 1;;
    esac
    ;;
  darwin|macos)
    TARGET_OS=darwin
    FF_TARGET_OS=darwin
    case "$TARGET_ARCH" in
      x86_64) FF_ARCH=x86_64 ;;
      arm64)  FF_ARCH=arm64  ;;
      *) echo "Unsupported darwin arch: $TARGET_ARCH"; exit 1;;
    esac
    # Use clang with -arch flags on macOS
    CC_SET="--cc=clang"
    EXTRA_CFLAGS="-arch $TARGET_ARCH"
    EXTRA_LDFLAGS="-arch $TARGET_ARCH"
    ;;
  windows|mingw|mingw32)
    TARGET_OS=windows
    FF_TARGET_OS=mingw32
    if [ "$TARGET_ARCH" != "x86_64" ]; then
      echo "Only x86_64 is supported for Windows target."; exit 1
    fi
    FF_ARCH=x86_64
    CROSS_PREFIX=${CROSS_PREFIX:-x86_64-w64-mingw32-}
    EXTRA_CONF+=(--enable-cross-compile)
    ;;
  *)
    echo "Unsupported target OS: $TARGET_OS"; exit 1
    ;;
esac

# Build third-party dependencies (libdav1d, nv-codec-headers)
SRC_DIR="$BUILD_DIR/src-$TARGET"
mkdir -p "$SRC_DIR"

export PKG_CONFIG_PATH="$PLATFORM_DIR/lib/pkgconfig${PKG_CONFIG_PATH:+:$PKG_CONFIG_PATH}"
HAVE_DAV1D=0

build_dav1d() {
  echo "Building dav1d for $TARGET_OS-$TARGET_ARCH into $PLATFORM_DIR"
  cd "$SRC_DIR"
  if [ ! -d dav1d ]; then
    git clone https://code.videolan.org/videolan/dav1d.git
  fi
  cd dav1d
  BUILD_DIR_NAME="build-$TARGET_OS-$TARGET_ARCH"
  rm -rf "$BUILD_DIR_NAME"

  MESON_ARGS=("$BUILD_DIR_NAME" "." "--prefix=$PLATFORM_DIR" "--libdir=lib" "--buildtype=release")

  # Prepare a Meson cross file when cross-compiling or targeting Windows
  if [ "$TARGET_OS" = "windows" ]; then
    CROSS_TXT="cross-$TARGET_OS-$TARGET_ARCH.txt"
    cat > "$CROSS_TXT" <<EOF
[binaries]
c = '${CROSS_PREFIX}gcc'
cpp = '${CROSS_PREFIX}g++'
ar = '${CROSS_PREFIX}ar'
strip = '${CROSS_PREFIX}strip'
windres = '${CROSS_PREFIX}windres'
pkgconfig = 'pkg-config'

[host_machine]
system = 'windows'
cpu_family = 'x86_64'
cpu = 'x86_64'
endian = 'little'

[built-in options]
c_args = []
c_link_args = []
EOF
    MESON_ARGS+=( "--cross-file" "$CROSS_TXT" )
  elif [ "$TARGET_OS" = "linux" ] && [ -n "$CROSS_PREFIX" ]; then
    # Cross-compiling to Linux (arm64/armhf)
    CROSS_TXT="cross-$TARGET_OS-$TARGET_ARCH.txt"
    case "$TARGET_ARCH" in
      arm64)
        CPU_FAMILY='aarch64'; CPU='aarch64';;
      armhf)
        CPU_FAMILY='arm'; CPU='armv7';;
      x86_64)
        CPU_FAMILY='x86_64'; CPU='x86_64';;
    esac
    cat > "$CROSS_TXT" <<EOF
[binaries]
c = '${CROSS_PREFIX}gcc'
cpp = '${CROSS_PREFIX}g++'
ar = '${CROSS_PREFIX}ar'
strip = '${CROSS_PREFIX}strip'
pkgconfig = 'pkg-config'

[host_machine]
system = 'linux'
cpu_family = '${CPU_FAMILY}'
cpu = '${CPU}'
endian = 'little'

[built-in options]
c_args = []
c_link_args = []
EOF
    MESON_ARGS+=( "--cross-file" "$CROSS_TXT" )
  fi

  if meson setup "${MESON_ARGS[@]}"; then
    if ninja -C "$BUILD_DIR_NAME"; then
      ninja -C "$BUILD_DIR_NAME" install
      HAVE_DAV1D=1
    else
      echo "Warning: ninja failed for dav1d; continuing without libdav1d"
    fi
  else
    echo "Warning: meson setup failed for dav1d; continuing without libdav1d"
  fi
}

build_nvcodec_headers() {
  echo "Installing nv-codec-headers into $PLATFORM_DIR"
  cd "$SRC_DIR"
  if [ ! -d nv-codec-headers ]; then
    git clone https://github.com/FFmpeg/nv-codec-headers.git
  fi
  cd nv-codec-headers
  make PREFIX="$PLATFORM_DIR" install || echo "Warning: nv-codec-headers install failed"
}

# Execute dependency builds
build_dav1d
build_nvcodec_headers

# Return to FFmpeg source
cd "$FFMPEG_SOURCE_DIR"

# Build base configure arguments
CONFIG_ARGS=(
  --target-os=$FF_TARGET_OS
  --arch=$FF_ARCH
  ${CROSS_PREFIX:+--cross-prefix=$CROSS_PREFIX}
  --pkg-config=pkg-config
  --prefix="$PLATFORM_DIR"
  --disable-static
  --enable-shared
  --disable-debug
  --disable-doc \
  --disable-htmlpages \
  --disable-manpages \
  --disable-podpages \
  --disable-txtpages
  --enable-ffmpeg
  --enable-avcodec
  --enable-avformat
  --enable-avfilter
  --enable-swscale
  --enable-swresample
  --disable-everything
  --enable-hwaccels
  --enable-decoders
  --enable-demuxers
  --enable-parsers
  --enable-protocols
  --enable-bsfs
  --enable-small
)

# Windows-specific HW acceleration flags
if [ "$TARGET_OS" = "windows" ]; then
  CONFIG_ARGS+=(--enable-nvdec --enable-d3d11va --enable-dxva2)
fi

# Enable libdav1d if we built and installed it
if [ "$HAVE_DAV1D" = "1" ]; then
  CONFIG_ARGS+=(--enable-libdav1d)
fi

# Append extra config options
CONFIG_ARGS+=("${EXTRA_CONF[@]}")
[ -n "$CC_SET" ] && CONFIG_ARGS+=($CC_SET)
[ -n "$EXTRA_CFLAGS" ] && CONFIG_ARGS+=(--extra-cflags="$EXTRA_CFLAGS")
[ -n "$EXTRA_LDFLAGS" ] && CONFIG_ARGS+=(--extra-ldflags="$EXTRA_LDFLAGS")

# Show final configuration
echo "Configuring with:"
printf '  ./configure'
printf ' %q' "${CONFIG_ARGS[@]}"
echo

# Run configure
./configure "${CONFIG_ARGS[@]}"

echo "Configuration complete. Building..."

# Build FFmpeg
make -j$(getconf _NPROCESSORS_ONLN)

# Install to platform directory
make install

echo "FFmpeg build complete for $TARGET_OS-$TARGET_ARCH"
echo "Libraries installed in: $PLATFORM_DIR"

# List built libraries
echo "Built libraries:"
find "$PLATFORM_DIR" -name "*.so*" -o -name "*.dylib*" -o -name "*.dll*" 2>/dev/null || true

# Create a build info file
cat > "$PLATFORM_DIR/build-info.txt" << EOF
FFmpeg Build Information
========================
Platform: $TARGET_OS-$TARGET_ARCH
Build Date: $(date)
FFmpeg Version: $(git describe --tags 2>/dev/null || echo "unknown")
Configuration: LGPL-only build (shared)
License: LGPL 2.1+
EOF

echo "Build complete!"