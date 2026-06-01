add_executable(koinference-tests
    facade_test.cpp)

target_link_libraries(koinference-tests PRIVATE
    koinference-facade
    GTest::gtest_main)

target_include_directories(koinference-tests PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}/../facade)

include(GoogleTest)
gtest_discover_tests(koinference-tests)
