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
    field.h
    field_info.h
    meta_string.h
    preprocessor.h
    type_traits.h
)

add_library(fory_meta ${FORY_META_SOURCES})
add_library(fory::meta ALIAS fory_meta)
fory_configure_target(fory_meta)

# FORY_STRUCT forwards __VA_ARGS__ through nested macros, which the legacy
# MSVC preprocessor expands as a single token. /Zc:preprocessor selects the
# standard-conforming preprocessor (MSVC 19.25+ / VS 2019 16.5+) and is
# propagated to anything that links fory_meta so CMake consumers that use
# FORY_STRUCT do not need to opt in by hand. The genex gates on the MSVC
# compiler id (not the legacy MSVC variable) so the flag is omitted for
# clang-cl, which warns on `/Zc:preprocessor` and is already conforming;
# the gate also keeps the install-export hermetic for non-MSVC consumers.
# Mirrors .bazelrc:59-60, which already sets this for Bazel Windows builds.
target_compile_options(fory_meta PUBLIC
    $<$<CXX_COMPILER_ID:MSVC>:/Zc:preprocessor>)

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
    fory_configure_target(fory_meta_preprocessor_test)
    target_link_libraries(fory_meta_preprocessor_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_preprocessor_test)

    add_executable(fory_meta_field_info_test field_info_test.cc)
    fory_configure_target(fory_meta_field_info_test)
    target_link_libraries(fory_meta_field_info_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_field_info_test)

    add_executable(fory_meta_enum_info_test enum_info_test.cc)
    fory_configure_target(fory_meta_enum_info_test)
    target_link_libraries(fory_meta_enum_info_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_enum_info_test)

    add_executable(fory_meta_type_traits_test type_traits_test.cc)
    fory_configure_target(fory_meta_type_traits_test)
    target_link_libraries(fory_meta_type_traits_test fory_meta GTest::gtest)
    gtest_discover_tests(fory_meta_type_traits_test)

    add_executable(fory_meta_string_test meta_string_test.cc)
    fory_configure_target(fory_meta_string_test)
    target_link_libraries(fory_meta_string_test fory_meta GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_meta_string_test)

    add_executable(fory_meta_field_test field_test.cc)
    fory_configure_target(fory_meta_field_test)
    target_link_libraries(fory_meta_field_test fory_meta GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_meta_field_test)
endif()
