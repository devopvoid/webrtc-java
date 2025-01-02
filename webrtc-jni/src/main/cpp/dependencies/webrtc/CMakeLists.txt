cmake_minimum_required(VERSION 3.13)
project(webrtc)

if(LINUX)
	# Used for Linux sysroot installation
	find_package(Python3 REQUIRED COMPONENTS Interpreter)
endif()

FILE(WRITE ${CMAKE_CURRENT_BINARY_DIR}/pseudo.hxx
"template<class T> class pseudo{}\n")
FILE(WRITE ${CMAKE_CURRENT_BINARY_DIR}/pseudo.cxx
"static void pseudo(void){}\n")

add_library(${PROJECT_NAME} STATIC pseudo.cxx pseudo.hxx)

if(NOT DEFINED TARGET_CPU)
	if(CMAKE_SIZEOF_VOID_P EQUAL 4)
		set(TARGET_CPU "x86")
	elseif(CMAKE_SIZEOF_VOID_P EQUAL 8)
		set(TARGET_CPU "x64")
	endif()

	if(${CMAKE_SYSTEM_PROCESSOR} MATCHES "arm64")
		set(TARGET_CPU "${CMAKE_SYSTEM_PROCESSOR}")
	endif()
endif()

if(NOT DEFINED WEBRTC_BRANCH)
	set(WEBRTC_BRANCH "master")
endif()

if(NOT DEFINED WEBRTC_SRC_DIR)
	if(DEFINED ENV{CTEST_REAL_HOME})
		set(WEBRTC_SRC_DIR "$ENV{CTEST_REAL_HOME}")
	else()
		set(WEBRTC_SRC_DIR "$ENV{HOME}")
	endif()

	if(NOT WEBRTC_SRC_DIR AND WIN32)
		file(TO_CMAKE_PATH "$ENV{USERPROFILE}" WEBRTC_SRC_DIR)
	endif()
endif()

if(NOT DEFINED WEBRTC_INSTALL_DIR)
	set(WEBRTC_INSTALL_DIR "${WEBRTC_SRC_DIR}/build")
endif()

if(WIN32)
	set(ENV{DEPOT_TOOLS_WIN_TOOLCHAIN} 0)
	set(SHELLCOMMAND cmd /c )
	set(WEBRTC_LIB webrtc.lib)
	set(PATH_SEP ";")
elseif(UNIX)
	set(WEBRTC_LIB libwebrtc.a)
	set(PATH_SEP ":")
endif()

function(print text)
	string(STRIP "${text}" text)
	string(REGEX REPLACE "\n$" "" text "${text}")
	string(LENGTH "${text}" TEXT_LENGTH)

	if(TEXT_LENGTH GREATER 0)
		message("${text}")
	endif()
endfunction()

function(execute_command)
	cmake_parse_arguments(CMD_ARGS "" "WORKING_DIRECTORY" "COMMAND" ${ARGN})

	execute_process(
		COMMAND ${SHELLCOMMAND} ${CMD_ARGS_COMMAND}
		WORKING_DIRECTORY "${CMD_ARGS_WORKING_DIRECTORY}"
		OUTPUT_VARIABLE COMMAND_OUTPUT
		ERROR_VARIABLE COMMAND_ERROR
	)

	print("${COMMAND_OUTPUT}")
	print("${COMMAND_ERROR}")
endfunction()

function(sysroot_install)
	message(STATUS "Installing sysroot")

	set(SCRIPT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/linux/sysroot/install-sysroot.py)

	execute_process(
		COMMAND ${Python3_EXECUTABLE} ${SCRIPT_PATH} --arch=${TARGET_CPU}
		WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
		OUTPUT_VARIABLE COMMAND_OUTPUT
		ERROR_VARIABLE COMMAND_ERROR
	)

	print("${COMMAND_OUTPUT}")
    print("${COMMAND_ERROR}")
endfunction()

function(sysroot_link)
	message(STATUS "Retrieving libm.so from sysroot")

	set(SYSROOT_PATH ${CMAKE_CURRENT_SOURCE_DIR}/linux)

	execute_process(
		COMMAND find ${SYSROOT_PATH} -iname libm-*.so
		WORKING_DIRECTORY "${WEBRTC_SRC_DIR}"
		OUTPUT_VARIABLE COMMAND_OUTPUT
		ERROR_VARIABLE COMMAND_ERROR
	)

	string(STRIP ${COMMAND_OUTPUT} LIBM_PATH)

	message(STATUS "Linking libm.so from: ${LIBM_PATH}")

	target_link_libraries(${PROJECT_NAME} "${LIBM_PATH}")
