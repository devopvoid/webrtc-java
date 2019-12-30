cmake_minimum_required(VERSION 3.13)

if(NOT DEFINED TARGET_CPU)
	if(CMAKE_SIZEOF_VOID_P EQUAL 4)
		set(TARGET_CPU "x86")
	elseif(CMAKE_SIZEOF_VOID_P EQUAL 8)
		set(TARGET_CPU "x64")
	endif()
endif()

if(NOT DEFINED WEBRTC_BRANCH)
	set(WEBRTC_BRANCH "master")
endif()

set(WEBRTC_DIR $ENV{HOME}/webrtc)
set(WEBRTC_SRC ${WEBRTC_DIR}/src)
set(WEBRTC_INC ${WEBRTC_SRC} ${WEBRTC_SRC}/third_party/abseil-cpp ${WEBRTC_SRC}/third_party/libyuv/include PARENT_SCOPE)
set(WEBRTC_BUILD out/${TARGET_CPU})

if(WIN32)
	set(SHELLCOMMAND cmd /c )
	set(WEBRTC_LIB "${WEBRTC_SRC}/${WEBRTC_BUILD}/obj/webrtc.lib")
elseif(UNIX)
	set(WEBRTC_LIB "${WEBRTC_SRC}/${WEBRTC_BUILD}/obj/libwebrtc.a")
endif()

set(WEBRTC_LIB ${WEBRTC_LIB} PARENT_SCOPE)

find_program(GCLIENT_EXECUTABLE
	NAMES gclient gclient.bat
	DOC "Path to gclient executable"
	HINTS ${DEPOT_TOOLS_PATH} ENV DEPOT_TOOLS_PATH)

find_path(DEPOT_TOOLS_PATH
	NAMES gclient gclient.py gclient.bat
		ninja ninja.exe ninja-linux32 ninja-linux64 ninja-mac
		download_from_google_storage download_from_google_storage.bat
		download_from_google_storage.py
	DOC "Path to depot_tools directory"
	HINTS ${DEPOT_TOOLS_PATH} ENV DEPOT_TOOLS_PATH)

include(${CMAKE_ROOT}/Modules/FindPackageHandleStandardArgs.cmake)
find_package_handle_standard_args (DepotTools
	REQUIRED_VARS GCLIENT_EXECUTABLE DEPOT_TOOLS_PATH
	FAIL_MESSAGE "Could not find depot_tools.")


if(EXISTS "${WEBRTC_LIB}")
	message(STATUS "WebRTC: Nothing to do")
	return()
endif()

file(MAKE_DIRECTORY ${WEBRTC_DIR})

message(STATUS "WebRTC: fetch")

execute_process(
	COMMAND ${SHELLCOMMAND} fetch --nohooks webrtc
	WORKING_DIRECTORY ${WEBRTC_DIR}
	ERROR_FILE error.txt
	OUTPUT_FILE output.txt
)
execute_process(
	COMMAND ${SHELLCOMMAND} gclient sync
	WORKING_DIRECTORY ${WEBRTC_DIR}
	ERROR_FILE error.txt
	OUTPUT_FILE output.txt
)

message(STATUS "WebRTC: checkout \"${WEBRTC_BRANCH}\"")

execute_process(
	COMMAND ${SHELLCOMMAND} git checkout ${WEBRTC_BRANCH}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	ERROR_FILE ../error.txt
	OUTPUT_FILE ../output.txt
)

message(STATUS "WebRTC: generate")

set(COMPILE_ARGS "is_debug=false target_cpu=\"${TARGET_CPU}\" rtc_include_tests=false use_rtti=true use_custom_libcxx=false symbol_level=0")

execute_process(
	COMMAND ${SHELLCOMMAND} gn clean ${WEBRTC_BUILD}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	ERROR_FILE ../error.txt
	OUTPUT_FILE ../output.txt
)
execute_process(
	COMMAND ${SHELLCOMMAND} gn gen ${WEBRTC_BUILD} --args=${COMPILE_ARGS}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	ERROR_FILE ../error.txt
	OUTPUT_FILE ../output.txt
)

message(STATUS "WebRTC: compile")

execute_process(
	COMMAND ${SHELLCOMMAND} ninja -C ${WEBRTC_BUILD}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	ERROR_FILE ../error.txt
	OUTPUT_FILE ../output.txt
)
