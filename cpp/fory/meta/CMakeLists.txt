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

set(FORY_META_SOURCES
    meta_string.cc
)

set(FORY_META_HEADERS
    enum_info.h
    field_info.h
    meta_string.h
    preprocessor.h
    type_traits.h
)

add_library(fory_meta ${FORY_META_SOURCES})
add_library(fory::meta ALIAS fory_meta)

target_include_directories(fory_meta
    PUBLIC
        $<BUILD_INTERFACE:${FORY_CPP_ROOT}>
        $<INSTALL_INTERFACE:include>
)

target_link_libraries(fory_meta
    PUBLIC
        fory_util
)

set_target_properties(fory_meta PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    OUTPUT_NAME fory_meta
)

# Tests
if(FORY_BUILD_TESTS)
    add_executable(fory_meta_preprocessor_test preprocessor_test.cc)
    target_link_libraries(fory_meta_preprocessor_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_preprocessor_test)

    add_executable(fory_meta_field_info_test field_info_test.cc)
    target_link_libraries(fory_meta_field_info_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_field_info_test)

    add_executable(fory_meta_enum_info_test enum_info_test.cc)
    target_link_libraries(fory_meta_enum_info_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_enum_info_test)

    add_executable(fory_meta_type_traits_test type_traits_test.cc)
    target_link_libraries(fory_meta_type_traits_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_type_traits_test)

    add_executable(fory_meta_string_test meta_string_test.cc)
    target_link_libraries(fory_meta_string_test fory_meta GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_meta_string_test)
endif()
