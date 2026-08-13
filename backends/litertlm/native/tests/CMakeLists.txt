# No GoogleTest here, unlike the llama.cpp facade: this suite exists to prove the
# link against the prebuilt runtime resolves, and pulling a test framework in for
# that would add a dependency to prove a dependency works.

add_executable(koinference-litertlm-facade-smoke facade_smoke.cpp)

target_link_libraries(koinference-litertlm-facade-smoke
    PRIVATE koinference-litertlm-facade)

# The runtime is a dylib with an @rpath install name, staged next to the archive.
set_target_properties(koinference-litertlm-facade-smoke PROPERTIES
    BUILD_RPATH "$<TARGET_FILE_DIR:koinference-litertlm-facade>")

add_test(NAME facade_smoke COMMAND koinference-litertlm-facade-smoke)
