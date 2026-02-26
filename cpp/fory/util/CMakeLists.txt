# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

set(FORY_UTIL_SOURCES
    buffer.cc
    error.cc
    logging.cc
    stream.cc
    string_util.cc
    time_util.cc
)

set(FORY_UTIL_HEADERS
    bit_util.h
    buffer.h
    error.h
    lock.h
    logging.h
    platform.h
    pool.h
    result.h
    stream.h
    string_util.h
    time_util.h
    flat_int_map.h
)

add_library(fory_util ${FORY_UTIL_SOURCES})
add_library(fory::util ALIAS fory_util)

target_include_directories(fory_util
    PUBLIC
        $<BUILD_INTERFACE:${FORY_CPP_ROOT}>
        $<INSTALL_INTERFACE:include>
)

# Link Abseil for debugging support
target_link_libraries(fory_util
    PUBLIC
        absl::failure_signal_handler
        absl::stacktrace
        absl::symbolize
)

set_target_properties(fory_util PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    OUTPUT_NAME fory_util
)

# Tests
if(FORY_BUILD_TESTS)
    add_executable(fory_util_buffer_test buffer_test.cc)
    target_link_libraries(fory_util_buffer_test fory_util GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_util_buffer_test)

    add_executable(fory_util_error_test error_test.cc)
    target_link_libraries(fory_util_error_test fory_util GTest::gtest)
    gtest_discover_tests(fory_util_error_test)

    add_executable(fory_util_logging_test logging_test.cc)
    target_link_libraries(fory_util_logging_test fory_util GTest::gtest)
    gtest_discover_tests(fory_util_logging_test)

    add_executable(fory_util_pool_test pool_test.cc)
    target_link_libraries(fory_util_pool_test fory_util GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_util_pool_test)

    add_executable(fory_util_result_test result_test.cc)
    target_link_libraries(fory_util_result_test fory_util GTest::gtest)
    gtest_discover_tests(fory_util_result_test)

    add_executable(fory_util_string_util_test string_util_test.cc)
    target_link_libraries(fory_util_string_util_test fory_util GTest::gtest)
    gtest_discover_tests(fory_util_string_util_test)

    add_executable(fory_util_time_util_test time_util_test.cc)
    target_link_libraries(fory_util_time_util_test fory_util GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_util_time_util_test)

    add_executable(fory_util_int_map_test int_map_test.cc)
    target_link_libraries(fory_util_int_map_test fory_util GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_util_int_map_test)
endif()
