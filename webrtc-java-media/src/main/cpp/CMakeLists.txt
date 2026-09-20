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

add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
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
