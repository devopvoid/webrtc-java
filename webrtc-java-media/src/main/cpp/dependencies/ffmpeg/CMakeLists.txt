cmake_minimum_required(VERSION 3.13)
project(ffmpeg)

#
# Builds the FFmpeg libraries this module links against, from the
# third-party/ffmpeg submodule, and exposes them as the imported target
# "ffmpeg".
#
# The build runs at CMake configure time and installs into FFMPEG_INSTALL_DIR,
# the way the WebRTC dependency of webrtc-jni does. A configure that finds the
# libraries already installed there, built with the same components, skips the
# whole thing, so only the first build of a platform pays for it.
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

# It arrives from Maven, where the user's home on Windows is a backslash
# path. CMake reads a backslash as the start of an escape, so C:\Users\...
# is not only the wrong separator, it is a parse error waiting to happen.
file(TO_CMAKE_PATH "${FFMPEG_INSTALL_DIR}" FFMPEG_INSTALL_DIR)

file(MAKE_DIRECTORY "${FFMPEG_INSTALL_DIR}")

# The libraries this module needs, in dependency order. The order matters
# twice: for the link line, and for the order the Java side loads them in.
set(FFMPEG_LIBS avutil swresample swscale avcodec avformat)

# The parts of FFmpeg this module plays with. Everything else is left out.
set(FFMPEG_COMPONENTS
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
    # What AVI files carry besides MPEG-4: Microsoft's MPEG-4 variants, which
    # is what an AVI tagged MPG4, MP42 or DIV3 holds.
    --enable-decoder=msmpeg4v1
    --enable-decoder=msmpeg4v2
    --enable-decoder=msmpeg4v3
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
    # Audio common in AVI files and in MPEG transport streams.
    --enable-decoder=mp2
    --enable-decoder=ac3
    # Not only for E-AC-3 itself: the AC-3 decoder calls into its tables, and
    # MSVC, unlike GCC and clang, does not drop those calls when it is left
    # out, so AC-3 alone fails to link on Windows.
    --enable-decoder=eac3
    --enable-decoder=adpcm_ms
    --enable-decoder=adpcm_ima_wav
    # Parsers for the decoders above.
    --enable-parser=h264
    --enable-parser=hevc
    --enable-parser=vp8
    --enable-parser=vp9
    --enable-parser=mpeg4video
    --enable-parser=mjpeg
    --enable-parser=aac
    --enable-parser=mpegaudio
    --enable-parser=ac3
    --enable-parser=opus
    --enable-parser=vorbis
    --enable-parser=flac
    # Local files only for now; network protocols follow once the TLS
    # backends are wired up per platform.
    --enable-protocol=file
    --enable-protocol=pipe
)

# Written next to an install once it is built, naming the components it was
# built with. Without it, an install from before a component was added would
# be taken as good, and a file needing that component would fail to play with
# nothing to say why.
set(FFMPEG_COMPONENTS_FILE "${FFMPEG_INSTALL_DIR}/components.txt")
string(REPLACE ";" "\n" FFMPEG_COMPONENTS_TEXT "${FFMPEG_VERSION};${FFMPEG_COMPONENTS}")

#
# Decides whether FFmpeg has to be built, by looking for one of its headers
# and one of its libraries in the install directory, and for a record of it
# having been built with the components listed above.
#
function(ffmpeg_is_installed RESULT)
    if(NOT EXISTS "${FFMPEG_INSTALL_DIR}/include/libavformat/avformat.h")
        set(${RESULT} FALSE PARENT_SCOPE)
        return()
    endif()

    if(NOT EXISTS "${FFMPEG_COMPONENTS_FILE}")
        set(${RESULT} FALSE PARENT_SCOPE)
        return()
    endif()

    file(READ "${FFMPEG_COMPONENTS_FILE}" INSTALLED_COMPONENTS)

    if(NOT INSTALLED_COMPONENTS STREQUAL FFMPEG_COMPONENTS_TEXT)
        message(STATUS "FFmpeg in ${FFMPEG_INSTALL_DIR} was built with other components, rebuilding")
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
    message(STATUS "FFmpeg ${FFMPEG_VERSION} found in ${FFMPEG_INSTALL_DIR}, skipping build")
