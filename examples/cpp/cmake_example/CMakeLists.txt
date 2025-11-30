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

project(fory_cmake_example
    VERSION 1.0.0
    DESCRIPTION "Example project demonstrating Fory C++ CMake integration"
    LANGUAGES CXX
)

# C++17 required for Fory
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# =============================================================================
# Fory Integration Options (choose one)
# =============================================================================

# Option 1: Use FetchContent (recommended for external projects)
# This is similar to how abseil or gtest are commonly used
include(FetchContent)

# For production use: fetch from GitHub
# FetchContent_Declare(
#     fory
#     GIT_REPOSITORY https://github.com/apache/fory.git
#     GIT_TAG        main
#     SOURCE_SUBDIR  cpp
# )

# For local development: use local source directory
FetchContent_Declare(
    fory
    SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../cpp"
)

FetchContent_MakeAvailable(fory)

# Option 2: Use find_package if Fory is installed system-wide
# find_package(Fory REQUIRED)

# Option 3: Add Fory as a subdirectory with explicit path
# set(FORY_CPP_DIR "/path/to/fory/cpp" CACHE PATH "Path to Fory C++ directory")
# add_subdirectory(${FORY_CPP_DIR} ${CMAKE_BINARY_DIR}/fory)

# Serialization example executable
add_executable(serialization_example
    ${CMAKE_CURRENT_SOURCE_DIR}/serialization_example.cc
)
target_link_libraries(serialization_example PRIVATE fory::serialization)

# Row format example executable
add_executable(row_format_example
    ${CMAKE_CURRENT_SOURCE_DIR}/row_format_example.cc
)
target_link_libraries(row_format_example PRIVATE fory::row_format)

# Print build info
message(STATUS "")
message(STATUS "Fory CMake Example Configuration:")
message(STATUS "  C++ standard:   ${CMAKE_CXX_STANDARD}")
message(STATUS "  Build type:     ${CMAKE_BUILD_TYPE}")
message(STATUS "")
