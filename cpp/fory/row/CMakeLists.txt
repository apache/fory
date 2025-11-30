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

set(FORY_ROW_SOURCES
    row.cc
    schema.cc
    type.cc
    writer.cc
)

set(FORY_ROW_HEADERS
    row.h
    schema.h
    type.h
    writer.h
)

add_library(fory_row_format ${FORY_ROW_SOURCES})
add_library(fory::row_format_impl ALIAS fory_row_format)

target_include_directories(fory_row_format
    PUBLIC
        $<BUILD_INTERFACE:${FORY_CPP_ROOT}>
        $<INSTALL_INTERFACE:include>
)

target_link_libraries(fory_row_format
    PUBLIC
        fory_serialization
        fory_type
        fory_util
)

set_target_properties(fory_row_format PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    OUTPUT_NAME fory_row_format
)

# Tests
if(FORY_BUILD_TESTS)
    add_executable(fory_row_test row_test.cc)
    target_link_libraries(fory_row_test fory_row_format GTest::gtest)
    gtest_discover_tests(fory_row_test)
endif()
