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

cmake_minimum_required(VERSION 3.16)

project(fory_idl_tests
    VERSION 0.16.0
    DESCRIPTION "Fory IDL compiler integration tests"
    LANGUAGES CXX
)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

FetchContent_Declare(
    fory
    SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../cpp"
)

FetchContent_MakeAvailable(fory)

add_executable(idl_roundtrip
    ${CMAKE_CURRENT_SOURCE_DIR}/main.cc
)

if(DEFINED ENV{ENABLE_FORY_DEBUG_OUTPUT} AND "$ENV{ENABLE_FORY_DEBUG_OUTPUT}" STREQUAL "1")
    target_compile_definitions(fory_serialization PRIVATE ENABLE_FORY_DEBUG_OUTPUT)
    target_compile_definitions(idl_roundtrip PRIVATE ENABLE_FORY_DEBUG_OUTPUT)
endif()

target_include_directories(idl_roundtrip PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(idl_roundtrip PRIVATE fory::serialization)
