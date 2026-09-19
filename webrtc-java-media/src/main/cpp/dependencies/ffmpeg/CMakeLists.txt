cmake_minimum_required(VERSION 3.13)
project(ffmpeg)

#
# Builds the FFmpeg libraries this module links against, from the
# third-party/ffmpeg submodule, and exposes them as the imported target
# "ffmpeg".
#
# The build runs at CMake configure time and installs into FFMPEG_INSTALL_DIR,
# the way the WebRTC dependency of webrtc-jni does. A configure that finds the
# libraries already installed there skips the whole thing, so only the first
# build of a platform pays for it.
#
# The configuration is deliberately LGPL only: no --enable-gpl and no
# --enable-nonfree, and the libraries are shared so that they can be replaced,
# which is what the LGPL asks of us. Only the demuxers, decoders, parsers and
# protocols this module actually plays are enabled; everything else is off, to
# keep the shipped libraries small.
#

set(FFMPEG_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third-party/ffmpeg")
get_filename_component(FFMPEG_SOURCE_DIR "${FFMPEG_SOURCE_DIR}" ABSOLUTE)

if(NOT DEFINED FFMPEG_INSTALL_DIR OR FFMPEG_INSTALL_DIR STREQUAL "")
    message(FATAL_ERROR "FFMPEG_INSTALL_DIR is not set")
endif()

file(MAKE_DIRECTORY "${FFMPEG_INSTALL_DIR}")

# The libraries this module needs, in dependency order. The order matters
# twice: for the link line, and for the order the Java side loads them in.
set(FFMPEG_LIBS avutil swresample swscale avcodec avformat)

#
# Decides whether FFmpeg has to be built, by looking for one of its headers
# and one of its libraries in the install directory.
#
function(ffmpeg_is_installed RESULT)
    if(NOT EXISTS "${FFMPEG_INSTALL_DIR}/include/libavformat/avformat.h")
        set(${RESULT} FALSE PARENT_SCOPE)
        return()
    endif()

    file(GLOB FOUND_LIBS
        "${FFMPEG_INSTALL_DIR}/lib/avformat*"
        "${FFMPEG_INSTALL_DIR}/lib/libavformat*"
        "${FFMPEG_INSTALL_DIR}/bin/avformat*")

    if(FOUND_LIBS)
        set(${RESULT} TRUE PARENT_SCOPE)
    else()
        set(${RESULT} FALSE PARENT_SCOPE)
    endif()
endfunction()

ffmpeg_is_installed(FFMPEG_INSTALLED)

if(FFMPEG_INSTALLED)
    message(STATUS "FFmpeg found in ${FFMPEG_INSTALL_DIR}, skipping build")
else()
    if(NOT EXISTS "${FFMPEG_SOURCE_DIR}/configure")
        message(FATAL_ERROR
            "The FFmpeg submodule is not checked out. Run:\n"
            "  git submodule update --init --depth 1 webrtc-java-media/third-party/ffmpeg")
    endif()

    # FFmpeg's configure is a shell script, so a POSIX shell is needed even to
    # build with MSVC. On Windows that means an MSYS2 shell; Git for Windows'
    # bash does not carry make, nasm or pkg-config.
    find_program(BASH_EXECUTABLE NAMES bash)

    if(NOT BASH_EXECUTABLE)
        message(FATAL_ERROR
            "No 'bash' found, which FFmpeg's configure needs. On Windows install MSYS2 and "
            "put its usr/bin on the PATH.")
    endif()

    find_program(MAKE_EXECUTABLE NAMES make gmake mingw32-make)

    if(NOT MAKE_EXECUTABLE)
        message(FATAL_ERROR "No 'make' found, which building FFmpeg needs.")
    endif()

    set(FFMPEG_CONFIGURE_ARGS
        --prefix=${FFMPEG_INSTALL_DIR}
        --disable-static
        --enable-shared
        --enable-pic
        --disable-debug
        --disable-doc
        --disable-programs
        --disable-avdevice
        --disable-avfilter
        --disable-postproc
        --disable-network
        --disable-everything
        --disable-autodetect
        # Containers this module reads.
        --enable-demuxer=mov
        --enable-demuxer=matroska
        --enable-demuxer=avi
        --enable-demuxer=mpegts
        --enable-demuxer=flv
        --enable-demuxer=wav
        --enable-demuxer=mp3
        --enable-demuxer=ogg
        --enable-demuxer=flac
        --enable-demuxer=aac
        # Video this module decodes.
        --enable-decoder=h264
        --enable-decoder=hevc
        --enable-decoder=vp8
        --enable-decoder=vp9
        --enable-decoder=mpeg4
        --enable-decoder=mjpeg
        # Audio this module decodes.
        --enable-decoder=aac
        --enable-decoder=mp3
        --enable-decoder=opus
        --enable-decoder=vorbis
        --enable-decoder=flac
        --enable-decoder=pcm_s16le
        --enable-decoder=pcm_s16be
        --enable-decoder=pcm_u8
        --enable-decoder=pcm_f32le
        # Parsers for the decoders above.
        --enable-parser=h264
        --enable-parser=hevc
        --enable-parser=vp8
        --enable-parser=vp9
        --enable-parser=mpeg4video
        --enable-parser=mjpeg
        --enable-parser=aac
        --enable-parser=mpegaudio
        --enable-parser=opus
        --enable-parser=vorbis
        --enable-parser=flac
        # Local files only for now; network protocols follow once the TLS
        # backends are wired up per platform.
        --enable-protocol=file
        --enable-protocol=pipe
    )

    if(WIN32)
        # FFmpeg builds against the MSVC runtime through its own msvc
        # toolchain support, which keeps the DLLs free of an MSYS2 runtime
        # dependency. configure needs Windows-style output, so cl and link
        # must be on the PATH of the shell that runs it.
        list(APPEND FFMPEG_CONFIGURE_ARGS --toolchain=msvc)
    endif()

    if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$" AND WIN32)
        # No assembler for this target yet; correctness first, speed later.
        list(APPEND FFMPEG_CONFIGURE_ARGS --disable-asm --arch=arm64)
    endif()

    if(APPLE AND CMAKE_OSX_ARCHITECTURES)
        list(APPEND FFMPEG_CONFIGURE_ARGS
            --extra-cflags=-arch\ ${CMAKE_OSX_ARCHITECTURES}
            --extra-ldflags=-arch\ ${CMAKE_OSX_ARCHITECTURES})
    endif()

    # Build out of tree so the submodule working tree stays clean.
    set(FFMPEG_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg-build")
    file(MAKE_DIRECTORY "${FFMPEG_BUILD_DIR}")

    string(REPLACE ";" " " FFMPEG_CONFIGURE_ARGS_STR "${FFMPEG_CONFIGURE_ARGS}")

    message(STATUS "Configuring FFmpeg ${FFMPEG_VERSION} into ${FFMPEG_INSTALL_DIR}")
    message(STATUS "  ${FFMPEG_SOURCE_DIR}/configure ${FFMPEG_CONFIGURE_ARGS_STR}")

    execute_process(
        COMMAND "${BASH_EXECUTABLE}" -c
                "'${FFMPEG_SOURCE_DIR}/configure' ${FFMPEG_CONFIGURE_ARGS_STR}"
        WORKING_DIRECTORY "${FFMPEG_BUILD_DIR}"
        RESULT_VARIABLE CONFIGURE_RESULT
    )

    if(NOT CONFIGURE_RESULT EQUAL 0)
        message(FATAL_ERROR
            "FFmpeg configure failed (${CONFIGURE_RESULT}). See "
            "${FFMPEG_BUILD_DIR}/ffbuild/config.log for what it could not find.")
    endif()

    include(ProcessorCount)
    ProcessorCount(BUILD_JOBS)

    if(BUILD_JOBS EQUAL 0)
        set(BUILD_JOBS 1)
    endif()

    message(STATUS "Building FFmpeg with ${BUILD_JOBS} jobs")

    execute_process(
        COMMAND "${MAKE_EXECUTABLE}" -j${BUILD_JOBS}
        WORKING_DIRECTORY "${FFMPEG_BUILD_DIR}"
        RESULT_VARIABLE BUILD_RESULT
    )

    if(NOT BUILD_RESULT EQUAL 0)
        message(FATAL_ERROR "FFmpeg build failed (${BUILD_RESULT})")
    endif()

    execute_process(
        COMMAND "${MAKE_EXECUTABLE}" install
        WORKING_DIRECTORY "${FFMPEG_BUILD_DIR}"
        RESULT_VARIABLE INSTALL_RESULT
    )

    if(NOT INSTALL_RESULT EQUAL 0)
        message(FATAL_ERROR "FFmpeg install failed (${INSTALL_RESULT})")
    endif()

    ffmpeg_is_installed(FFMPEG_INSTALLED)

    if(NOT FFMPEG_INSTALLED)
        message(FATAL_ERROR
            "FFmpeg reported success but nothing was installed in ${FFMPEG_INSTALL_DIR}")
    endif()
endif()

#
# Expose the installed libraries as one interface target. The import libraries
# are what the link line needs; the runtime libraries are collected separately
# because they have to travel in the platform jar.
#
add_library(ffmpeg INTERFACE)

target_include_directories(ffmpeg INTERFACE "${FFMPEG_INSTALL_DIR}/include")

foreach(LIB ${FFMPEG_LIBS})
    find_library(FFMPEG_${LIB}_LIB
        NAMES ${LIB} lib${LIB}
        PATHS "${FFMPEG_INSTALL_DIR}/lib"
        NO_DEFAULT_PATH)

    if(NOT FFMPEG_${LIB}_LIB)
        message(FATAL_ERROR "Could not find lib${LIB} in ${FFMPEG_INSTALL_DIR}/lib")
    endif()

    target_link_libraries(ffmpeg INTERFACE "${FFMPEG_${LIB}_LIB}")
endforeach()

# The shared libraries to ship. On Windows the DLLs land in bin/, elsewhere in
# lib/ next to the import libraries.
file(GLOB FFMPEG_RUNTIME_LIBS
    "${FFMPEG_INSTALL_DIR}/bin/*.dll"
    "${FFMPEG_INSTALL_DIR}/lib/*.so.*"
    "${FFMPEG_INSTALL_DIR}/lib/*.dylib")

set(FFMPEG_RUNTIME_LIBS "${FFMPEG_RUNTIME_LIBS}" CACHE INTERNAL
    "FFmpeg shared libraries that ship in the platform jar")
