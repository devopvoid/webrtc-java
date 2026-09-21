#
# Cross build of the media module for arm-linux-gnueabihf.
#
# Deliberately not webrtc-jni's toolchain file for this target. That one
# compiles with -nostdinc++ and links WebRTC's own libc++, because webrtc-jni
# has to match WebRTC's C++ ABI exactly. This module links no C++ ABI at all,
# only FFmpeg's C interface and the JVM's, so it wants an ordinary compiler
# with an ordinary standard library, which is what the distribution's cross
# toolchain is.
#

set(CMAKE_SYSTEM_NAME       Linux)
set(CMAKE_SYSTEM_PROCESSOR  arm)

set(CMAKE_C_COMPILER        arm-linux-gnueabihf-gcc)
set(CMAKE_CXX_COMPILER      arm-linux-gnueabihf-g++)

# The host's binutils cannot read a binary for this architecture, which shows
# up as "unable to recognise the format of the input file" the moment anything
# tries to strip one.
set(CMAKE_AR                arm-linux-gnueabihf-ar)
set(CMAKE_RANLIB            arm-linux-gnueabihf-ranlib)
set(CMAKE_STRIP             arm-linux-gnueabihf-strip)
