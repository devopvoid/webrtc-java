cmake_minimum_required(VERSION 3.13)

if(NOT WEBRTC_TOOLCHAIN_FILE STREQUAL "")
    message(STATUS "Toolchain file: ${WEBRTC_TOOLCHAIN_FILE}")
    set(CMAKE_TOOLCHAIN_FILE "${WEBRTC_TOOLCHAIN_FILE}")
endif()

project(webrtc-java)

if(UNIX AND NOT APPLE)
    # Apply the sysroot configuration for the actual build
    if(DEFERRED_SYSROOT)
        # Configure sysroot for all subsequent operations
        set(CMAKE_SYSROOT ${DEFERRED_SYSROOT})
        set(CMAKE_FIND_ROOT_PATH ${DEFERRED_SYSROOT})

        # Set pkg-config environment
        set(ENV{PKG_CONFIG_SYSROOT_DIR} "${DEFERRED_SYSROOT}")
        set(ENV{PKG_CONFIG_PATH} "${DEFERRED_SYSROOT}/usr/lib/pkgconfig:${DEFERRED_SYSROOT}/usr/share/pkgconfig")

        # Restrict library and header searches to sysroot only
        set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
        set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
        set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
    endif()

    set(LINUX TRUE)
endif()

if(APPLE)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
    set(SOURCE_TARGET macos)
elseif(LINUX)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
    set(SOURCE_TARGET linux)
elseif(WIN32)
    set(CMAKE_CXX_FLAGS_RELEASE "/MT")
    set(CMAKE_CXX_FLAGS_DEBUG "/MTd")
    set(SOURCE_TARGET windows)
endif()

if (CMAKE_CXX_COMPILER_ID STREQUAL Clang AND CMAKE_CXX_COMPILER_FRONTEND_VARIANT STREQUAL "MSVC")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /showFilenames")
endif()

add_subdirectory(dependencies/webrtc)
add_subdirectory(dependencies/jni-voithos)

