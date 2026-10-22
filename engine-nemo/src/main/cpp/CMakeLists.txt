cmake_minimum_required(VERSION 4.2.3)
project(nemo_speech_jni LANGUAGES CXX)

if(NOT ANDROID OR NOT ANDROID_ABI MATCHES "^(arm64-v8a|x86_64)$")
    message(FATAL_ERROR "NeMo JNI requires the prepared Android CPU ABI")
endif()
if(NOT ANDROID_STL STREQUAL "c++_static")
    message(FATAL_ERROR "NeMo JNI requires static application libc++")
endif()
foreach(required NEMO_SDK_LIB_DIR NEMO_SDK_INCLUDE_DIR)
    if(NOT IS_DIRECTORY "${${required}}")
        message(FATAL_ERROR "Explicit ${required} is required; use prepare-nemo-native.py")
    endif()
endforeach()

add_library(nemo_speech_asr_c SHARED IMPORTED)
set_target_properties(nemo_speech_asr_c PROPERTIES
    IMPORTED_LOCATION "${NEMO_SDK_LIB_DIR}/libnemo_speech_asr_c.so"
    INTERFACE_INCLUDE_DIRECTORIES "${NEMO_SDK_INCLUDE_DIR}")
add_library(nemo_speech_jni SHARED nemo_jni.cpp)
target_compile_features(nemo_speech_jni PRIVATE cxx_std_17)
target_compile_options(nemo_speech_jni PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(nemo_speech_jni PRIVATE nemo_speech_asr_c)
target_link_options(nemo_speech_jni PRIVATE
    "LINKER:-z,max-page-size=16384"
    "LINKER:--exclude-libs,ALL"
    "LINKER:--no-undefined"
    "LINKER:-rpath-link,${NEMO_SDK_LIB_DIR}")
