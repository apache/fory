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

# fory_encoder is a header-only library
add_library(fory_encoder INTERFACE)
add_library(fory::encoder ALIAS fory_encoder)

target_include_directories(fory_encoder
    INTERFACE
        $<BUILD_INTERFACE:${FORY_CPP_ROOT}>
        $<INSTALL_INTERFACE:include>
)

target_link_libraries(fory_encoder
    INTERFACE
        fory_row_format
        fory_meta
)

# Tests
if(FORY_BUILD_TESTS)
    add_executable(fory_encoder_test row_encoder_test.cc row_encode_trait_test.cc)
    target_link_libraries(fory_encoder_test fory_encoder GTest::gtest)
    gtest_discover_tests(fory_encoder_test)
endif()