file(GLOB SOURCES_ROOT "src/*.cpp")
file(GLOB SOURCES_API "src/api/*.cpp")
file(GLOB SOURCES_API_AUDIO "src/api/audio/*.cpp")
file(GLOB SOURCES_MEDIA "src/media/*.cpp")
file(GLOB SOURCES_MEDIA_AUDIO "src/media/audio/*.cpp")
file(GLOB SOURCES_MEDIA_AUDIO_OS "src/media/audio/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO "src/media/video/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_CODEC "src/media/video/codec/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_CODEC_OS "src/media/video/codec/${SOURCE_TARGET}/*.cpp")

# NVENC is loaded at run time from the NVIDIA driver, so building it needs its
# header only. The driver exists for Windows and Linux.
if(WIN32 OR LINUX)
    file(GLOB SOURCES_MEDIA_VIDEO_CODEC_NVENC "src/media/video/codec/nvenc/*.cpp")
endif()
# NVDEC the same, for decoding. Only Linux has a decoder built on it so far;
# Windows decodes through Media Foundation.
if(LINUX)
    file(GLOB SOURCES_MEDIA_VIDEO_CODEC_NVDEC "src/media/video/codec/nvdec/*.cpp")
endif()
file(GLOB SOURCES_MEDIA_VIDEO_DESKTOP "src/media/video/desktop/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_DESKTOP_OS "src/media/video/desktop/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_OS "src/media/video/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_PLATFORM "src/platform/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_RTC "src/rtc/*.cpp")

list(APPEND SOURCES
    ${SOURCES_ROOT}
    ${SOURCES_API}
    ${SOURCES_API_AUDIO}
    ${SOURCES_MEDIA}
    ${SOURCES_MEDIA_AUDIO}
    ${SOURCES_MEDIA_AUDIO_OS}
    ${SOURCES_MEDIA_VIDEO}
    ${SOURCES_MEDIA_VIDEO_CODEC}
    ${SOURCES_MEDIA_VIDEO_CODEC_OS}
    ${SOURCES_MEDIA_VIDEO_CODEC_NVENC}
    ${SOURCES_MEDIA_VIDEO_CODEC_NVDEC}
    ${SOURCES_MEDIA_VIDEO_DESKTOP}
    ${SOURCES_MEDIA_VIDEO_DESKTOP_OS}
    ${SOURCES_MEDIA_VIDEO_OS}
    ${SOURCES_PLATFORM}
    ${SOURCES_RTC}
)

add_library(${PROJECT_NAME} SHARED ${SOURCES})

# Introduced since WebRTC branch 7204 and Clang 20.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    if(MSVC)
        # clang-cl on Windows requires the /clang: prefix to avoid interpreting /W... as a warning level flag
        target_compile_options(${PROJECT_NAME} PRIVATE /clang:-Wno-nullability-completeness)
    else()
        target_compile_options(${PROJECT_NAME} PRIVATE -Wno-nullability-completeness)
    endif()
endif()

target_include_directories(${PROJECT_NAME}
    PRIVATE
        include
        include/api
        include/media
        include/rtc
)

if(WIN32 OR LINUX)
    target_include_directories(${PROJECT_NAME} PRIVATE dependencies/nvenc/include)
endif()
if(LINUX)
    # libva is loaded at run time, so building needs its headers only.
    target_include_directories(${PROJECT_NAME} PRIVATE dependencies/libva/include)

    # So is NVDEC, and the CUDA driver it runs on.
    target_include_directories(${PROJECT_NAME} PRIVATE dependencies/nvdec/include)
endif()

set_target_properties(${PROJECT_NAME} PROPERTIES
    OUTPUT_NAME "${PROJECT_NAME}-${OUTPUT_NAME_SUFFIX}"
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    CXX_EXTENSIONS OFF
)

target_link_libraries(${PROJECT_NAME} jni-voithos)
target_link_libraries(${PROJECT_NAME} webrtc)

if(APPLE)
    set_source_files_properties(${SOURCES} PROPERTIES COMPILE_FLAGS "-x objective-c++")
    target_link_options(${PROJECT_NAME} PRIVATE "-ObjC")
    target_link_libraries(${PROJECT_NAME} "-framework Foundation" "-framework AVFoundation" "-framework CoreMedia" "-framework CoreAudio" "-framework IOKit" "-framework CoreVideo" "-framework VideoToolbox" "-framework QuartzCore")
elseif(LINUX)
    set(CXX_LIBS "-static-libgcc -stdlib=libc++ -lc++ -lc++abi")

    target_link_libraries(${PROJECT_NAME} ${CXX_LIBS})
    target_link_libraries(${PROJECT_NAME} dl)
elseif(WIN32)
    target_link_libraries(${PROJECT_NAME} d3d11.lib dwmapi.lib dxguid.lib mf.lib mfreadwrite.lib mfplat.lib mfuuid.lib shcore.lib)
endif()

install(TARGETS ${PROJECT_NAME}
    RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
    LIBRARY DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
)

#
# The native library links WebRTC statically, and with it the third-party
# code WebRTC builds in. Their licenses ask for their text to travel with the
# binary, so it goes into the platform jar, under META-INF where it cannot
# collide with anything on a classpath.
#
# LICENSE.md is generated from the WebRTC build rather than written by hand,
# so that it lists exactly what is linked in. Regenerate it whenever
# webrtc.branch changes, from the WebRTC checkout (depot_tools on the PATH):
#
#   python tools_webrtc/libs/generate_licenses.py --target //:webrtc <out> out/<cpu>
#
# The tool knows no license for ffmpeg and openh264, which rtc_use_h264 pulls
# in, and stops; map them to third_party/ffmpeg/COPYING.LGPLv2.1 and
# third_party/openh264/src/LICENSE in its LIB_TO_LICENSES_DICT first.
# PATENTS is WebRTC's additional patent grant, which the tool leaves out.
#
install(FILES
    "${CMAKE_CURRENT_SOURCE_DIR}/dependencies/webrtc/licenses/LICENSE.md"
    "${CMAKE_CURRENT_SOURCE_DIR}/dependencies/webrtc/licenses/PATENTS"
    DESTINATION "${CMAKE_INSTALL_PREFIX}/META-INF/licenses/webrtc"
    COMPONENT Runtime
)

if(WIN32 OR LINUX)
    install(FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/dependencies/nvenc/LICENSE"
        DESTINATION "${CMAKE_INSTALL_PREFIX}/META-INF/licenses/nvenc"
        COMPONENT Runtime
    )
endif()
if(LINUX)
    install(FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/dependencies/libva/COPYING"
        DESTINATION "${CMAKE_INSTALL_PREFIX}/META-INF/licenses/libva"
        COMPONENT Runtime
    )
    install(FILES
        "${CMAKE_CURRENT_SOURCE_DIR}/dependencies/nvdec/LICENSE"
        DESTINATION "${CMAKE_INSTALL_PREFIX}/META-INF/licenses/nvdec"
        COMPONENT Runtime
    )
endif()
