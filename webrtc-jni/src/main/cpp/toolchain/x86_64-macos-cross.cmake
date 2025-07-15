set(CMAKE_SYSTEM_NAME                  Darwin)
set(CMAKE_SYSTEM_PROCESSOR             x86_64)

set(CMAKE_OSX_ARCHITECTURES            x86_64)
set(CMAKE_OSX_DEPLOYMENT_TARGET        "11.0" CACHE STRING "Minimum OS X deployment version")

set(CMAKE_C_FLAGS_INIT                 "-arch x86_64")
set(CMAKE_CXX_FLAGS_INIT               "-arch x86_64")

set(CMAKE_EXE_LINKER_FLAGS_INIT        "-arch x86_64")
set(CMAKE_SHARED_LINKER_FLAGS_INIT     "-arch x86_64")

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM  NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY  ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE  ONLY)

set(TARGET_CPU                         "x64")