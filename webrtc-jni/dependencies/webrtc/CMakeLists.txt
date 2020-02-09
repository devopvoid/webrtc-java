cmake_minimum_required(VERSION 3.13)

find_program(GCLIENT_EXECUTABLE
	NAMES gclient gclient.bat
	DOC "Path to gclient executable"
	HINTS ${DEPOT_TOOLS_PATH} ENV DEPOT_TOOLS_PATH)

find_path(DEPOT_TOOLS_PATH
	NAMES gclient gclient.py gclient.bat
		ninja ninja.exe ninja-linux32 ninja-linux64 ninja-mac
		download_from_google_storage download_from_google_storage.bat
		download_from_google_storage.py
	DOC "Path to depot tools directory"
	HINTS ${DEPOT_TOOLS_PATH} ENV DEPOT_TOOLS_PATH)

include(${CMAKE_ROOT}/Modules/FindPackageHandleStandardArgs.cmake)
find_package_handle_standard_args (DepotTools
	REQUIRED_VARS GCLIENT_EXECUTABLE DEPOT_TOOLS_PATH
	FAIL_MESSAGE "Could not find depot tools")

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

set(WEBRTC_DIR ${WEBRTC_SRC_DIR})
set(WEBRTC_SRC ${WEBRTC_DIR}/src)
set(WEBRTC_INC ${WEBRTC_SRC} ${WEBRTC_SRC}/third_party/abseil-cpp ${WEBRTC_SRC}/third_party/libyuv/include PARENT_SCOPE)
set(WEBRTC_BUILD out/${TARGET_CPU})

message(STATUS "WebRTC checkout path: ${WEBRTC_DIR}")
message(STATUS "WebRTC checkout branch: ${WEBRTC_BRANCH}")
message(STATUS "WebRTC target CPU: ${TARGET_CPU}")

if(WIN32)
	set(SHELLCOMMAND cmd /c )
	set(WEBRTC_LIB "${WEBRTC_SRC}/${WEBRTC_BUILD}/obj/webrtc.lib")
elseif(UNIX)
	set(WEBRTC_LIB "${WEBRTC_SRC}/${WEBRTC_BUILD}/obj/libwebrtc.a")
endif()

set(WEBRTC_LIB ${WEBRTC_LIB} PARENT_SCOPE)

if(EXISTS "${WEBRTC_LIB}")
	message(STATUS "WebRTC: Nothing to do")
	return()
endif()

file(MAKE_DIRECTORY ${WEBRTC_DIR})

message(STATUS "WebRTC: fetch")
execute_process(
	COMMAND ${SHELLCOMMAND} fetch --no-history --nohooks webrtc
	WORKING_DIRECTORY ${WEBRTC_DIR}
	OUTPUT_VARIABLE FETCH_OUTPUT
	ERROR_VARIABLE FETCH_ERROR
)
message(STATUS "${FETCH_OUTPUT}")
message(STATUS "${FETCH_ERROR}")

# Patch DEPS to reduce time running gclient
file(READ "${WEBRTC_SRC}/DEPS" DEPS_CONTENT)
string(REPLACE
	"'src/resources'],"
	"'src/resources'],'condition': 'rtc_include_tests==true',"
	DEPS_CONTENT
	"${DEPS_CONTENT}")
file(WRITE "${WEBRTC_SRC}/DEPS" "${DEPS_CONTENT}")

message(STATUS "WebRTC: sync")
execute_process(
	COMMAND ${SHELLCOMMAND} gclient sync
	WORKING_DIRECTORY ${WEBRTC_DIR}
    OUTPUT_VARIABLE SYNC_OUTPUT
	ERROR_VARIABLE SYNC_ERROR
)
message(STATUS "${SYNC_OUTPUT}")
message(STATUS "${SYNC_ERROR}")

message(STATUS "WebRTC: checkout \"${WEBRTC_BRANCH}\"")
execute_process(
	COMMAND ${SHELLCOMMAND} git checkout .
	WORKING_DIRECTORY ${WEBRTC_SRC}
)
execute_process(
	COMMAND ${SHELLCOMMAND} git checkout ${WEBRTC_BRANCH}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	OUTPUT_VARIABLE CHECKOUT_OUTPUT
	ERROR_VARIABLE CHECKOUT_ERROR
)
message(STATUS "${CHECKOUT_OUTPUT}")
message(STATUS "${CHECKOUT_ERROR}")

message(STATUS "WebRTC: generate")

set(COMPILE_ARGS "is_debug=false target_cpu=\"${TARGET_CPU}\" rtc_include_tests=false use_rtti=true use_custom_libcxx=false symbol_level=0")

execute_process(
	COMMAND ${SHELLCOMMAND} gn gen ${WEBRTC_BUILD} --args=${COMPILE_ARGS}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	OUTPUT_VARIABLE GEN_OUTPUT
	ERROR_VARIABLE GEN_ERROR
)
message(STATUS "${GEN_OUTPUT}")
message(STATUS "${GEN_ERROR}")

message(STATUS "WebRTC: compile")

execute_process(
	COMMAND ${SHELLCOMMAND} ninja -C ${WEBRTC_BUILD}
	WORKING_DIRECTORY ${WEBRTC_SRC}
	OUTPUT_VARIABLE COMPILE_OUTPUT
	ERROR_VARIABLE COMPILE_ERROR
)
message(STATUS "${COMPILE_OUTPUT}")
message(STATUS "${COMPILE_ERROR}")
