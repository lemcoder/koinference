cmake_minimum_required(VERSION 3.16)
project(koinference-facade VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── CPM ─────────────────────────────────────────────────────────────────────
# Default CPM cache alongside the cmake project; override with CPM_SOURCE_CACHE env var.
if(NOT DEFINED ENV{CPM_SOURCE_CACHE})
    set(CPM_SOURCE_CACHE "${CMAKE_CURRENT_SOURCE_DIR}/.cpm" CACHE PATH "")
endif()
include(cmake/get_cpm.cmake)

# Force-set before CPM includes llama.cpp so option() in its cmake picks up
# these values from cache rather than re-defaulting to ON.
set(GGML_NATIVE           OFF CACHE BOOL   "" FORCE)
# Defaults to LLAMA_STANDALONE, i.e. OFF under CPM — but the facade uses common_* helpers.
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL   "" FORCE)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL   "" FORCE)

# ── llama.cpp ────────────────────────────────────────────────────────────────
# Update GIT_TAG to the desired release (e.g. b5001, b5200, …).
CPMAddPackage(
    NAME        llama_cpp
    GITHUB_REPOSITORY ggerganov/llama.cpp
    GIT_TAG     b5001
    EXCLUDE_FROM_ALL TRUE
)

# ── facade static library ────────────────────────────────────────────────────
add_library(koinference-facade STATIC
    facade/koinference_facade.cpp)

target_include_directories(koinference-facade
    PUBLIC  ${CMAKE_CURRENT_LIST_DIR}/facade
    PRIVATE ${llama_cpp_SOURCE_DIR}
            ${llama_cpp_SOURCE_DIR}/common
            ${llama_cpp_SOURCE_DIR}/include
            ${llama_cpp_SOURCE_DIR}/ggml/include)

target_link_libraries(koinference-facade
    PUBLIC llama common)

# ── JNI stub ─────────────────────────────────────────────────────────────────
# The Konan plugin generates the bridge sources from facade/koinference_facade.h
# (`:backends:llamacpp:generateJvmInterop`); this compiles and links them. Doing the link here rather
# than in the plugin keeps one toolchain end to end — the C++ runtime and the frameworks ggml's
# Metal/BLAS backends need arrive transitively from koinference-facade.
option(KOI_BUILD_JNI "Build the generated JNI stub library" OFF)

# Where generateJvmInterop writes the stub, and the name its Kotlin System.loadLibrary() expects.
set(KOI_JNI_STUB_DIR "${CMAKE_CURRENT_LIST_DIR}/../build/generated/jvmInterop/c"
    CACHE PATH "Directory holding the generated JNI .c sources")
set(KOI_JNI_LIB_NAME "iogithublemcoderkoinferencellamacppinternaljnistubs"
    CACHE STRING "Output name of the JNI stub library")

if(KOI_BUILD_JNI)
    file(GLOB KOI_JNI_SOURCES "${KOI_JNI_STUB_DIR}/*.c")
    if(NOT KOI_JNI_SOURCES)
        message(FATAL_ERROR
            "No generated JNI sources under ${KOI_JNI_STUB_DIR}. "
            "Run :backends:llamacpp:generateJvmInterop first.")
    endif()

    add_library(koinference-jni SHARED ${KOI_JNI_SOURCES})
    set_target_properties(koinference-jni PROPERTIES OUTPUT_NAME "${KOI_JNI_LIB_NAME}")
    target_link_libraries(koinference-jni PRIVATE koinference-facade)

    # The NDK sysroot ships jni.h; every other target needs a JDK to supply it. FindJNI insists on a
    # full JDK (AWT, the JVM library) when all this needs is the header, so JAVA_HOME wins when set —
    # Gradle passes its own toolchain that way.
    if(NOT ANDROID)
        if(APPLE)
            set(KOI_JNI_OS_DIR "darwin")
        elseif(WIN32)
            set(KOI_JNI_OS_DIR "win32")
        else()
            set(KOI_JNI_OS_DIR "linux")
        endif()

        if(DEFINED ENV{JAVA_HOME} AND EXISTS "$ENV{JAVA_HOME}/include/jni.h")
            target_include_directories(koinference-jni PRIVATE
                "$ENV{JAVA_HOME}/include"
                "$ENV{JAVA_HOME}/include/${KOI_JNI_OS_DIR}")
        else()
            find_package(JNI REQUIRED)
            target_include_directories(koinference-jni PRIVATE ${JNI_INCLUDE_DIRS})
        endif()
    endif()
endif()

# ── tests ─────────────────────────────────────────────────────────────────────
option(BUILD_TESTS "Build Google Test suite" OFF)

if(BUILD_TESTS)
    CPMAddPackage(
        NAME        googletest
        GITHUB_REPOSITORY google/googletest
        GIT_TAG     v1.15.2
        OPTIONS
            "INSTALL_GTEST OFF"
    )

    enable_testing()
    add_subdirectory(tests)
endif()
