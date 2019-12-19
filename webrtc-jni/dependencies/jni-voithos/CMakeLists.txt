cmake_minimum_required(VERSION 3.6)
project(jni-voithos)

file(GLOB_RECURSE SOURCES "src/*.cpp")

if(APPLE)
	set(JNI_PLATFORM "darwin")
	set_source_files_properties(${SOURCES} PROPERTIES COMPILE_FLAGS "-x objective-c++")
elseif(LINUX)
	set(JNI_PLATFORM "linux")
elseif(WIN32)
	set(JNI_PLATFORM "windows")
endif()

add_library(${PROJECT_NAME} STATIC ${SOURCES})

set_target_properties(${PROJECT_NAME} PROPERTIES
	CXX_STANDARD 14
	CXX_STANDARD_REQUIRED ON
	CXX_EXTENSIONS OFF
)

target_include_directories(${PROJECT_NAME} PUBLIC
	include
	include/jni
	include/jni/${JNI_PLATFORM}
	include/windows
)