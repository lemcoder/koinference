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

# GGML_NATIVE is off because these are cross-compiles, which leaves ggml at the toolchain's
# baseline — plain armv8-a for the NDK. That compiles the dot-product kernels out entirely, and
# they are what Q4_0 decoding runs on. The arm64 preset therefore sets GGML_CPU_ARM_ARCH to
# armv8.2-a+dotprod; see docs/performance.md for the measurements and for why +i8mm is *not*
# there.
#
# This is a floor, not a detection: a device older than dotprod (pre-2018, roughly Cortex-A53)
# will take SIGILL rather than fall back. Shipping below that floor needs several variants and a
# runtime pick, the way llama.rn does it — that work is not done here.
# ── GPU offload (Vulkan) ─────────────────────────────────────────────────────
# Off by default, and opt-in rather than automatic, because turning it on moves the floor: the
# backend calls Vulkan 1.1 entry points (vkGetPhysicalDeviceFeatures2), and the NDK only exports
# those from API 28, while this module's minSdk is 24.
#
# Vulkan is the measured path: on a Pixel 8a (Mali-G715) it runs LFM2.5-1.2B Q4_0 at ~17.9 tok/s
# against ~15.2 on CPU. KOI_OPENCL below builds the other one; it needs a manifest declaration to
# load and has not been measured. See docs/performance.md.
#
# Selecting it at run time is the existing RuntimeSettings(accelerator = Accelerator.GPU), which
# the facade already maps onto llama_model_params.n_gpu_layers. A build without this option
# ignores that request and runs on the CPU rather than failing, which is llama.cpp's own
# behaviour for a backend that was never compiled in.
option(KOI_VULKAN "Build the llama.cpp Vulkan backend for GPU offload" OFF)

