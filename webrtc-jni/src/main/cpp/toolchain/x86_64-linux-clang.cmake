set(CMAKE_SYSTEM_NAME		Linux)
set(CMAKE_SYSTEM_PROCESSOR	x86_64)
set(CMAKE_C_COMPILER		/home/alex/webrtc/src/third_party/llvm-build/Release+Asserts/bin/clang)
set(CMAKE_CXX_COMPILER		/home/alex/webrtc/src/third_party/llvm-build/Release+Asserts/bin/clang++)

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -nostdinc++ -D_LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_EXTENSIVE")

set(TARGET_CPU				"x64")
