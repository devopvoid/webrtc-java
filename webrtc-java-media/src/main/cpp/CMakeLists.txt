cmake_minimum_required(VERSION 3.13)

#
# A cross build is driven by a toolchain file, the same ones webrtc-jni uses:
# they are where this project says which compiler and which sysroot a target is
# built with, and having two answers to that would only let them drift apart.
# It has to be set before project(), which is why it arrives under a name of
# its own rather than as CMAKE_TOOLCHAIN_FILE.
#
if(DEFINED MEDIA_TOOLCHAIN_FILE AND NOT MEDIA_TOOLCHAIN_FILE STREQUAL "")
    message(STATUS "Toolchain file: ${MEDIA_TOOLCHAIN_FILE}")
    set(CMAKE_TOOLCHAIN_FILE "${MEDIA_TOOLCHAIN_FILE}")
endif()

project(webrtc-java-media CXX)

# The toolchain files find a sysroot but leave it to be applied here, after
# project(), because setting it earlier breaks CMake's own compiler checks.
if(DEFINED DEFERRED_SYSROOT AND DEFERRED_SYSROOT)
    message(STATUS "Sysroot: ${DEFERRED_SYSROOT}")
    set(CMAKE_SYSROOT ${DEFERRED_SYSROOT})
    set(CMAKE_FIND_ROOT_PATH ${DEFERRED_SYSROOT})
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(UNIX AND NOT APPLE)
    set(LINUX TRUE)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
endif()

if(APPLE)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
elseif(WIN32)
    # Match the runtime the rest of the project's native code uses.
    set(CMAKE_CXX_FLAGS_RELEASE "/MT")
    set(CMAKE_CXX_FLAGS_DEBUG "/MTd")
endif()

find_package(JNI REQUIRED)

add_subdirectory(dependencies/ffmpeg)

file(GLOB SOURCES "src/*.cpp" "src/media/*.cpp")

add_library(${PROJECT_NAME} SHARED ${SOURCES})

target_include_directories(${PROJECT_NAME}
    PRIVATE
        include
        # webrtc_java_api.h: the C interface of webrtc-java's native library.
        # Only the header is used. There is no link dependency between the two
        # libraries; the function table is passed in at runtime.
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../../webrtc-jni/src/main/cpp/include
        ${JNI_INCLUDE_DIRS}
)

target_link_libraries(${PROJECT_NAME} PRIVATE ffmpeg)

if(LINUX)
    #
    # The toolchain file builds against the Chromium sysroot, so that this
    # library needs no newer glibc than webrtc-java's does: a machine that can
    # run one can run the other. It also compiles with -nostdinc++, because
    # everything Linux in this project uses the libc++ that WebRTC bundles
    # rather than whatever the build machine happens to have.
    #
    # That libc++ is installed beside WebRTC itself, so webrtc-jni has to have
    # been built before this module on Linux. It is the one thing this module
    # takes from that build, and it is a C++ standard library, not any part of
    # WebRTC: no WebRTC symbol is linked here, and the two native libraries
    # still meet only through the C function table.
    #
    set(LIBCPP_CONFIG_DIR "${WEBRTC_INSTALL_DIR}/include/third_party/libc++")
    set(LIBCPP_INCLUDE_DIR "${LIBCPP_CONFIG_DIR}/include")
    set(LIBCPP_ABI_INCLUDE_DIR "${WEBRTC_INSTALL_DIR}/include/third_party/libc++abi/include")

    if(NOT EXISTS "${LIBCPP_INCLUDE_DIR}")
        message(FATAL_ERROR
            "No libc++ in ${LIBCPP_INCLUDE_DIR}. This module builds against the one WebRTC "
            "bundles, so webrtc-jni has to be built first, or WEBRTC_INSTALL_DIR has to point "
            "at where it installed WebRTC.")
    endif()

    # The first carries __config_site, which the headers in the second need.
    target_include_directories(${PROJECT_NAME} PRIVATE "${LIBCPP_CONFIG_DIR}")
    target_include_directories(${PROJECT_NAME} SYSTEM PRIVATE
            "${LIBCPP_INCLUDE_DIR}" "${LIBCPP_ABI_INCLUDE_DIR}")

    # These two belong with those include directories and have to be set here
    # rather than left to the toolchain file, which only the x86_64 one does.
    # webrtc-jni does not notice, because it links the webrtc target and picks
    # them up from its PUBLIC flags; nothing carries them to this module.
    #
    # The bundled libc++ has wanted the hardening mode chosen at configuration
    # time since branch-heads/7977, and without it every translation unit
    # fails in __config before it reaches a line of ours.
    target_compile_definitions(${PROJECT_NAME}
            PRIVATE _LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE)

    # Clang finds the GCC installation inside the cross sysroots and puts its
    # libstdc++ headers on the implicit search path, where they collide with
    # the bundled libc++. Excluding the implicit C++ directories leaves the
    # bundled headers serving C++ on their own.
    target_compile_options(${PROJECT_NAME} PRIVATE -nostdinc++)

    target_link_directories(${PROJECT_NAME} PRIVATE "${WEBRTC_INSTALL_DIR}/lib")
    target_link_libraries(${PROJECT_NAME} PRIVATE
            -static-libgcc -stdlib=libc++ -lc++ -lc++abi)

    target_link_libraries(${PROJECT_NAME} PRIVATE dl pthread)
