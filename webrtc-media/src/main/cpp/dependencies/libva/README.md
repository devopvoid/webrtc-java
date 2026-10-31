# libva loader

FFmpeg decodes with VA-API on Linux (the `h264_vaapi`, `hevc_vaapi`, `vp9_vaapi` and `av1_vaapi`
hwaccels), which is how the GPUs of Intel and AMD decode through the drivers distributions enable by
default: Intel's `iHD` (intel-media-driver) and Mesa's `radeonsi`.

Unlike CUDA and Vulkan, which FFmpeg loads with `dlopen`, it links libva and libva-drm directly.
The FFmpeg libraries would then name `libva.so.2` and `libva-drm.so.2` as dependencies, and a
machine without libva could not load them at all. So FFmpeg is linked against `va_loader.c`
instead: a static library that defines every libva function FFmpeg calls and loads the real libva
with `dlopen` on the first call. Where there is none, `vaGetDisplayDRM` returns `NULL`, FFmpeg
creates no device, and the player goes on to the next kind of device or decodes in software.

The headers are the host's, from the `libva-dev` package (`sudo apt-get install libva-dev`), and
the same for every architecture. CMake copies the `va/` directory into the build, so that a cross
build never gets `-I/usr/include`, and fills in `libva.pc.in` and `libva-drm.pc.in`, which FFmpeg's
configure finds ahead of the host's own. Set `LIBVA_INCLUDE_DIR` for headers elsewhere. A libva
older than the headers is fine: a function it lacks returns `VA_STATUS_ERROR_UNIMPLEMENTED`,
except `vaMapBuffer2` (libva 2.21), which falls back to `vaMapBuffer`.

After the build, CMake checks with `readelf` that no FFmpeg library depends on libva, and that none
leaves a `va*` function undefined. A new FFmpeg release that calls a function this file does not
define fails that check: add the function to `va_loader.c`. The pointer types come from the
headers' declarations, so the compiler holds each function to its prototype.

The version of the headers and a hash of `va_loader.c` are recorded in `components.txt`, so a
change to either rebuilds FFmpeg.
