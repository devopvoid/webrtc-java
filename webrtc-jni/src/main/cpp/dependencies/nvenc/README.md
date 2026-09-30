# NVENC header

`include/nvEncodeAPI.h` is the NVIDIA Video Codec SDK header for NVENC, API
version 12.0, taken unchanged from FFmpeg's `nv-codec-headers` at tag
`n12.0.16.1`:

https://github.com/FFmpeg/nv-codec-headers/blob/n12.0.16.1/include/ffnvcodec/nvEncodeAPI.h

The library loads NVENC and the CUDA driver at run time, so the header is all
the build needs. API 12.0 runs on NVIDIA drivers 522 and newer on Windows, and
520 and newer on Linux. Moving to a newer header raises that minimum.
