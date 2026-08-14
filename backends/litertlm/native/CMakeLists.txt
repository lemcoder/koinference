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
set(KOILM_VERSION "0.15.0" CACHE STRING "LiteRT-LM release to consume")

set(KOILM_RELEASES "https://github.com/google-ai-edge/LiteRT-LM/releases/download")

if(APPLE AND CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(KOILM_ARCHIVE  "CLiteRTLM_mac.xcframework.zip")
    set(KOILM_SHA256   "d23cf189ce8f6bb2556c0a023805e245d1ec862434e501eb60f353488033c1b5")
    set(KOILM_SLICE    "CLiteRTLM_mac.xcframework/macos-arm64_x86_64")
    set(KOILM_LIBRARY  "libCLiteRTLM_mac.dylib")
    set(KOILM_HEADERS  "Headers")
elseif(APPLE AND CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(KOILM_ARCHIVE  "CLiteRTLM.xcframework.zip")
    set(KOILM_SHA256   "d6ccf6b54362d894ff71a7580c7e446d36767dab908aecfbb16ffca0fa0bc59b")
    if(CMAKE_OSX_SYSROOT MATCHES "iphonesimulator")
        set(KOILM_SLICE "CLiteRTLM.xcframework/ios-arm64-simulator/CLiteRTLM.framework")
    else()
        set(KOILM_SLICE "CLiteRTLM.xcframework/ios-arm64/CLiteRTLM.framework")
    endif()
    set(KOILM_LIBRARY  "CLiteRTLM")
    set(KOILM_HEADERS  "Headers")
else()
    # Android and Linux take the runtime from the Maven AAR and the per-platform
    # libLiteRt.so respectively; neither is wired up yet.
    message(FATAL_ERROR
        "No LiteRT-LM prebuilt is wired up for ${CMAKE_SYSTEM_NAME}. "
        "Only macOS and iOS are supported so far.")
endif()

set(KOILM_DOWNLOAD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.prebuilt/${KOILM_VERSION}")
set(KOILM_ZIP "${KOILM_DOWNLOAD_DIR}/${KOILM_ARCHIVE}")
set(KOILM_ROOT "${KOILM_DOWNLOAD_DIR}/${KOILM_ARCHIVE}.d")

if(NOT EXISTS "${KOILM_ROOT}/${KOILM_SLICE}/${KOILM_LIBRARY}")
    message(STATUS "[koinference] Fetching ${KOILM_ARCHIVE} (${KOILM_VERSION})...")
    file(DOWNLOAD
        "${KOILM_RELEASES}/v${KOILM_VERSION}/${KOILM_ARCHIVE}"
        "${KOILM_ZIP}"
        EXPECTED_HASH SHA256=${KOILM_SHA256}
        SHOW_PROGRESS)
    file(ARCHIVE_EXTRACT INPUT "${KOILM_ZIP}" DESTINATION "${KOILM_ROOT}")
endif()

set(KOILM_SLICE_DIR "${KOILM_ROOT}/${KOILM_SLICE}")
if(NOT EXISTS "${KOILM_SLICE_DIR}/${KOILM_LIBRARY}")
    message(FATAL_ERROR
        "Expected ${KOILM_LIBRARY} under ${KOILM_SLICE_DIR}; the release layout changed.")
endif()

add_library(CLiteRTLM SHARED IMPORTED GLOBAL)
set_target_properties(CLiteRTLM PROPERTIES
    IMPORTED_LOCATION "${KOILM_SLICE_DIR}/${KOILM_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${KOILM_SLICE_DIR}/${KOILM_HEADERS}")

# ── facade static library ────────────────────────────────────────────────────
add_library(koinference-litertlm-facade STATIC
    facade/koinference_litertlm_facade.cpp)

target_include_directories(koinference-litertlm-facade
    PUBLIC ${CMAKE_CURRENT_LIST_DIR}/facade)

target_link_libraries(koinference-litertlm-facade
    PUBLIC CLiteRTLM)

# A static archive records nothing about the shared library it needs, so the
# Kotlin/Native link would not find CLiteRTLM from the .a alone. Staging both
# side by side lets one -L cover the pair, which is what build.gradle.kts passes.
add_custom_command(TARGET koinference-litertlm-facade POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${KOILM_SLICE_DIR}/${KOILM_LIBRARY}"
        "$<TARGET_FILE_DIR:koinference-litertlm-facade>/${KOILM_LIBRARY}"
    COMMENT "Staging ${KOILM_LIBRARY} next to the facade archive")

# ── tests ─────────────────────────────────────────────────────────────────────
option(BUILD_TESTS "Build the facade smoke test" OFF)

if(BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
