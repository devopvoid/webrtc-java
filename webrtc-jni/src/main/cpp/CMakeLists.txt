cmake_minimum_required(VERSION 3.13)

if(NOT WEBRTC_TOOLCHAIN_FILE STREQUAL "")
	message(STATUS "Toolchain file: ${WEBRTC_TOOLCHAIN_FILE}")
	set(CMAKE_TOOLCHAIN_FILE "${WEBRTC_TOOLCHAIN_FILE}")
endif()

project(webrtc-java)

if(UNIX AND NOT APPLE)
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
file(GLOB SOURCES_MEDIA "src/media/*.cpp")
file(GLOB SOURCES_MEDIA_AUDIO "src/media/audio/*.cpp")
file(GLOB SOURCES_MEDIA_AUDIO_OS "src/media/audio/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO "src/media/video/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_DESKTOP "src/media/video/desktop/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_DESKTOP_OS "src/media/video/desktop/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_MEDIA_VIDEO_OS "src/media/video/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_PLATFORM "src/platform/${SOURCE_TARGET}/*.cpp")
file(GLOB SOURCES_RTC "src/rtc/*.cpp")

list(APPEND SOURCES
	${SOURCES_ROOT}
	${SOURCES_API}
	${SOURCES_MEDIA}
	${SOURCES_MEDIA_AUDIO}
	${SOURCES_MEDIA_AUDIO_OS}
	${SOURCES_MEDIA_VIDEO}
	${SOURCES_MEDIA_VIDEO_DESKTOP}
	${SOURCES_MEDIA_VIDEO_DESKTOP_OS}
	${SOURCES_MEDIA_VIDEO_OS}
	${SOURCES_PLATFORM}
	${SOURCES_RTC}
)

add_library(${PROJECT_NAME} SHARED ${SOURCES})

target_include_directories(${PROJECT_NAME}
	PRIVATE
		include
		include/api
		include/media
		include/rtc
)

set_target_properties(${PROJECT_NAME} PROPERTIES
	CXX_STANDARD 20
	CXX_STANDARD_REQUIRED ON
	CXX_EXTENSIONS OFF
)

target_link_libraries(${PROJECT_NAME} jni-voithos)
target_link_libraries(${PROJECT_NAME} webrtc)

if(APPLE)
	set_source_files_properties(${SOURCES} PROPERTIES COMPILE_FLAGS "-x objective-c++")
	target_link_libraries(${PROJECT_NAME} "-framework Foundation" "-framework AVFoundation" "-framework CoreMedia" "-framework CoreAudio" "-framework IOKit")
elseif(LINUX)
	if(NOT TARGET_CPU MATCHES "^arm")
    	set(CXX_LIBS "-static-libgcc -stdlib=libc++ -lc++ -lc++abi")
	else()
		set(CXX_LIBS "-static-libgcc")
	endif()

	target_link_libraries(${PROJECT_NAME} ${CXX_LIBS} pulse udev)
elseif(WIN32)
	target_link_libraries(${PROJECT_NAME} c++.lib msvcprt.lib dwmapi.lib mf.lib mfreadwrite.lib mfplat.lib mfuuid.lib shcore.lib)
endif()

install(TARGETS ${PROJECT_NAME}
	RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
	LIBRARY DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
)
