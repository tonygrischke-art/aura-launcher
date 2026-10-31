cmake_minimum_required(VERSION 3.22)

project("hermes-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Compute LLAMA_SRC from this file's location to the repo-root llama.cpp
set(LLAMA_SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../llama.cpp)
message(STATUS "LLAMA_SRC resolved to: ${LLAMA_SRC}")

# Android ABI detection
if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# Build llama.cpp as a subdirectory
add_subdirectory(${LLAMA_SRC} build-llama)

# Our JNI library
add_library(hermes-llama SHARED
    local_llm_jni.cpp
)

target_include_directories(hermes-llama PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
)

target_link_libraries(hermes-llama
    llama
    llama-common
    android
    log
)