endfunction()

file(TO_CMAKE_PATH "${WEBRTC_SRC_DIR}" WEBRTC_SRC_DIR)

set(WEBRTC_DIR ${WEBRTC_SRC_DIR})
set(WEBRTC_SRC ${WEBRTC_DIR}/src)
set(WEBRTC_BUILD out/${TARGET_CPU})
set(WEBRTC_LIB_PATH ${WEBRTC_SRC}/${WEBRTC_BUILD}/obj/${WEBRTC_LIB})
set(WEBRTC_LIB_PATH_INSTALLED ${WEBRTC_INSTALL_DIR}/lib/${WEBRTC_LIB})

file(TO_CMAKE_PATH "${WEBRTC_DIR}" WEBRTC_DIR)
file(TO_CMAKE_PATH "${WEBRTC_INSTALL_DIR}" WEBRTC_INSTALL_DIR)
file(TO_CMAKE_PATH "${WEBRTC_LIB_PATH}" WEBRTC_LIB_PATH)
file(TO_CMAKE_PATH "${WEBRTC_LIB_PATH_INSTALLED}" WEBRTC_LIB_PATH_INSTALLED)

message(STATUS "WebRTC checkout path: ${WEBRTC_DIR}")
message(STATUS "WebRTC checkout branch: ${WEBRTC_BRANCH}")
message(STATUS "WebRTC target: ${SOURCE_TARGET} ${TARGET_CPU}")
message(STATUS "WebRTC build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "WebRTC install path: ${WEBRTC_INSTALL_DIR}")

if(EXISTS "${WEBRTC_LIB_PATH_INSTALLED}")
	set(TARGET_INC_DIR ${WEBRTC_INSTALL_DIR}/include)
	set(TARGET_LINK_LIB ${WEBRTC_LIB_PATH_INSTALLED})
else()
	set(TARGET_INC_DIR ${WEBRTC_SRC})
	set(TARGET_LINK_LIB ${WEBRTC_LIB_PATH})
endif()

target_include_directories(${PROJECT_NAME}
	PUBLIC
		${TARGET_INC_DIR}
		${TARGET_INC_DIR}/third_party/abseil-cpp
		${TARGET_INC_DIR}/third_party/libyuv/include
)
target_link_libraries(${PROJECT_NAME} ${TARGET_LINK_LIB})

if(APPLE)
	target_compile_definitions(${PROJECT_NAME} PUBLIC WEBRTC_MAC WEBRTC_POSIX)
	target_link_libraries(${PROJECT_NAME} "-framework Foundation" "-framework AVFoundation" "-framework CoreGraphics" "-framework CoreAudio" "-framework AudioToolbox" "-framework IOSurface" "-framework ApplicationServices" "-framework AppKit")
elseif(LINUX)
	# Find DBus
	find_package(PkgConfig QUIET REQUIRED) # Include functions provided by PkgConfig module.
	pkg_check_modules(DBUS REQUIRED dbus-1)

	target_include_directories(${PROJECT_NAME} PUBLIC ${DBUS_INCLUDE_DIRS})
	target_compile_definitions(${PROJECT_NAME} PUBLIC WEBRTC_LINUX WEBRTC_POSIX)
	target_link_libraries(${PROJECT_NAME} X11 dbus-1)
elseif(WIN32)
	target_compile_definitions(${PROJECT_NAME} PUBLIC WEBRTC_WIN NOMINMAX WIN32_LEAN_AND_MEAN)
	target_link_libraries(${PROJECT_NAME} D3D11 DXGI user32 gdi32 iphlpapi dmoguids msdmo secur32 strmiids winmm wmcodecdspuuid ws2_32)
endif()

if(EXISTS "${WEBRTC_LIB_PATH}" OR EXISTS "${WEBRTC_LIB_PATH_INSTALLED}")
	message(STATUS "WebRTC: Compiled version found '${TARGET_LINK_LIB}'")

	if(LINUX)
		sysroot_install()
		sysroot_link()
	endif()

	return()
endif()

if(NOT EXISTS "${WEBRTC_SRC_DIR}/depot_tools")
	message(STATUS "WebRTC: fetch depot tools")
	file(MAKE_DIRECTORY ${WEBRTC_SRC_DIR})
	execute_command(
		COMMAND git clone https://chromium.googlesource.com/chromium/tools/depot_tools.git
		WORKING_DIRECTORY "${WEBRTC_SRC_DIR}"
	)
endif()

set(ENV{PATH} "${WEBRTC_SRC_DIR}/depot_tools${PATH_SEP}$ENV{PATH}")

file(MAKE_DIRECTORY ${WEBRTC_DIR})

message(STATUS "WebRTC: fetch")
execute_command(
	COMMAND fetch --nohooks webrtc
	WORKING_DIRECTORY "${WEBRTC_DIR}"
)

message(STATUS "WebRTC: checkout \"${WEBRTC_BRANCH}\"")
execute_command(
	COMMAND git checkout .
	WORKING_DIRECTORY "${WEBRTC_SRC}"
)
execute_command(
	COMMAND git checkout ${WEBRTC_BRANCH}
	WORKING_DIRECTORY "${WEBRTC_SRC}"
)

message(STATUS "WebRTC: sync")
execute_command(
	COMMAND gclient sync
	WORKING_DIRECTORY "${WEBRTC_DIR}"
)

if(LINUX)
	sysroot_install()
endif()

file(GLOB PATCHES "${CMAKE_CURRENT_LIST_DIR}/patches/${SOURCE_TARGET}/*.patch")

if (PATCHES)
	message(STATUS "WebRTC: patch")

	foreach(PATCH ${PATCHES})
		message(STATUS "Applying ${PATCH}")

		execute_process(
			COMMAND patch -p0 --forward
			WORKING_DIRECTORY "${WEBRTC_SRC_DIR}"
			INPUT_FILE "${PATCH}"
			OUTPUT_VARIABLE OUTPUT
			RESULT_VARIABLE RESULT)

		if (RESULT EQUAL 0)
			message(STATUS "Patch applied: ${PATCH}")
		endif()
	endforeach(PATCH)
endif()

message(STATUS "WebRTC: generate")
set(COMPILE_ARGS "is_debug=false target_cpu=\"${TARGET_CPU}\" treat_warnings_as_errors=false rtc_build_examples=false rtc_include_tests=false use_rtti=true use_custom_libcxx=false symbol_level=0 rtc_use_h264=true")
execute_command(
	COMMAND gn gen ${WEBRTC_BUILD} --args=${COMPILE_ARGS}
	WORKING_DIRECTORY "${WEBRTC_SRC}"
)

message(STATUS "WebRTC: compile")
execute_command(
	COMMAND ninja -C "${WEBRTC_BUILD}"
	WORKING_DIRECTORY "${WEBRTC_SRC}"
)

if(LINUX)
	sysroot_link()
endif()

install(FILES "${WEBRTC_LIB_PATH}" DESTINATION "${WEBRTC_INSTALL_DIR}/lib")
install(
	DIRECTORY "${WEBRTC_SRC}/"
	DESTINATION "${WEBRTC_INSTALL_DIR}/include"
	FILES_MATCHING
		PATTERN "*.h"
		REGEX /src/base/ EXCLUDE
		REGEX /src/build/ EXCLUDE
		REGEX /src/build_overrides/ EXCLUDE
		REGEX /src/buildtools/ EXCLUDE
		REGEX /src/data/ EXCLUDE
		REGEX /src/docs/ EXCLUDE
		REGEX /src/examples/ EXCLUDE
		REGEX /src/out/ EXCLUDE
		REGEX /src/resources/ EXCLUDE
		REGEX /src/sdk/ EXCLUDE
		REGEX /src/stats/ EXCLUDE
		REGEX /src/style-guide/ EXCLUDE
		REGEX /src/test/ EXCLUDE
		REGEX /src/testing/ EXCLUDE
		REGEX "/src/third_party/(a[c-z].*|[b-k]|l[a-h|k-z].*|lib[a-x].*|[m-z])" EXCLUDE
		REGEX /src/tools/ EXCLUDE
		REGEX /src/tools_webrtc/ EXCLUDE
		REGEX /test/ EXCLUDE
)
# Delete empty directories
install(CODE "
	file(GLOB_RECURSE headers LIST_DIRECTORIES true ${WEBRTC_INSTALL_DIR}/include/!*)

	foreach(path \${headers})
		file(GLOB result \"\${path}/*\")
		list(LENGTH result file_count)

		if(file_count EQUAL 0)
			# DIR is empty
			file(REMOVE_RECURSE \${path})
		endif()
	endforeach()
")
