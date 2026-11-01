cmake_minimum_required(VERSION 3.31.6)

# Native inference must NEVER be compiled at -O0. AGP passes -DCMAKE_BUILD_TYPE=Debug for the `debug`
# variant, which builds ggml/llama.cpp unoptimised (10-30x slower quantised matmuls). The upstream
# "default to Release" guard in llama.cpp only fires when CMAKE_BUILD_TYPE is empty, so force it here.
set(CMAKE_BUILD_TYPE Release CACHE STRING "Native LLM stack is always optimised" FORCE)

project(mihon_translation_llm LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(BUILD_SHARED_LIBS ON CACHE BOOL "Build GGML backends as Android shared libraries" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "Do not build the command-line helper library" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Do not build upstream tests in the Mihon APK" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "Do not build command-line tools in the Mihon APK" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Do not build upstream examples in the Mihon APK" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Do not build a server in the Mihon APK" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "Do not build upstream app targets in the Mihon APK" FORCE)

    set(GGML_NATIVE OFF CACHE BOOL "Build portable Android CPU kernels" FORCE)
    set(GGML_BACKEND_DL ON CACHE BOOL "Load CPU backend variants from app native libraries" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "Select CPU kernels supported by the device at runtime" FORCE)
    set(GGML_LLAMAFILE OFF CACHE BOOL "Do not include the llamafile backend" FORCE)

    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_CPU_KLEIDIAI ON CACHE BOOL "Enable Arm KleidiAI kernels on arm64" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP on Android in favor of native ggml_threadpool" FORCE)
    else()
        set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "KleidiAI is Arm-only" FORCE)
        set(GGML_OPENMP OFF CACHE BOOL "Keep the x86_64 emulator backend portable" FORCE)
    endif()

    add_subdirectory(llama.cpp)

    add_library(mihon_llm SHARED translation_llm.cpp)
    target_include_directories(mihon_llm PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include"
        "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include"
    )
    target_link_libraries(mihon_llm PRIVATE llama android log)
    target_compile_options(mihon_llm PRIVATE -Wall -Wextra -Wno-unused-parameter -O3)
    # Logged at model load so a stray Debug build is obvious in logcat.
    target_compile_definitions(mihon_llm PRIVATE MIHON_BUILD_TYPE="${CMAKE_BUILD_TYPE}")
else()
    message(STATUS "No embedded LLM backend for ${ANDROID_ABI}; translation will use the ONNX fallback")
endif()
