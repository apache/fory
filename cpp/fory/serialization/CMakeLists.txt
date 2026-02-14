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

set(FORY_SERIALIZATION_SOURCES
    context.cc
    skip.cc
    type_resolver.cc
)

set(FORY_SERIALIZATION_HEADERS
    any_serializer.h
    array_serializer.h
    basic_serializer.h
    collection_serializer.h
    config.h
    context.h
    enum_serializer.h
    fory.h
    map_serializer.h
    ref_mode.h
    ref_resolver.h
    serializer.h
    serializer_traits.h
    skip.h
    smart_ptr_serializers.h
    string_serializer.h
    struct_serializer.h
    temporal_serializers.h
    tuple_serializer.h
    type_info.h
    type_resolver.h
    unsigned_serializer.h
    union_serializer.h
    variant_serializer.h
    weak_ptr_serializer.h
)

add_library(fory_serialization ${FORY_SERIALIZATION_SOURCES})
add_library(fory::serialization_impl ALIAS fory_serialization)

target_include_directories(fory_serialization
    PUBLIC
        $<BUILD_INTERFACE:${FORY_CPP_ROOT}>
        $<INSTALL_INTERFACE:include>
)

target_link_libraries(fory_serialization
    PUBLIC
        fory_meta
        fory_type
        fory_util
        fory_thirdparty
        absl::flat_hash_map
)

set_target_properties(fory_serialization PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    OUTPUT_NAME fory_serialization
)

# Tests
if(FORY_BUILD_TESTS)
    add_executable(fory_serialization_test serialization_test.cc)
    target_link_libraries(fory_serialization_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_test)

    add_executable(fory_serialization_struct_test struct_test.cc)
    target_link_libraries(fory_serialization_struct_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_struct_test)

    add_executable(fory_serialization_struct_compatible_test struct_compatible_test.cc)
    target_link_libraries(fory_serialization_struct_compatible_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_struct_compatible_test)

    add_executable(fory_serialization_smart_ptr_test smart_ptr_serializer_test.cc)
    target_link_libraries(fory_serialization_smart_ptr_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_smart_ptr_test)

    add_executable(fory_serialization_map_test map_serializer_test.cc)
    target_link_libraries(fory_serialization_map_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_map_test)

    add_executable(fory_serialization_variant_test variant_serializer_test.cc)
    target_link_libraries(fory_serialization_variant_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_variant_test)

    add_executable(fory_serialization_field_test field_serializer_test.cc)
    target_link_libraries(fory_serialization_field_test fory_serialization GTest::gtest)
    gtest_discover_tests(fory_serialization_field_test)

    add_executable(fory_serialization_namespace_macro_test namespace_macro_test.cc)
    target_link_libraries(fory_serialization_namespace_macro_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_namespace_macro_test)

    add_executable(fory_serialization_weak_ptr_test weak_ptr_serializer_test.cc)
    target_link_libraries(fory_serialization_weak_ptr_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_weak_ptr_test)

    add_executable(fory_serialization_any_test any_serializer_test.cc)
    target_link_libraries(fory_serialization_any_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_any_test)

    add_executable(fory_serialization_stream_test stream_test.cc)
    target_link_libraries(fory_serialization_stream_test fory_serialization GTest::gtest GTest::gtest_main)
    gtest_discover_tests(fory_serialization_stream_test)
endif()

# xlang test binary
add_executable(fory_xlang_test_main xlang_test_main.cc)
target_link_libraries(fory_xlang_test_main fory_serialization fory_thirdparty)
set_target_properties(fory_xlang_test_main PROPERTIES
    EXCLUDE_FROM_ALL TRUE
)
