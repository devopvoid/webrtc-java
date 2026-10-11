# Vulkan headers

`include/vulkan` and `include/vk_video` hold the C headers of the Khronos `Vulkan-Headers` at tag
`v1.4.317`, taken unchanged (the C++ headers and modules are left out):

https://github.com/KhronosGroup/Vulkan-Headers/tree/v1.4.317/include

FFmpeg needs them to decode with Vulkan Video on Linux (the `h264_vulkan`, `hevc_vulkan`,
`vp9_vulkan` and `av1_vulkan` hwaccels), which is how the GPUs of Intel and AMD decode, and those of
NVIDIA too. FFmpeg loads the Vulkan loader (`libvulkan.so.1`) with `dlopen` when a device is
created, so the build needs the headers only and a machine without Vulkan loads the libraries all
the same. Distributions ship older headers than FFmpeg wants (Ubuntu 24.04 has 1.3.275), which is
why they are here.

`vulkan.pc.in` is the pkg-config file FFmpeg's configure looks for; CMake fills in the path and puts
the result where configure finds it. Version 1.4.317 is the lowest FFmpeg n8.1 takes for VP9
(`vulkan >= 1.4.317`; the others need 1.3.277).

`LICENSE.md` and `LICENSES` are those of the repository: the headers are under the Apache License
2.0 or the MIT license. They are installed into the Linux platform jars under
`META-INF/licenses/vulkan`.
