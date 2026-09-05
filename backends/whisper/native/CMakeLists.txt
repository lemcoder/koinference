cmake_minimum_required(VERSION 3.16)
project(koinference-whisper-facade VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ── CPM ─────────────────────────────────────────────────────────────────────
if(NOT DEFINED ENV{CPM_SOURCE_CACHE})
    set(CPM_SOURCE_CACHE "${CMAKE_CURRENT_SOURCE_DIR}/.cpm" CACHE PATH "")
endif()
include(cmake/get_cpm.cmake)

# whisper.cpp vendors ggml, and the same lessons apply as in :backends:llamacpp — the comments
# there are the long version.
#
# GGML_NATIVE off because these are cross-compiles; the arm64 preset names its own baseline.
set(GGML_NATIVE  OFF CACHE BOOL "" FORCE)
# Android ships no libomp.so, and a .so that links it dies on dlopen with every call failing.
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)

# Nothing here needs whisper's own binaries, and building them costs minutes per ABI.
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

# Update GIT_TAG to move the pin. Bumping is not free — see what it cost llama.cpp in CLAUDE.md.
CPMAddPackage(
    NAME        whisper_cpp
    GITHUB_REPOSITORY ggml-org/whisper.cpp
    GIT_TAG     v1.8.2
    EXCLUDE_FROM_ALL TRUE
)

# ── facade static library ────────────────────────────────────────────────────
add_library(koinference-whisper-facade STATIC facade/koinference_whisper.cpp)

target_include_directories(koinference-whisper-facade
    PUBLIC  ${CMAKE_CURRENT_LIST_DIR}/facade
    PRIVATE ${whisper_cpp_SOURCE_DIR}/include
            ${whisper_cpp_SOURCE_DIR}/ggml/include)

target_link_libraries(koinference-whisper-facade PUBLIC whisper)

# ── JNI stub ────────────────────────────────────────────────────────────────
# The Konan plugin generates the bindings and the .c stub; CMake links it, for the reasons the
# llama.cpp module's CMakeLists spells out — a static .a carries no record of what it needs.
option(KOI_BUILD_JNI "Build the generated JNI stub for this facade" OFF)

if (KOI_BUILD_JNI)
    if (NOT DEFINED KONAN_JNI_STUB_DIR OR NOT DEFINED KONAN_JNI_LIB_NAME)
        message(FATAL_ERROR
            "KOI_BUILD_JNI needs KONAN_JNI_STUB_DIR and KONAN_JNI_LIB_NAME. Ask the generate task "
            "for them rather than hardcoding: guessing these has broken this build three times.")
    endif()

    file(GLOB KONAN_JNI_STUB_SOURCES "${KONAN_JNI_STUB_DIR}/*.c")

    add_library(koinference-whisper-jni SHARED ${KONAN_JNI_STUB_SOURCES})
    set_target_properties(koinference-whisper-jni PROPERTIES OUTPUT_NAME "${KONAN_JNI_LIB_NAME}")

    target_link_libraries(koinference-whisper-jni PRIVATE koinference-whisper-facade)

    if (NOT ANDROID)
        find_package(JNI REQUIRED)
        target_include_directories(koinference-whisper-jni PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
endif()
