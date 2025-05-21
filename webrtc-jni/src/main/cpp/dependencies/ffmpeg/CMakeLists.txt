cmake_minimum_required(VERSION 3.13)
project(ffmpeg)

FILE(WRITE ${CMAKE_CURRENT_BINARY_DIR}/pseudo.hxx
"template<class T> class pseudo{}\n")
FILE(WRITE ${CMAKE_CURRENT_BINARY_DIR}/pseudo.cxx
"static void pseudo(void){}\n")


add_library(${PROJECT_NAME} STATIC pseudo.cxx pseudo.hxx)

set_target_properties(${PROJECT_NAME} PROPERTIES
	CXX_STANDARD 17
	CXX_STANDARD_REQUIRED ON
	CXX_EXTENSIONS OFF
)
target_compile_definitions(${PROJECT_NAME} PUBLIC FFMPEG_STATIC_DEFINE)

find_package(FFMPEG REQUIRED)

target_include_directories(${PROJECT_NAME} PUBLIC ${FFMPEG_INCLUDE_DIRS})
target_link_directories(${PROJECT_NAME} PUBLIC ${FFMPEG_LIBRARY_DIRS})
target_link_libraries(${PROJECT_NAME} PRIVATE ${FFMPEG_LIBRARIES})
