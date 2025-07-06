set(CMAKE_SYSTEM_NAME       Linux)
set(CMAKE_SYSTEM_PROCESSOR  x86_64)
set(CMAKE_C_COMPILER        /opt/clang/bin/clang)
set(CMAKE_CXX_COMPILER      /opt/clang/bin/clang++)
set(CMAKE_AR                /opt/clang/bin/llvm-ar)

set(CMAKE_CXX_FLAGS         "${CMAKE_CXX_FLAGS} -nostdinc++ -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE")
set(CMAKE_EXE_LINKER_FLAGS  "${CMAKE_EXE_LINKER_FLAGS} -stdlib=libc++ -v -Wl,--verbose")

foreach(LINKER SHARED_LINKER)
    set(CMAKE_${LINKER}_FLAGS "-fuse-ld=lld -Wl,-s -v -Wl,--verbose")
endforeach()

# IMPORTANT: Find sysroot but DO NOT set CMAKE_SYSROOT yet
file(GLOB LINUX_SYSROOT "/opt/sysroot/debian*amd64*")
if(NOT LINUX_SYSROOT)
    message(FATAL_ERROR "No debian amd64 sysroot found in /opt/sysroot/")
endif()

# Store sysroot path for later application
# This will be set after project() call in the main CMakeLists.txt
set(DEFERRED_SYSROOT ${LINUX_SYSROOT})

set(TARGET_CPU              "x64")
