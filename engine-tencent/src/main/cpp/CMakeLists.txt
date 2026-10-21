cmake_minimum_required(VERSION 4.2.3)
project(tencent_native LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED TENCENT_LLAMA_SOURCE)
    message(FATAL_ERROR "Use tools/android/prepare-tencent-native.py with the pinned source and tools")
endif()
# CPU only, statically linked, with no executable/download/server dependencies.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
foreach(flag LLAMA_BUILD_COMMON LLAMA_BUILD_TESTS LLAMA_BUILD_TOOLS LLAMA_BUILD_EXAMPLES
             LLAMA_BUILD_SERVER LLAMA_BUILD_APP LLAMA_OPENSSL LLAMA_SUBPROCESS
             GGML_CUDA GGML_VULKAN GGML_METAL GGML_OPENCL GGML_SYCL GGML_RPC GGML_BLAS
             GGML_OPENMP GGML_CPU_KLEIDIAI GGML_NATIVE)
    set(${flag} OFF CACHE BOOL "" FORCE)
endforeach()
if(ANDROID)
    if(NOT ANDROID_PLATFORM STREQUAL "android-26" OR NOT ANDROID_STL STREQUAL "c++_static")
        message(FATAL_ERROR "Pinned Android 26 and static STL are required")
    endif()
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        foreach(flag GGML_AVX GGML_AVX2 GGML_F16C GGML_FMA)
            set(${flag} OFF CACHE BOOL "" FORCE)
        endforeach()
        set(GGML_SSE42 ON CACHE BOOL "" FORCE)
    else()
        message(FATAL_ERROR "Only arm64-v8a and x86_64 are supported")
    endif()
endif()
# Keep build-machine paths out of native diagnostics and debug metadata.
add_compile_options(
    "-ffile-prefix-map=${TENCENT_LLAMA_SOURCE}=llama.cpp"
    "-ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=tencent-native"
    "-ffile-prefix-map=${CMAKE_BINARY_DIR}=native-build")
add_subdirectory("${TENCENT_LLAMA_SOURCE}" llama.cpp)
add_library(tencent_runtime STATIC tencent_runtime.cpp)
target_link_libraries(tencent_runtime PUBLIC llama)
target_include_directories(tencent_runtime PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")

if(ANDROID)
    add_library(tencent_jni SHARED tencent_jni.cpp)
    target_link_libraries(tencent_jni PRIVATE tencent_runtime)
    target_link_options(tencent_jni PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--exclude-libs,ALL")
    set_target_properties(tencent_jni PROPERTIES LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/jni")
else()
    find_package(Threads REQUIRED)
    add_executable(tencent_native_smoke smoke.cpp)
    target_link_libraries(tencent_native_smoke PRIVATE tencent_runtime Threads::Threads)
endif()
