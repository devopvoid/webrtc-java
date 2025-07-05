set(CMAKE_SYSTEM_NAME		Linux)
set(CMAKE_SYSTEM_PROCESSOR	arm)
set(CMAKE_C_COMPILER		/opt/clang/bin/clang)
set(CMAKE_CXX_COMPILER		/opt/clang/bin/clang++)
set(CMAKE_AR		        /opt/clang/bin/llvm-ar)

set(TARGET_TRIPLE           arm-linux-gnueabihf)

set(CMAKE_C_FLAGS           "--target=${TARGET_TRIPLE} -march=armv7-a -mfloat-abi=hard -mfpu=neon")
set(CMAKE_CXX_FLAGS         "${CMAKE_C_FLAGS}")
set(CMAKE_EXE_LINKER_FLAGS  "${CMAKE_EXE_LINKER_FLAGS} -v -Wl,--verbose")

foreach(LINKER SHARED_LINKER)
    set(CMAKE_${LINKER}_FLAGS "-fuse-ld=lld -Wl,-s -v -Wl,--verbose")
endforeach()

file(GLOB LINUX_SYSROOT "/opt/sysroot/debian*armhf*")
if(NOT LINUX_SYSROOT)
    message(FATAL_ERROR "No debian armhf sysroot found in /opt/sysroot/")
endif()

set(CMAKE_SYSROOT ${LINUX_SYSROOT})

# Make sure CMake finds libraries and headers in the sysroot
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)

set(TARGET_CPU				"arm")