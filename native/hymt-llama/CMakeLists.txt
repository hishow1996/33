cmake_minimum_required(VERSION 3.18)
project(hymt_android_runtime C CXX)

if (NOT IS_DIRECTORY "${LLAMA_SRC}")
    message(FATAL_ERROR "LLAMA_SRC must point to the pinned llama.cpp checkout")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The Android app packages one self-contained ARM64 JNI library. Keep llama/ggml
# static so no extra shared runtime libraries are required at install time.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# Keep the ARM64 generic CPU/NEON backend self-contained in CI. KleidiAI is an optional
# acceleration layer that FetchContent downloads from a separate GitHub release; network
# failures there must not prevent the HY-MT1.5 runtime from building or running.
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" llama EXCLUDE_FROM_ALL)

add_library(hymt SHARED hymt_jni.cpp)
target_include_directories(hymt PRIVATE
    "${LLAMA_SRC}/include"
    "${LLAMA_SRC}/ggml/include"
)
target_compile_features(hymt PRIVATE cxx_std_17)
target_compile_options(hymt PRIVATE -fvisibility=hidden)
target_link_options(hymt PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--build-id=sha1
    -Wl,-z,max-page-size=16384
)
target_link_libraries(hymt PRIVATE llama log)
