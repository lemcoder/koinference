cmake_minimum_required(VERSION 3.21)
project(koinference-litertlm-facade VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── the LiteRT-LM runtime ────────────────────────────────────────────────────
# Unlike llama.cpp, this dependency is a binary rather than source. LiteRT-LM's
# own CMake cannot build here: its data processors include
# "support/preprocessor/*.h // from @litert", which is not published in the
# open-source LiteRT tree, so the source build fails on any platform. Google
# ships the C API prebuilt instead, and its engine.h is byte-identical to the
# header at the tag it was cut from — so the header and the binary agree.
set(KOILM_VERSION "0.16.0" CACHE STRING "LiteRT-LM release to consume")

# The C API ships as one archive covering every platform, which is why this file no longer
# reaches for the per-platform xcframeworks. Version 0.16.0 is the floor: it is the first release
# to publish it, and before it Android had no C API at all — only the Maven AAR, whose
# liblitertlm_jni.so is version-scripted down to 24 Java_* entry points and exports zero
# litert_lm_* symbols. The Android .so in here exports 144 of them.
#
# One artifact for every leg also removes a skew: with an xcframework for Apple and something
# else for Android, the two could drift a release apart while both looked pinned.
set(KOILM_CAPI_VERSION "0.1.0" CACHE STRING "Version of the C API archive within the release")
set(KOILM_ARCHIVE "litert_lm_c_api-${KOILM_CAPI_VERSION}.zip")
set(KOILM_SHA256 "f0f3ae7b5730af783d1f018f7ad9a8de20c25fedf01af4e35fc11d4382246f7d")
set(KOILM_RELEASES "https://github.com/google-ai-edge/LiteRT-LM/releases/download")

# Which slice of the archive this build wants. The names are the archive's own.
if(ANDROID)
    if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        set(KOILM_PLATFORM "android_arm64")
    elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
        set(KOILM_PLATFORM "android_x86_64")
    else()
        message(FATAL_ERROR
            "LiteRT-LM publishes the C API for arm64-v8a and x86_64 only; "
            "asked for ${CMAKE_ANDROID_ARCH_ABI}.")
    endif()
    set(KOILM_LIBRARY "liblitert-lm.so")
elseif(APPLE AND CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(KOILM_PLATFORM "macos_arm64")
    set(KOILM_LIBRARY "liblitert-lm.dylib")
else()
    # iOS is in the release as an xcframework rather than in this archive, and Linux has slices
    # here but no Kotlin target yet. Neither is wired up, and saying so beats a link error.
    message(FATAL_ERROR
        "No LiteRT-LM C API slice is wired up for ${CMAKE_SYSTEM_NAME}. "
        "macOS arm64 and Android arm64-v8a/x86_64 are supported.")
endif()

set(KOILM_DOWNLOAD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.prebuilt/${KOILM_VERSION}")
set(KOILM_ZIP "${KOILM_DOWNLOAD_DIR}/${KOILM_ARCHIVE}")
set(KOILM_ROOT "${KOILM_DOWNLOAD_DIR}/${KOILM_ARCHIVE}.d")
set(KOILM_LIB_DIR "${KOILM_ROOT}/lib/${KOILM_PLATFORM}")
set(KOILM_INCLUDE_DIR "${KOILM_ROOT}/include")

# 154 MB, so it is fetched once per version and shared by every ABI: the archive carries all of
# them, and each configure only picks a different slice out of it.
if(NOT EXISTS "${KOILM_INCLUDE_DIR}/engine.h")
    message(STATUS "[koinference] Fetching ${KOILM_ARCHIVE} (${KOILM_VERSION})...")
    file(DOWNLOAD
        "${KOILM_RELEASES}/v${KOILM_VERSION}/${KOILM_ARCHIVE}"
        "${KOILM_ZIP}"
        EXPECTED_HASH SHA256=${KOILM_SHA256}
        SHOW_PROGRESS)
    file(ARCHIVE_EXTRACT INPUT "${KOILM_ZIP}" DESTINATION "${KOILM_ROOT}")
endif()

if(NOT EXISTS "${KOILM_LIB_DIR}/${KOILM_LIBRARY}")
    message(FATAL_ERROR
        "Expected ${KOILM_LIBRARY} under ${KOILM_LIB_DIR}; the release layout changed.")
endif()

# Staged into the build directory rather than linked from the archive, because on macOS it has
# to be corrected first: the dylib in the release records an install name of
# `@rpath/liblitert-lm.so` — a .so suffix on a Darwin dylib — so anything linked against it looks
# for a file by that name at load time and dies with "Library not loaded". install_name_tool
# fixes the id to match the file, and linking against the corrected copy records the corrected
# name. Doing it at configure time means the imported target can point straight at the result.
file(COPY "${KOILM_LIB_DIR}/${KOILM_LIBRARY}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}")
set(KOILM_STAGED "${CMAKE_CURRENT_BINARY_DIR}/${KOILM_LIBRARY}")

if(APPLE)
    execute_process(
        COMMAND install_name_tool -id "@rpath/${KOILM_LIBRARY}" "${KOILM_STAGED}"
        RESULT_VARIABLE KOILM_ID_RESULT)
    if(NOT KOILM_ID_RESULT EQUAL 0)
        message(FATAL_ERROR "install_name_tool could not set the id of ${KOILM_STAGED}")
    endif()
endif()

add_library(CLiteRTLM SHARED IMPORTED GLOBAL)
set_target_properties(CLiteRTLM PROPERTIES
    IMPORTED_LOCATION "${KOILM_STAGED}"
    INTERFACE_INCLUDE_DIRECTORIES "${KOILM_INCLUDE_DIR}")

# ── facade static library ────────────────────────────────────────────────────
add_library(koinference-litertlm-facade STATIC
    facade/koinference_litertlm_facade.cpp)

target_include_directories(koinference-litertlm-facade
    PUBLIC ${CMAKE_CURRENT_LIST_DIR}/facade)

target_link_libraries(koinference-litertlm-facade
    PUBLIC CLiteRTLM)

# Nothing to stage post-build: the runtime library was copied into this directory at configure
# time, which is the same place the facade archive lands, so one -L covers the pair. A static
# archive records nothing about the shared library it needs, which is why they travel together.

# ── JNI stub ─────────────────────────────────────────────────────────────────
# Android reaches the C API the same way the JVM leg of :backends:llamacpp does: the Konan plugin
# generates the bridge sources from the facade header, and this compiles and links them. Keeping
# the link here rather than in the plugin keeps one toolchain end to end — the stub, the facade
# and the prebuilt runtime are all seen by the same NDK clang.
#
# Until 0.16.0 this was impossible: the only Android artifact was the Maven AAR, whose
# liblitertlm_jni.so is version-scripted down to its own Java_* entry points and exports no
# litert_lm_* symbols at all. The C API archive's Android slice exports 144.
option(KOI_BUILD_JNI "Build the generated JNI stub library" OFF)

# Set by the Konan plugin's externalNativeBuild block — its contract with an external build.
set(KONAN_JNI_STUB_DIR "" CACHE PATH "Directory holding the generated JNI .c sources")
set(KONAN_JNI_LIB_NAME "" CACHE STRING "Output name of the JNI stub library")

if(KOI_BUILD_JNI)
    if(NOT KONAN_JNI_STUB_DIR OR NOT KONAN_JNI_LIB_NAME)
        message(FATAL_ERROR
            "KONAN_JNI_STUB_DIR and KONAN_JNI_LIB_NAME are set by the Konan plugin. "
            "Build through the interop's task, or pass both by hand.")
    endif()

    file(GLOB KOI_JNI_SOURCES "${KONAN_JNI_STUB_DIR}/*.c")
    if(NOT KOI_JNI_SOURCES)
        message(FATAL_ERROR "No generated JNI sources under ${KONAN_JNI_STUB_DIR}.")
    endif()

    # The presets build this with ANDROID_STL=c++_static. liblitert-lm.so statically links its
    # own C++ runtime — its NEEDED list is nine system libraries and no libc++_shared — so there
    # is nothing to share, and a shared STL would mean packaging libc++_shared.so beside the stub
    # or failing at dlopen with "library libc++_shared.so not found".
    add_library(koinference-litertlm-jni SHARED ${KOI_JNI_SOURCES})
    set_target_properties(koinference-litertlm-jni PROPERTIES
        OUTPUT_NAME "${KONAN_JNI_LIB_NAME}")
    target_link_libraries(koinference-litertlm-jni PRIVATE koinference-litertlm-facade)

    # The runtime library has to sit beside the stub in the APK, because the stub records it as a
    # DT_NEEDED and Android's loader resolves that from the same jniLibs directory. Copied into
    # the stub's output directory, which is what the plugin hands to AGP for packaging.
    add_custom_command(TARGET koinference-litertlm-jni POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${KOILM_STAGED}"
            "$<TARGET_FILE_DIR:koinference-litertlm-jni>/${KOILM_LIBRARY}"
        COMMENT "Staging ${KOILM_LIBRARY} beside the JNI stub")

    if(NOT ANDROID)
        # The NDK sysroot ships jni.h; every other toolchain needs a JDK to supply it, and
        # FindJNI insists on a full one when all this needs is the header.
        if(DEFINED ENV{JAVA_HOME} AND EXISTS "$ENV{JAVA_HOME}/include/jni.h")
            target_include_directories(koinference-litertlm-jni PRIVATE
                "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/darwin")
        else()
            find_package(JNI REQUIRED)
            target_include_directories(koinference-litertlm-jni PRIVATE ${JNI_INCLUDE_DIRS})
        endif()
    endif()
endif()

# ── tests ─────────────────────────────────────────────────────────────────────
option(BUILD_TESTS "Build the facade smoke test" OFF)

if(BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
