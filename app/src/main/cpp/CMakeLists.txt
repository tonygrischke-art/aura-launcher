cmake_minimum_required(VERSION 3.22)

project("aura-llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# llama.cpp lives at <repo>/llama.cpp. This file is <repo>/android/src/main/cpp,
# so the repo root is FOUR levels up (the old path went five up, i.e. outside the repo).
# Override with -DLLAMA_SRC=/path if llama.cpp is checked out elsewhere.
set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../llama.cpp" CACHE PATH "llama.cpp source tree")
get_filename_component(LLAMA_SRC "${LLAMA_SRC}" REALPATH)
if(NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_SRC} (pass -DLLAMA_SRC=...)")
endif()
message(STATUS "LLAMA_SRC resolved to: ${LLAMA_SRC}")

# Android only unpacks APK entries named lib*.so, and the dynamic linker resolves
# DT_NEEDED by literal name. llama.cpp sets SOVERSION on every library, which yields
# libllama.so.0 / libmtmd.so.0 etc. Those never get extracted and the load fails.
# Must be set before add_subdirectory(). build.sh re-verifies the result with readelf.
set(CMAKE_PLATFORM_NO_VERSIONED_SONAME ON)

# Android ABI detection
if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP OFF)  # Per llama.cpp docs: OpenMP not supported in Android NDK
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

# Build llama.cpp as a subdirectory with MTMD (multimodal) support
set(LLAMA_BUILD_MTMD ON CACHE BOOL "Build mtmd library" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Skip tools build" FORCE)
add_subdirectory(${LLAMA_SRC} build-llama)

# Our JNI library
add_library(aura-llama SHARED
    local_llm_jni.cpp
)

target_include_directories(aura-llama PRIVATE
    ${LLAMA_SRC}
    ${LLAMA_SRC}/common
    ${LLAMA_SRC}/include
    ${LLAMA_SRC}/ggml/include
    ${LLAMA_SRC}/ggml/src
    ${LLAMA_SRC}/tools/mtmd
)

target_link_libraries(aura-llama
    llama
    llama-common
    mtmd
    ggml
    android
    log
)
