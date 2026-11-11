# ffnvcodec headers

`include/ffnvcodec` holds the headers of FFmpeg's `nv-codec-headers` at tag `n12.0.16.1`, taken
unchanged:

https://github.com/FFmpeg/nv-codec-headers/tree/n12.0.16.1/include/ffnvcodec

- `dynlink_cuda.h`, `dynlink_cuviddec.h`, `dynlink_nvcuvid.h` and `dynlink_loader.h`: what FFmpeg
  needs to decode with NVDEC (the `h264_nvdec` and `vp9_nvdec` hwaccels). FFmpeg loads the CUDA
  driver (`libcuda.so.1`) and `libnvcuvid.so.1` with `dlopen` when a device is created, so the
  build needs the headers only and a machine without an NVIDIA driver loads the libraries all the
  same.
- `nvEncodeAPI.h`: the same file `webrtc-jni` vendors for NVENC. FFmpeg's configure checks for it
  too, so it is part of the set.

`ffnvcodec.pc.in` is the pkg-config file FFmpeg's configure looks for; CMake fills in the path and
puts the result where configure finds it. Version 12.0.16.1 is one of those FFmpeg n8.1 accepts
(`ffnvcodec >= 12.0.16.1 ffnvcodec < 12.1`), and the one the NVENC header comes from.

`LICENSE` gathers the notices at the head of the files. It is installed into the Linux platform
jars under `META-INF/licenses/ffnvcodec`.
