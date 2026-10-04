cmake_minimum_required(VERSION 3.22)
project(saba_llama C CXX)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)

# Static ggml/llama so this .so does not clash with the whisper module's own ggml.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
    # Emulator only: the host CPU is passed through, so use its vector units.
    foreach(opt GGML_AVX GGML_AVX2 GGML_FMA GGML_F16C)
        set(${opt} ON CACHE BOOL "" FORCE)
    endforeach()
endif()

add_subdirectory(${LLAMA_SRC} build-llama)

add_library(saba_llama SHARED llama_jni.cpp)
target_link_libraries(saba_llama llama android log)
target_link_options(saba_llama PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
