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

if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)
endif()

project(fory_cpp_benchmark
    VERSION 0.16.0
    DESCRIPTION "C++ Benchmark comparing Fory, Protobuf, and Msgpack serialization"
    LANGUAGES CXX
)

# C++17 required for Fory
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Set default build type to Release for benchmarks
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# =============================================================================
# Dependencies via FetchContent
# =============================================================================
include(FetchContent)

# Fory C++ library (local source) - this will also fetch Abseil
FetchContent_Declare(
    fory
    SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../cpp"
)
FetchContent_MakeAvailable(fory)

# Protobuf - fetch to ensure Abseil version compatibility
# Using v25.5 which is compatible with Abseil 20240722.0
set(protobuf_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(protobuf_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(protobuf_ABSL_PROVIDER "package" CACHE STRING "" FORCE)
set(protobuf_BUILD_PROTOBUF_BINARIES ON CACHE BOOL "" FORCE)
set(protobuf_BUILD_PROTOC_BINARIES ON CACHE BOOL "" FORCE)
FetchContent_Declare(
    protobuf
    GIT_REPOSITORY https://github.com/protocolbuffers/protobuf.git
    GIT_TAG v25.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(protobuf)

# Google Benchmark
FetchContent_Declare(
    benchmark
    GIT_REPOSITORY https://github.com/google/benchmark.git
    GIT_TAG v1.9.1
    GIT_SHALLOW TRUE
)
set(BENCHMARK_ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(BENCHMARK_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(benchmark)

# msgpack-c (header-only)
FetchContent_Declare(
    msgpack
    GIT_REPOSITORY https://github.com/msgpack/msgpack-c.git
    GIT_TAG cpp-6.1.0
    GIT_SHALLOW TRUE
)
FetchContent_GetProperties(msgpack)
if(NOT msgpack_POPULATED)
    FetchContent_Populate(msgpack)
endif()

add_library(msgpack-cxx INTERFACE)
target_include_directories(msgpack-cxx INTERFACE
    ${msgpack_SOURCE_DIR}/include
)

# =============================================================================
# Proto file compilation
# =============================================================================
set(PROTO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../proto")
set(PROTO_FILE "${PROTO_DIR}/bench.proto")
set(PROTO_OUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")

# Create output directory
file(MAKE_DIRECTORY ${PROTO_OUT_DIR})

# Get protoc executable path
set(PROTOC_EXECUTABLE $<TARGET_FILE:protobuf::protoc>)

# Generate protobuf sources using custom command
set(PROTO_SRCS "${PROTO_OUT_DIR}/bench.pb.cc")
set(PROTO_HDRS "${PROTO_OUT_DIR}/bench.pb.h")

add_custom_command(
    OUTPUT ${PROTO_SRCS} ${PROTO_HDRS}
    COMMAND protobuf::protoc
            --cpp_out=${PROTO_OUT_DIR}
            --proto_path=${PROTO_DIR}
            ${PROTO_FILE}
    DEPENDS ${PROTO_FILE} protobuf::protoc
    COMMENT "Generating protobuf sources from bench.proto"
    VERBATIM
)

# Create a library for the generated proto files
add_library(bench_proto STATIC ${PROTO_SRCS} ${PROTO_HDRS})
target_include_directories(bench_proto PUBLIC ${PROTO_OUT_DIR})
target_link_libraries(bench_proto PUBLIC protobuf::libprotobuf)

# =============================================================================
# Benchmark executable
# =============================================================================
add_executable(fory_benchmark
    ${CMAKE_CURRENT_SOURCE_DIR}/benchmark.cc
)

target_include_directories(fory_benchmark PRIVATE
    ${PROTO_OUT_DIR}
)
target_compile_definitions(fory_benchmark PRIVATE
    MSGPACK_NO_BOOST
)

target_link_libraries(fory_benchmark PRIVATE
    fory::serialization
    bench_proto
    benchmark::benchmark
    benchmark::benchmark_main
    msgpack-cxx
)

# Compiler optimizations for benchmarks
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    target_compile_options(fory_benchmark PRIVATE -O3 -DNDEBUG)
endif()

# =============================================================================
# Print configuration
# =============================================================================
message(STATUS "")
message(STATUS "Fory C++ Benchmark Configuration:")
message(STATUS "  Build type:     ${CMAKE_BUILD_TYPE}")
message(STATUS "  C++ standard:   ${CMAKE_CXX_STANDARD}")
message(STATUS "")