endif()

# The Java side loads the library as
# webrtc-java-media-<os>-<arch>, the same scheme NativeLoader uses for the
# core library.
if(DEFINED OUTPUT_NAME_SUFFIX AND NOT OUTPUT_NAME_SUFFIX STREQUAL "")
    set_target_properties(${PROJECT_NAME} PROPERTIES
        OUTPUT_NAME "${PROJECT_NAME}-${OUTPUT_NAME_SUFFIX}")
endif()

#
# Everything that has to end up in the platform jar goes into one directory,
# which the jar plugin picks up as the classes directory of the natives jar.
#
set(NATIVES_DIR "${CMAKE_CURRENT_BINARY_DIR}/../natives")

# The directory is filled afresh on every build, rather than added to: a
# library that is no longer part of the build, such as one of an earlier
# FFmpeg release under its older major version, must not stay in it and end
# up in the jar. The commands below run in the order they are declared.
add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E remove_directory "${NATIVES_DIR}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${NATIVES_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "$<TARGET_FILE:${PROJECT_NAME}>" "${NATIVES_DIR}"
    COMMENT "Collecting the native library for the platform jar")

foreach(RUNTIME_LIB ${FFMPEG_RUNTIME_LIBS})
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
                "${RUNTIME_LIB}" "${NATIVES_DIR}"
        COMMENT "Collecting ${RUNTIME_LIB} for the platform jar")
endforeach()

#
# The LGPL asks for its text to travel with the FFmpeg libraries, along with
# a notice saying what they are and where their source is. Both go under
# META-INF, where they cannot collide with anything on a classpath.
#
set(FFMPEG_LICENSE_FILE "${CMAKE_CURRENT_SOURCE_DIR}/../../../third-party/ffmpeg/COPYING.LGPLv2.1")

if(NOT EXISTS "${FFMPEG_LICENSE_FILE}")
    message(FATAL_ERROR
        "${FFMPEG_LICENSE_FILE} is missing, and the platform jar must not ship FFmpeg "
        "without it. Check out the FFmpeg submodule.")
endif()

set(FFMPEG_NOTICE_FILE "${CMAKE_CURRENT_BINARY_DIR}/FFmpeg-NOTICE.txt")
configure_file("${CMAKE_CURRENT_SOURCE_DIR}/dependencies/ffmpeg/FFmpeg-NOTICE.txt.in"
    "${FFMPEG_NOTICE_FILE}" @ONLY)

set(FFMPEG_LICENSES_DIR "${NATIVES_DIR}/META-INF/licenses/ffmpeg")

add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${FFMPEG_LICENSES_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${FFMPEG_LICENSE_FILE}" "${FFMPEG_LICENSES_DIR}/COPYING.LGPLv2.1"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${FFMPEG_NOTICE_FILE}" "${FFMPEG_LICENSES_DIR}/NOTICE.txt"
    COMMENT "Collecting the FFmpeg license and notice for the platform jar")
