# Build Notes

Building from source compiles three native libraries: Google's WebRTC, the JNI library around it, and FFmpeg with dav1d for the `webrtc-java-media` module. The tools below are what the build expects to find. The CI setup in `.github/actions/prepare-<os>/action.yml` installs the same set and is the reference when in doubt.

You don't have to install Chromium's `depot_tools`, Siso or GN: the build clones `depot_tools` into the WebRTC checkout and uses the tools it brings.

## Prerequisites

### All platforms

| Tool                  | Notes                                                                                     |
| --------------------- | ----------------------------------------------------------------------------------------- |
| JDK 17 or newer       | The libraries target Java 11, and the examples need 17.                                   |
| Maven 3.6.3 or newer  |                                                                                           |
| Git                   | Clones `depot_tools`, the WebRTC sources and the submodules.                              |
| CMake 3.13 or newer   | Must be on the `PATH`. The Maven build does not download it.                              |
| Python 3              | Runs `depot_tools` and the Linux sysroot script. On macOS it also needs `setuptools`.     |
| Meson and Ninja       | Build dav1d, the AV1 software decoder linked into FFmpeg (`pip install meson`).           |
| Disk space            | About 15 GB for the WebRTC checkout and its toolchains, kept in `~/webrtc` by default.   |

FFmpeg and dav1d come from Git submodules, which a plain clone leaves empty. Fetch them before the first build:

```shell
git submodule update --init --depth 1 webrtc-media/third-party/ffmpeg webrtc-media/third-party/dav1d
```

### Linux

Linux builds run on an x86-64 host and cross-compile for ARM. Install the build tools (Debian/Ubuntu package names):

```shell
sudo apt-get install binutils cmake git libva-dev make meson nasm ninja-build pkg-config python3 python3-setuptools rsync unzip wget xz-utils
```

`libva-dev` provides the VA-API headers FFmpeg is built against, for every target architecture, cross builds included. Only the headers are used: FFmpeg is linked against a small loader in `webrtc-media/src/main/cpp/dependencies/libva` instead of libva itself, so the libraries load on machines without libva. Set `-DLIBVA_INCLUDE_DIR=<dir>` (the directory holding `va/`) for headers somewhere other than `/usr/include` or `/usr/local/include`.

The JNI library compiles against the libc++ headers of the WebRTC build. Those headers only support the Clang that the WebRTC branch pins, so the toolchain files expect that exact Clang in `/opt/clang`. Install it this way (the package name is in `.github/actions/prepare-linux/action.yml` and changes with the WebRTC branch):

```shell
CLANG_PACKAGE=clang-llvmorg-24-init-7747-g62397f8b-27.tar.xz
wget https://commondatastorage.googleapis.com/chromium-browser-clang/Linux_x64/$CLANG_PACKAGE
sudo mkdir -p /opt/clang
sudo tar -xf $CLANG_PACKAGE -C /opt/clang
```

Headers and libraries (X11, D-Bus, GIO, GBM, libdrm) come from a Debian sysroot in `/opt/sysroot` rather than from the host, so no `-dev` packages are needed. Install the sysroot for each target architecture you build for (`amd64`, `arm64` or `armhf`):

```shell
python3 webrtc-jni/src/main/cpp/dependencies/webrtc/linux/sysroot/install-sysroot.py --arch=amd64
sudo mkdir -p /opt/sysroot
sudo mv webrtc-jni/src/main/cpp/dependencies/webrtc/linux/debian* /opt/sysroot/
```

To cross-compile for ARM, CI also installs `gcc-aarch64-linux-gnu g++-aarch64-linux-gnu` (`-Plinux-aarch64`) or `gcc-arm-linux-gnueabihf g++-arm-linux-gnueabihf` (`-Plinux-aarch32`).

Running the tests requires the runtime libraries the native library links against. A desktop system already has them. On a headless one, install them with:

```shell
sudo apt-get install libx11-6 libxext6 libxfixes3 libxdamage1 libxtst6 libxrandr2 libxcomposite1 libglib2.0-0 libgbm1 libdrm2
```

### macOS

