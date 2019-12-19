cmake_minimum_required(VERSION 3.6)
project(webrtc-java)

if(WIN32)
	set(CMAKE_CXX_FLAGS_RELEASE "/MT")
	set(CMAKE_CXX_FLAGS_DEBUG "/MTd")
endif()

add_subdirectory(dependencies/webrtc)
add_subdirectory(dependencies/jni-voithos)

file(GLOB_RECURSE SOURCES "src/*.cpp")

add_library(${PROJECT_NAME} SHARED ${SOURCES})

target_include_directories(${PROJECT_NAME} PRIVATE
	${WEBRTC_INC}
	include
	include/api
	include/media
	include/rtc
)

set_target_properties(${PROJECT_NAME} PROPERTIES
	CXX_STANDARD 14
	CXX_STANDARD_REQUIRED ON
	CXX_EXTENSIONS OFF
)

if(APPLE)
	target_compile_definitions(${PROJECT_NAME} PUBLIC WEBRTC_MAC WEBRTC_POSIX)
	target_link_libraries(${PROJECT_NAME} "-framework Foundation" "-framework AVFoundation" "-framework CoreGraphics" "-framework CoreAudio" "-framework AudioToolbox")
elseif(LINUX)
	target_compile_definitions(${PROJECT_NAME} PUBLIC WEBRTC_LINUX)
elseif(WIN32)
	target_compile_definitions(${PROJECT_NAME} PUBLIC WEBRTC_WIN NOMINMAX WIN32_LEAN_AND_MEAN)
	target_link_libraries(${PROJECT_NAME} D3D11 DXGI user32 gdi32 iphlpapi dmoguids msdmo secur32 strmiids winmm wmcodecdspuuid ws2_32)
endif()

target_link_libraries(${PROJECT_NAME} jni-voithos)
target_link_libraries(${PROJECT_NAME} ${WEBRTC_LIB})

install(TARGETS ${PROJECT_NAME}
	RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
	LIBRARY DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
)