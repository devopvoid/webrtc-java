cmake_minimum_required(VERSION 3.13)
project(webrtc-java)

if(UNIX AND NOT APPLE)
	set(LINUX TRUE)
endif()

if(APPLE)
    set(CMAKE_CXX_VISIBILITY_PRESET hidden)
    set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
elseif(LINUX)
    set(CMAKE_POSITION_INDEPENDENT_CODE ON)
	set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -s")
elseif(WIN32)
	set(CMAKE_CXX_FLAGS_RELEASE "/MT")
	set(CMAKE_CXX_FLAGS_DEBUG "/MTd")
endif()

add_subdirectory(dependencies/webrtc)
add_subdirectory(dependencies/jni-voithos)

file(GLOB_RECURSE SOURCES "src/*.cpp")

add_library(${PROJECT_NAME} SHARED ${SOURCES})

target_include_directories(${PROJECT_NAME}
	PRIVATE
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

target_link_libraries(${PROJECT_NAME} jni-voithos)
target_link_libraries(${PROJECT_NAME} webrtc)

install(TARGETS ${PROJECT_NAME}
	RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
	LIBRARY DESTINATION ${CMAKE_INSTALL_PREFIX} COMPONENT Runtime
)