- Xcode 16 (CI uses 16.1), selected with `xcode-select`. The library targets macOS 11 and later.
- The remaining tools from [Homebrew](https://brew.sh):

```shell
brew install cmake nasm meson ninja pkgconf python-setuptools
```

`nasm` is needed for the x86-64 build (`-Pmacos-cross-x86_64`), including when you cross-compile from Apple Silicon.

### Windows

- **Visual Studio 2022** with the *Desktop development with C++* workload. The build uses this local installation, not Google's internal toolchain. Add *MSVC ARM64 build tools* to build for `windows-aarch64`, and *C++ Clang tools for Windows* to build with `-Pwindows-clang`.
- **Windows SDK 10.0.28000.0**, this exact version. The WebRTC branch pins it, and `gn gen` fails without it. Install it with the *Debugging Tools for Windows* feature, because the toolchain setup needs `dbghelp.dll`. A different WebRTC branch may pin a different SDK (`SDK_VERSION` in `build/vs_toolchain.py`).
- **MSYS2**, installed in `C:\msys64` (set `MSYS2_ROOT` for another location). FFmpeg's configure scripts and makefiles need a POSIX shell with `make` and `nasm`. Git for Windows' bash has neither.

  ```shell
  winget install MSYS2.MSYS2
  C:\msys64\usr\bin\bash -lc "pacman -S --needed make nasm diffutils pkgconf"
  ```

- **Ninja and Meson** on the `PATH`, e.g. `choco install ninja` and `pip install meson`.

Run Maven from an ordinary command prompt or PowerShell. The build sets up the MSYS2 shell and the Visual Studio environment itself.

## Building

With the prerequisites installed, run:

```shell
mvn install
```

On the first run, the WebRTC source tree is loaded into the `/<user home>/webrtc` directory, and FFmpeg and dav1d are built into `/<user home>/ffmpeg`. This takes a long time. Later builds reuse both as long as their configuration is unchanged. To build the native libraries without running the tests, use `mvn package -DskipTests`, as CI does.

## Java artifact reproducibility and compatibility

Java archives use the version-controlled `project.build.outputTimestamp` in the
parent pom instead of the current build time. Keep the JDK and Maven versions
fixed when comparing hashes. A source archive needs no Git metadata to reproduce
this timestamp; it can also be overridden with
`-Dproject.build.outputTimestamp=<ISO-8601 timestamp>`. Native binaries require
additional measures before their builds can be considered reproducible.

After building or installing the host native artifact, compare the public core
and media APIs against the pinned previous release:

```shell
mvn -pl webrtc,webrtc-media -am verify -Papi-compatibility -DskipNativeBuild
```

The baseline is `api.compatibility.version` in the parent pom. Advance it after a
release. The two member-level exceptions record breaks already made since
0.20.0 (#395 and #421); remove them when advancing that baseline. Internal
packages are excluded. Reports are written to each module's `target/japicmp/`.
JavaFX is new since that release and has no published baseline yet; enable the
same profile there once its first release is available.

CI additionally packages all three Java libraries on JDK 11 with tests skipped
(JUnit 6 itself requires 17), then compiles and runs the consumers under
`.github/consumers/` separately on the classpath and modulepath. They resolve the
public client, media and JavaFX APIs and exercise native factory creation and
FFmpeg loading without hardware or a display. They use the Linux native jars
from the platform build, without `--add-exports` or `--add-opens`.

## Build Parameters

| Parameter          | Description                                            | Default Value               |
| ------------------ | ------------------------------------------------------ |-----------------------------|
| webrtc.branch      | The WebRTC branch to checkout.                         | branch-heads/8059           |
| webrtc.src.dir     | The absolute checkout path for the WebRTC source tree. | /\<user_home\>/webrtc       |
| webrtc.install.dir | The install path for the compiled WebRTC library. Is also used to link against a pre-compiled WebRTC library to reduce build time. | /\<user_home\>/webrtc/build |

## WebRTC patches and cached builds

The native build applies the patches in
`webrtc-jni/src/main/cpp/dependencies/webrtc/patches/common/` on every platform,
followed by the platform-specific patches. Common safety patches are required:
configuration stops if one cannot be applied.

A compiled WebRTC build records its GN arguments, extra targets, and patch
fingerprint. Changes to patch content invalidate both build-tree and installed
caches, so a native rebuild also includes the updated patches.

## Release workflow

Maintainers start **Prepare release** (`release.yml`) from `main`, providing the
next development version, such as `0.16.0-SNAPSHOT`. Preparation commits the
changelog and version changes and atomically pushes `main` and the release tag.
It then dispatches **Publish release** (`release-publish.yml`) on that tag using
`GITHUB_TOKEN` with `actions: write`; no additional token is needed. A push made
with `GITHUB_TOKEN` does not trigger a tag push workflow on its own.

Publication requires a `vX.Y.Z` tag whose commit belongs to `main` and whose
version matches the root pom. All platform builds, native tests and publication
jobs check out the triggering commit SHA. After testing every platform, the
workflow publishes one Maven Central bundle and creates a draft GitHub release.

Selecting **dryRun** in Prepare release builds and tests the dispatched commit
with the release version, signs and assembles a deployment bundle, and uploads
it as `deployment-bundle-dry-run`. It pushes no commits or tags, dispatches no
publication workflow, and uses placeholder Central credentials with uploads
disabled.

If preparation pushed the tag but dispatch failed, start Publish release manually
and select that tag. Before retrying a failed publication, check whether Maven
Central already accepted the version; published releases cannot be overwritten.
Maintainer-pushed `v*` tags also start Publish release automatically.