else()
    if(NOT EXISTS "${FFMPEG_SOURCE_DIR}/configure")
        message(FATAL_ERROR
            "The FFmpeg submodule is not checked out. Run:\n"
            "  git submodule update --init --depth 1 webrtc-java-media/third-party/ffmpeg")
    endif()

    # FFmpeg's configure and its makefiles are shell scripts, so a POSIX shell
    # is needed even to build with MSVC. On Windows that shell has to be
    # MSYS2's: Git for Windows' bash carries neither make nor nasm, and it is
    # the one a bare find_program(bash) turns up, so look for MSYS2 by path
    # first and only then fall back to the PATH.
    if(WIN32)
        find_program(BASH_EXECUTABLE
            NAMES bash
            PATHS "$ENV{MSYS2_ROOT}/usr/bin"
                  "C:/msys64/usr/bin"
                  "C:/msys2/usr/bin"
                  "C:/tools/msys64/usr/bin"
            NO_DEFAULT_PATH)

        if(NOT BASH_EXECUTABLE)
            message(FATAL_ERROR
                "No MSYS2 bash found, which building FFmpeg on Windows needs. Install MSYS2 "
                "(winget install MSYS2.MSYS2), then inside it run:\n"
                "  pacman -S --needed make nasm diffutils pkgconf\n"
                "Set MSYS2_ROOT if it is not installed in C:/msys64.")
        endif()

        # make and nasm live inside MSYS2 and are only visible from its own
        # shell, so check for them there rather than with find_program.
        get_filename_component(MSYS2_BIN_DIR "${BASH_EXECUTABLE}" DIRECTORY)

        foreach(REQUIRED_TOOL make nasm)
            if(NOT EXISTS "${MSYS2_BIN_DIR}/${REQUIRED_TOOL}.exe")
                message(FATAL_ERROR
                    "MSYS2 has no '${REQUIRED_TOOL}', which building FFmpeg needs. Run inside "
                    "MSYS2:\n  pacman -S --needed make nasm diffutils pkgconf")
            endif()
        endforeach()

        # configure probes the compiler, so the shell it runs in has to have cl
        # and link on its PATH. vcvars is what puts them there, and vswhere
        # ships with every Visual Studio since 2017.
        find_program(VSWHERE_EXECUTABLE
            NAMES vswhere
            HINTS "$ENV{ProgramFiles\(x86\)}/Microsoft Visual Studio/Installer"
                  "C:/Program Files (x86)/Microsoft Visual Studio/Installer")

        if(NOT VSWHERE_EXECUTABLE)
            message(FATAL_ERROR
                "No 'vswhere' found, so Visual Studio could not be located. Building FFmpeg "
                "on Windows needs a Visual Studio with the C++ workload.")
        endif()

        execute_process(
            COMMAND "${VSWHERE_EXECUTABLE}" -latest -products * -property installationPath
            OUTPUT_VARIABLE VS_INSTALL_DIR
            OUTPUT_STRIP_TRAILING_WHITESPACE
        )

        if(NOT VS_INSTALL_DIR)
            message(FATAL_ERROR "vswhere reported no Visual Studio installation.")
        endif()

        file(TO_CMAKE_PATH "${VS_INSTALL_DIR}" VS_INSTALL_DIR)

        # The cross compiler is hosted on x64 in every case this builds for.
        set(FFMPEG_MSVC_HOST_ARCH x64)

        if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
            set(FFMPEG_MSVC_TARGET_ARCH arm64)
            set(FFMPEG_VCVARS "${VS_INSTALL_DIR}/VC/Auxiliary/Build/vcvarsamd64_arm64.bat")
        else()
            set(FFMPEG_MSVC_TARGET_ARCH x64)
            set(FFMPEG_VCVARS "${VS_INSTALL_DIR}/VC/Auxiliary/Build/vcvars64.bat")
        endif()

        if(NOT EXISTS "${FFMPEG_VCVARS}")
            message(FATAL_ERROR
                "No '${FFMPEG_VCVARS}'. Visual Studio is installed without the C++ tools for "
                "this target architecture.")
        endif()

        file(TO_NATIVE_PATH "${FFMPEG_VCVARS}" FFMPEG_VCVARS)
    else()
        find_program(BASH_EXECUTABLE NAMES bash)

        if(NOT BASH_EXECUTABLE)
            message(FATAL_ERROR "No 'bash' found, which FFmpeg's configure needs.")
        endif()

        find_program(MAKE_EXECUTABLE NAMES make gmake)

        if(NOT MAKE_EXECUTABLE)
            message(FATAL_ERROR "No 'make' found, which building FFmpeg needs.")
        endif()
    endif()

    # configure and make are run by a POSIX shell, which on Windows means MSYS2
    # and its /c/... form of a path rather than C:/...
    # A function rather than a macro on purpose: a macro substitutes its
    # arguments into the body as text, so a path still holding a backslash
    # would be parsed as an escape sequence instead of passed as a value.
    function(to_shell_path PATH_IN RESULT)
        set(CONVERTED "${PATH_IN}")

        if(WIN32)
            string(REGEX REPLACE "^([A-Za-z]):/" "/\\1/" CONVERTED "${CONVERTED}")
        endif()

        set(${RESULT} "${CONVERTED}" PARENT_SCOPE)
    endfunction()

    to_shell_path("${FFMPEG_INSTALL_DIR}" FFMPEG_INSTALL_DIR_SH)
    to_shell_path("${FFMPEG_SOURCE_DIR}" FFMPEG_SOURCE_DIR_SH)

    set(FFMPEG_CONFIGURE_ARGS
        --prefix=${FFMPEG_INSTALL_DIR_SH}
        --disable-static
        --enable-shared
        --enable-pic
        --disable-debug
        --disable-doc
        --disable-programs
        --disable-avdevice
        --disable-avfilter
        --disable-network
        --disable-everything
        --disable-autodetect
        ${FFMPEG_COMPONENTS}
    )

    #
    # What configure has to be told about the target. It is all worked out from
    # what the toolchain file already set, so a target CMake can build for
    # needs no new case here.
    #

    # The architecture under the name FFmpeg knows it by.
    function(ffmpeg_arch_name PROCESSOR RESULT)
        set(NAME "")

        if("${PROCESSOR}" MATCHES "^(x86_64|AMD64|amd64)$")
            set(NAME x86_64)
        elseif("${PROCESSOR}" MATCHES "^(aarch64|arm64|ARM64)$")
            if(APPLE)
                # Apple's clang knows this architecture as arm64 and rejects
                # -arch aarch64 outright, which configure reports only as
                # being unable to create an executable.
                set(NAME arm64)
            else()
                set(NAME aarch64)
            endif()
        elseif("${PROCESSOR}" MATCHES "^(arm|armv7.*|armhf)$")
            set(NAME arm)
        endif()

        set(${RESULT} "${NAME}" PARENT_SCOPE)
    endfunction()

    ffmpeg_arch_name("${CMAKE_SYSTEM_PROCESSOR}" FFMPEG_ARCH)
    ffmpeg_arch_name("${CMAKE_HOST_SYSTEM_PROCESSOR}" FFMPEG_HOST_ARCH)

    if(WIN32)
        set(FFMPEG_TARGET_OS win64)

        # FFmpeg builds against the MSVC runtime through its own msvc toolchain
        # support, which keeps the DLLs free of an MSYS2 runtime dependency.
        # configure needs Windows-style output, so cl and link must be on the
        # PATH of the shell that runs it.
        list(APPEND FFMPEG_CONFIGURE_ARGS --toolchain=msvc)
    elseif(APPLE)
        set(FFMPEG_TARGET_OS darwin)
    else()
        set(FFMPEG_TARGET_OS linux)
    endif()

    if(FFMPEG_ARCH)
        list(APPEND FFMPEG_CONFIGURE_ARGS --arch=${FFMPEG_ARCH})
    endif()

    set(FFMPEG_EXTRA_CFLAGS "")
    set(FFMPEG_EXTRA_LDFLAGS "")

    if(APPLE AND CMAKE_OSX_ARCHITECTURES)
        # On Apple the architecture is chosen by a compiler flag rather than by
        # a different compiler, and the deployment target has to match what the
        # rest of the build asks for or the linker complains about every object.
        set(FFMPEG_EXTRA_CFLAGS "-arch ${CMAKE_OSX_ARCHITECTURES}")
        set(FFMPEG_EXTRA_LDFLAGS "-arch ${CMAKE_OSX_ARCHITECTURES}")

        if(CMAKE_OSX_DEPLOYMENT_TARGET)
            set(FFMPEG_EXTRA_CFLAGS
                "${FFMPEG_EXTRA_CFLAGS} -mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
            set(FFMPEG_EXTRA_LDFLAGS
                "${FFMPEG_EXTRA_LDFLAGS} -mmacosx-version-min=${CMAKE_OSX_DEPLOYMENT_TARGET}")
        endif()
    elseif(NOT WIN32)
        # Elsewhere the target is a compiler flag the toolchain file put into
        # CMAKE_C_FLAGS, which is exactly what FFmpeg needs to be handed too.
        set(FFMPEG_EXTRA_CFLAGS "${CMAKE_C_FLAGS}")
        set(FFMPEG_EXTRA_LDFLAGS "${CMAKE_C_FLAGS}")
    endif()

    if(CMAKE_SYSROOT)
        list(APPEND FFMPEG_CONFIGURE_ARGS --sysroot=${CMAKE_SYSROOT})
    endif()

    # CMake reports a cross build whenever a toolchain file names a system,
    # even one that matches the host, and every toolchain file here does. What
    # decides this for FFmpeg is narrower: whether it can run what it builds,
    # which is a question about the architecture and the operating system.
    set(FFMPEG_CROSS_COMPILING FALSE)

    if(CMAKE_CROSSCOMPILING)
        if(NOT CMAKE_SYSTEM_NAME STREQUAL CMAKE_HOST_SYSTEM_NAME
                OR NOT FFMPEG_ARCH STREQUAL FFMPEG_HOST_ARCH)
            set(FFMPEG_CROSS_COMPILING TRUE)
        endif()
    endif()

    if(FFMPEG_CROSS_COMPILING)
        list(APPEND FFMPEG_CONFIGURE_ARGS
            --enable-cross-compile
            --target-os=${FFMPEG_TARGET_OS})

        # make install strips what it installs, with whichever strip is on
        # the PATH, and the host's cannot read a binary built for another
        # architecture: "unable to recognise the format of the input file".
        # Shipping unstripped costs a little size and nothing else.
        list(APPEND FFMPEG_CONFIGURE_ARGS --disable-stripping)

        # An assembler for a foreign architecture is a build prerequisite of
        # its own, and this module is not fast enough to need one yet.
        # Correctness on every platform first, speed where it is measured.
        if(FFMPEG_ARCH STREQUAL "aarch64" OR FFMPEG_ARCH STREQUAL "arm")
            list(APPEND FFMPEG_CONFIGURE_ARGS --disable-asm)
        endif()
    endif()

    # The compiler CMake settled on, so that FFmpeg is built by the same one as
    # the rest of this module rather than by whatever configure finds first.
    #
    # Not on Apple. There CMake names the compiler inside the toolchain, and
    # calling that directly skips the /usr/bin/cc shim that runs xcrun to
    # point SDKROOT at an SDK. Without one there is no libSystem to link
    # against, which configure reports only as being unable to create an
    # executable. Its own default, plain "cc", goes through the shim.
    if(NOT WIN32 AND NOT APPLE AND CMAKE_C_COMPILER)
        list(APPEND FFMPEG_CONFIGURE_ARGS --cc=${CMAKE_C_COMPILER})
    endif()

    # These carry spaces, so they are quoted here rather than in the script.
    if(NOT FFMPEG_EXTRA_CFLAGS STREQUAL "")
        string(STRIP "${FFMPEG_EXTRA_CFLAGS}" FFMPEG_EXTRA_CFLAGS)
        list(APPEND FFMPEG_CONFIGURE_ARGS "--extra-cflags=\"${FFMPEG_EXTRA_CFLAGS}\"")
    endif()
    if(NOT FFMPEG_EXTRA_LDFLAGS STREQUAL "")
        string(STRIP "${FFMPEG_EXTRA_LDFLAGS}" FFMPEG_EXTRA_LDFLAGS)
        list(APPEND FFMPEG_CONFIGURE_ARGS "--extra-ldflags=\"${FFMPEG_EXTRA_LDFLAGS}\"")
    endif()

    # Build out of tree so the submodule working tree stays clean.
    set(FFMPEG_BUILD_DIR "${CMAKE_CURRENT_BINARY_DIR}/ffmpeg-build")

    # A rebuild starts from nothing. make install only adds to the install
    # directory, so the libraries of an earlier FFmpeg would stay beside the
    # new ones, under the older major version in their names, and be packaged
    # into the jar with them. The install directory is only cleared when the
    # record of an earlier build of ours shows it is ours to clear, since it
    # can be pointed anywhere. The build directory always belongs to this
    # build, and objects compiled from another release are no use to it.
    if(EXISTS "${FFMPEG_COMPONENTS_FILE}")
        message(STATUS "Clearing the earlier FFmpeg build in ${FFMPEG_INSTALL_DIR}")
        file(REMOVE_RECURSE "${FFMPEG_INSTALL_DIR}")
    endif()

    file(REMOVE_RECURSE "${FFMPEG_BUILD_DIR}")
    file(MAKE_DIRECTORY "${FFMPEG_INSTALL_DIR}" "${FFMPEG_BUILD_DIR}")
    to_shell_path("${FFMPEG_BUILD_DIR}" FFMPEG_BUILD_DIR_SH)

    string(REPLACE ";" " " FFMPEG_CONFIGURE_ARGS_STR "${FFMPEG_CONFIGURE_ARGS}")

    include(ProcessorCount)
    ProcessorCount(BUILD_JOBS)

    if(BUILD_JOBS EQUAL 0)
        set(BUILD_JOBS 1)
    endif()

    #
    # configure, make and make install go into one shell script rather than
    # three execute_process calls, because on Windows all three have to run in
    # the same MSYS2 shell, and that shell has to be entered through vcvars so
    # that FFmpeg's configure can find cl and link.
    #
    set(FFMPEG_SHELL_PREAMBLE "")

    if(WIN32)
        # MSYS2's own make, reached from inside its shell.
        set(FFMPEG_MAKE make)

        # MSYS2 ships a coreutils "link" in /usr/bin that otherwise shadows
        # MSVC's linker, and FFmpeg's configure then reports that the C
        # compiler cannot create executables. Putting the MSVC toolchain
        # first is what keeps that from happening.
        set(FFMPEG_SHELL_PREAMBLE
"if [ -n \"$VCToolsInstallDir\" ]; then
    export PATH=\"$(cygpath -u \"$VCToolsInstallDir\")/bin/Host${FFMPEG_MSVC_HOST_ARCH}/${FFMPEG_MSVC_TARGET_ARCH}:$PATH\"
fi
")
    else()
        set(FFMPEG_MAKE "${MAKE_EXECUTABLE}")
    endif()

    set(FFMPEG_BUILD_SCRIPT "${CMAKE_CURRENT_BINARY_DIR}/build-ffmpeg.sh")

    file(WRITE "${FFMPEG_BUILD_SCRIPT}"
"# Generated by CMake. Builds FFmpeg ${FFMPEG_VERSION} for webrtc-java-media.
set -e
${FFMPEG_SHELL_PREAMBLE}cd \"${FFMPEG_BUILD_DIR_SH}\"
\"${FFMPEG_SOURCE_DIR_SH}/configure\" ${FFMPEG_CONFIGURE_ARGS_STR}
\"${FFMPEG_MAKE}\" -j${BUILD_JOBS}
\"${FFMPEG_MAKE}\" install
")

    if(WIN32)
        to_shell_path("${FFMPEG_BUILD_SCRIPT}" FFMPEG_BUILD_SCRIPT_SH)

        # vcvars is a batch file, so entering it needs cmd, and the shell it
        # hands over to has to inherit the PATH it set up.
        set(FFMPEG_LAUNCHER "${CMAKE_CURRENT_BINARY_DIR}/build-ffmpeg.cmd")

        file(WRITE "${FFMPEG_LAUNCHER}"
"@echo off
call \"${FFMPEG_VCVARS}\" >nul
if errorlevel 1 exit /b 1
set MSYS2_PATH_TYPE=inherit
\"${BASH_EXECUTABLE}\" -l \"${FFMPEG_BUILD_SCRIPT_SH}\"
")

        file(TO_NATIVE_PATH "${FFMPEG_LAUNCHER}" FFMPEG_LAUNCHER_NATIVE)
        set(FFMPEG_BUILD_COMMAND cmd /c "${FFMPEG_LAUNCHER_NATIVE}")
    else()
        set(FFMPEG_BUILD_COMMAND "${BASH_EXECUTABLE}" "${FFMPEG_BUILD_SCRIPT}")
    endif()

    message(STATUS "Building FFmpeg ${FFMPEG_VERSION} into ${FFMPEG_INSTALL_DIR} with ${BUILD_JOBS} jobs")
    message(STATUS "  configure ${FFMPEG_CONFIGURE_ARGS_STR}")

    execute_process(
        COMMAND ${FFMPEG_BUILD_COMMAND}
        WORKING_DIRECTORY "${FFMPEG_BUILD_DIR}"
        RESULT_VARIABLE BUILD_RESULT
    )

    if(NOT BUILD_RESULT EQUAL 0)
        # configure says almost nothing on the console and writes the actual
        # reason into config.log, which on a build machine nobody can reach is
        # the same as writing it nowhere. The tail of it goes to the console.
        set(FFMPEG_CONFIG_LOG "${FFMPEG_BUILD_DIR}/ffbuild/config.log")

        if(EXISTS "${FFMPEG_CONFIG_LOG}")
            file(READ "${FFMPEG_CONFIG_LOG}" FFMPEG_CONFIG_LOG_TEXT)
            string(LENGTH "${FFMPEG_CONFIG_LOG_TEXT}" FFMPEG_CONFIG_LOG_LENGTH)

            if(FFMPEG_CONFIG_LOG_LENGTH GREATER 6000)
                math(EXPR FFMPEG_CONFIG_LOG_OFFSET "${FFMPEG_CONFIG_LOG_LENGTH} - 6000")
                string(SUBSTRING "${FFMPEG_CONFIG_LOG_TEXT}"
                        ${FFMPEG_CONFIG_LOG_OFFSET} -1 FFMPEG_CONFIG_LOG_TEXT)
            endif()

            message("---- tail of ${FFMPEG_CONFIG_LOG} ----")
            message("${FFMPEG_CONFIG_LOG_TEXT}")
            message("---- end of config.log ----")
        endif()

        message(FATAL_ERROR
            "Building FFmpeg failed (${BUILD_RESULT}). The script that ran is "
            "${FFMPEG_BUILD_SCRIPT}.")
    endif()

    file(WRITE "${FFMPEG_COMPONENTS_FILE}" "${FFMPEG_COMPONENTS_TEXT}")

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
    # An MSVC build of FFmpeg puts the import libraries next to the DLLs in
    # bin/ and leaves only the .def files in lib/, so both have to be searched.
    find_library(FFMPEG_${LIB}_LIB
        NAMES ${LIB} lib${LIB}
        PATHS "${FFMPEG_INSTALL_DIR}/lib" "${FFMPEG_INSTALL_DIR}/bin"
        NO_DEFAULT_PATH)

    if(NOT FFMPEG_${LIB}_LIB)
        message(FATAL_ERROR
            "Could not find lib${LIB} in ${FFMPEG_INSTALL_DIR}/lib or ${FFMPEG_INSTALL_DIR}/bin")
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