if (KOI_VULKAN)
    # ggml-vulkan needs the C++ bindings (vulkan.hpp) and the SPIR-V headers. The NDK sysroot
    # ships neither — only the C headers and the loader stub — so they come through CPM, pinned
    # like everything else here.
    CPMAddPackage(
        NAME        Vulkan-Headers
        GITHUB_REPOSITORY KhronosGroup/Vulkan-Headers
        GIT_TAG     v1.4.360
        EXCLUDE_FROM_ALL TRUE
    )
    CPMAddPackage(
        NAME        SPIRV-Headers
        GITHUB_REPOSITORY KhronosGroup/SPIRV-Headers
        GIT_TAG     v2026.08.0
        EXCLUDE_FROM_ALL TRUE
    )

    # ggml-vulkan does find_package(SPIRV-Headers), which the Android toolchain confines to the
    # sysroot unless told otherwise.
    set(SPIRV-Headers_DIR "${SPIRV-Headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
    include_directories(${SPIRV-Headers_SOURCE_DIR}/include)

    set(Vulkan_INCLUDE_DIR "${Vulkan-Headers_SOURCE_DIR}/include" CACHE PATH "" FORCE)

    if (ANDROID)
        # The shader compiler ships with the NDK but is not on PATH, and the loader stub has to
        # come from an API level that actually exports the 1.1 entry points.
        if (NOT DEFINED KOI_VULKAN_API)
            set(KOI_VULKAN_API 28)
        endif()
        file(GLOB _koi_ndk_glslc "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc")
        list(GET _koi_ndk_glslc 0 _koi_glslc)
        set(Vulkan_GLSLC_EXECUTABLE "${_koi_glslc}" CACHE FILEPATH "" FORCE)
        # Globbed rather than composed from a triple variable: which one the toolchain file sets
        # has moved between NDK and CMake versions, and there is exactly one match per ABI.
        file(GLOB _koi_vulkan_lib
             "${CMAKE_SYSROOT}/usr/lib/*/${KOI_VULKAN_API}/libvulkan.so")
        list(LENGTH _koi_vulkan_lib _koi_vulkan_lib_count)
        if (_koi_vulkan_lib_count EQUAL 0)
            message(FATAL_ERROR
                "KOI_VULKAN is on but no libvulkan.so for API ${KOI_VULKAN_API} in ${CMAKE_SYSROOT}")
        endif()
        list(GET _koi_vulkan_lib 0 _koi_vulkan_lib_first)
        set(Vulkan_LIBRARY "${_koi_vulkan_lib_first}" CACHE FILEPATH "" FORCE)
    endif()

    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
endif()

# ── GPU offload (OpenCL) ─────────────────────────────────────────────────────
# Also off by default, and less proven than KOI_VULKAN — see docs/performance.md.
#
# Reaching OpenCL from an app needs two things beyond the backend compiling. The manifest has to
# declare <uses-native-library android:name="libOpenCL.so">, which is mandatory from API 31 for a
# vendor library even when it is already on the vendor public-libraries allowlist; that
# declaration lives in src/androidMain/AndroidManifest.xml and merges into consumers. And the link
# needs a libOpenCL.so to resolve against, which the NDK does not ship.
#
# The ICD loader supplies that: it is built from source purely as a link target, and its SONAME is
# libOpenCL.so, so at run time the loader resolves the *vendor* driver of that name rather than
# this copy. It is deliberately not packaged into jniLibs — packaging it would shadow the driver
# with a loader that has no ICD to enumerate on devices that register none.
# ARM's own microkernels for quantized matmul. Off upstream, and worth a look on any ARMv8.2+
# target: this is exactly the shape of work a bandwidth-bound Q4_0 decode does.
option(KOI_KLEIDIAI "Build ggml's CPU backend with ARM KleidiAI kernels" OFF)

if (KOI_KLEIDIAI)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
endif()

option(KOI_OPENCL "Build the llama.cpp OpenCL backend for GPU offload" OFF)

if (KOI_OPENCL)
    CPMAddPackage(
        NAME        OpenCL-Headers
        GITHUB_REPOSITORY KhronosGroup/OpenCL-Headers
        GIT_TAG     v2026.05.29
        EXCLUDE_FROM_ALL TRUE
    )
    CPMAddPackage(
        NAME        OpenCL-ICD-Loader
        GITHUB_REPOSITORY KhronosGroup/OpenCL-ICD-Loader
        GIT_TAG     v2026.05.29
        EXCLUDE_FROM_ALL TRUE
        OPTIONS     "OPENCL_ICD_LOADER_HEADERS_DIR ${OpenCL-Headers_SOURCE_DIR}"
                    "BUILD_TESTING OFF"
    )

    # Out of the JNI output directory, which the Konan plugin points at jniLibs: built there it
    # would be packaged into the AAR and shadow the vendor driver at run time. It is a link
    # target, nothing more.
    set_target_properties(OpenCL PROPERTIES
        LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/opencl-link"
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/opencl-link")

    set(OpenCL_INCLUDE_DIR "${OpenCL-Headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    # The target name rather than its path: ggml links whatever this holds, and a bare target
    # name gives CMake the dependency edge that a path string does not.
    set(OpenCL_LIBRARY     OpenCL                         CACHE STRING "" FORCE)

    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    # Mali here, not Adreno. The Adreno kernels are the upstream default and are wrong for
    # anything else.
    set(GGML_OPENCL_USE_ADRENO_KERNELS OFF CACHE BOOL "" FORCE)
endif()

# Defaults to LLAMA_STANDALONE, i.e. OFF under CPM — but the facade uses common_* helpers.
set(LLAMA_BUILD_COMMON    ON  CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_TESTS     OFF CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_EXAMPLES  OFF CACHE BOOL   "" FORCE)
set(LLAMA_BUILD_SERVER    OFF CACHE BOOL   "" FORCE)
set(BUILD_SHARED_LIBS     OFF CACHE BOOL   "" FORCE)

# ── llama.cpp ────────────────────────────────────────────────────────────────
# Update GIT_TAG to the desired release (e.g. b10472, b10500, …). Bumping is not free: the
# facade uses common_* helpers whose signatures move, and deprecated llama_* calls are removed
# rather than kept — b5001's llama_kv_cache_clear no longer exists in b10472.
#
# b10472 is the floor for LFM2: LLM_ARCH_LFM2 does not exist before it, so an LFM2 GGUF loads as
# "unknown model architecture" on anything older.
CPMAddPackage(
    NAME        llama_cpp
    GITHUB_REPOSITORY ggerganov/llama.cpp
    GIT_TAG     b10516
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
            ${llama_cpp_SOURCE_DIR}/ggml/include
            # Since b10472 the common headers include <nlohmann/json_fwd.hpp>, which lives in
            # the vendored tree rather than beside them.
            ${llama_cpp_SOURCE_DIR}/vendor)

# The common utilities target was renamed `common` → `llama-common` between b5001 and b10472.
# Detected rather than hardcoded, because getting it wrong does not fail at configure time: CMake
# treats an unknown name as a raw `-lcommon`, the static archive records it happily, and the
# error only surfaces when something finally links an executable.
if (TARGET llama-common)
    set(KOI_COMMON_TARGET llama-common)
elseif (TARGET common)
    set(KOI_COMMON_TARGET common)
else()
    message(FATAL_ERROR "llama.cpp built no common utilities target; the facade needs common_*")
endif()

target_link_libraries(koinference-facade
    PUBLIC llama ${KOI_COMMON_TARGET})

# ── JNI stub ─────────────────────────────────────────────────────────────────
# The Konan plugin generates the bridge sources from facade/koinference_facade.h
# (`:backends:llamacpp:generateJvmInterop`); this compiles and links them. Doing the link here rather
# than in the plugin keeps one toolchain end to end — the C++ runtime and the frameworks ggml's
# Metal/BLAS backends need arrive transitively from koinference-facade.
option(KOI_BUILD_JNI "Build the generated JNI stub library" OFF)

# Set by the Konan plugin's externalNativeBuild block — its contract with an external build, the way
# an AGP project reads ANDROID_ABI. Driving this target by hand means supplying them by hand.
set(KONAN_JNI_STUB_DIR "" CACHE PATH "Directory holding the generated JNI .c sources")
set(KONAN_JNI_LIB_NAME "" CACHE STRING "Output name of the JNI stub library")

if(KOI_BUILD_JNI)
    if(NOT KONAN_JNI_STUB_DIR OR NOT KONAN_JNI_LIB_NAME)
        message(FATAL_ERROR
            "KONAN_JNI_STUB_DIR and KONAN_JNI_LIB_NAME are set by the Konan plugin. "
            "Build through :backends:llamacpp:cmakeBuildKoinference, or pass both by hand.")
    endif()

    file(GLOB KOI_JNI_SOURCES "${KONAN_JNI_STUB_DIR}/*.c")
    if(NOT KOI_JNI_SOURCES)
        message(FATAL_ERROR "No generated JNI sources under ${KONAN_JNI_STUB_DIR}.")
    endif()

    add_library(koinference-jni SHARED ${KOI_JNI_SOURCES})
    set_target_properties(koinference-jni PROPERTIES OUTPUT_NAME "${KONAN_JNI_LIB_NAME}")
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
