cmake_minimum_required(VERSION 3.16)
project(koinference-facade VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── CPM ─────────────────────────────────────────────────────────────────────
include(cmake/get_cpm.cmake)

# ── llama.cpp ────────────────────────────────────────────────────────────────
# Update GIT_TAG to the desired release (e.g. b5001, b5200, …).
CPMAddPackage(
    NAME        llama_cpp
    GITHUB_REPOSITORY ggerganov/llama.cpp
    GIT_TAG     b5001
    EXCLUDE_FROM_ALL TRUE
    OPTIONS
        "LLAMA_BUILD_TESTS     OFF"
        "LLAMA_BUILD_EXAMPLES  OFF"
        "LLAMA_BUILD_SERVER    OFF"
        "BUILD_SHARED_LIBS     OFF"
        "GGML_NATIVE           OFF"
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
    PUBLIC llama llama-common)

# ── JNI shared library ───────────────────────────────────────────────────────
option(BUILD_JNI "Build JNI bridge (requires JDK)" OFF)

if(BUILD_JNI)
    find_package(JNI REQUIRED)

    add_library(koinference-jni SHARED
        jni/koinference_jni.cpp)

    target_include_directories(koinference-jni PRIVATE
        ${JNI_INCLUDE_DIRS}
        ${CMAKE_CURRENT_LIST_DIR}/facade)

    target_link_libraries(koinference-jni PRIVATE
        koinference-facade
        ${JNI_LIBRARIES})

    set_target_properties(koinference-jni PROPERTIES
        OUTPUT_NAME "koinference-jni")
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
