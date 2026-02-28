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

project(fory
    VERSION 0.16.0
    DESCRIPTION "Apache Fory - A blazingly fast multi-language serialization framework"
    LANGUAGES CXX
)

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Options
option(FORY_BUILD_TESTS "Build Fory tests" OFF)
option(FORY_BUILD_SHARED "Build shared libraries" ON)
option(FORY_BUILD_STATIC "Build static libraries" ON)
option(FORY_USE_AVX2 "Enable AVX2 optimizations on x86_64" ON)

# Set default build type if not specified
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()

# Output directories
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/lib)
set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)

# Platform detection
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    set(FORY_IS_X86_64 TRUE)
else()
    set(FORY_IS_X86_64 FALSE)
endif()

# Compiler flags
if(MSVC)
    add_compile_options(/W4)
    if(FORY_USE_AVX2 AND FORY_IS_X86_64)
        add_compile_options(/arch:AVX2)
    endif()
else()
    add_compile_options(-Wall -Wextra -Wpedantic)
    if(FORY_USE_AVX2 AND FORY_IS_X86_64)
        add_compile_options(-mavx2)
    endif()
endif()

# Set Fory root directory for include paths (important when used as subdirectory)
set(FORY_CPP_ROOT ${CMAKE_CURRENT_SOURCE_DIR} CACHE PATH "Fory C++ root directory")

# Fetch Abseil (required for debugging support)
message(STATUS "Fetching Abseil via FetchContent")
include(FetchContent)
set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_BUILD_TESTING OFF)
set(ABSL_ENABLE_INSTALL ON)
FetchContent_Declare(
    abseil-cpp
    GIT_REPOSITORY https://github.com/abseil/abseil-cpp.git
    GIT_TAG 20240722.0
)
FetchContent_MakeAvailable(abseil-cpp)

# Add subdirectories
add_subdirectory(fory/thirdparty)
add_subdirectory(fory/util)
add_subdirectory(fory/type)
add_subdirectory(fory/meta)
add_subdirectory(fory/serialization)
add_subdirectory(fory/row)
add_subdirectory(fory/encoder)

# Create main fory_serialization interface library (for users who only need serialization)
add_library(fory_serialization_lib INTERFACE)
target_link_libraries(fory_serialization_lib INTERFACE fory_serialization)
add_library(fory::serialization ALIAS fory_serialization_lib)

# Create main fory_row_format interface library (for users who need row format)
add_library(fory_row_format_lib INTERFACE)
target_link_libraries(fory_row_format_lib INTERFACE fory_row_format fory_encoder)
add_library(fory::row_format ALIAS fory_row_format_lib)

# Create main fory interface library (includes everything)
add_library(fory_lib INTERFACE)
target_link_libraries(fory_lib INTERFACE fory_serialization fory_row_format fory_encoder)
add_library(fory::fory ALIAS fory_lib)

# Tests
if(FORY_BUILD_TESTS)
    enable_testing()
    find_package(GTest REQUIRED)
    include(GoogleTest)
    add_subdirectory(tests)
endif()

# Installation
include(GNUInstallDirs)
include(CMakePackageConfigHelpers)

# Install headers
install(DIRECTORY fory/
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/fory
    FILES_MATCHING
    PATTERN "*.h"
    PATTERN "*_test.cc" EXCLUDE
    PATTERN "python" EXCLUDE
)

# Install libraries
install(TARGETS
    fory_thirdparty
    fory_util
    fory_type
    fory_meta
    fory_serialization
    fory_row_format
    fory_encoder
    fory_serialization_lib
    fory_row_format_lib
    fory_lib
    EXPORT ForyTargets
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
    INCLUDES DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}
)

# Generate and install package config files
write_basic_package_version_file(
    "${CMAKE_CURRENT_BINARY_DIR}/ForyConfigVersion.cmake"
    VERSION ${PROJECT_VERSION}
    COMPATIBILITY SameMajorVersion
)

configure_package_config_file(
    "${CMAKE_CURRENT_SOURCE_DIR}/cmake/ForyConfig.cmake.in"
    "${CMAKE_CURRENT_BINARY_DIR}/ForyConfig.cmake"
    INSTALL_DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/fory
)

install(EXPORT ForyTargets
    FILE ForyTargets.cmake
    NAMESPACE fory::
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/fory
)

install(FILES
    "${CMAKE_CURRENT_BINARY_DIR}/ForyConfig.cmake"
    "${CMAKE_CURRENT_BINARY_DIR}/ForyConfigVersion.cmake"
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/cmake/fory
)

# Print configuration summary
message(STATUS "")
message(STATUS "Fory Configuration Summary:")
message(STATUS "  Version:        ${PROJECT_VERSION}")
message(STATUS "  Build type:     ${CMAKE_BUILD_TYPE}")
message(STATUS "  C++ standard:   ${CMAKE_CXX_STANDARD}")
message(STATUS "  Install prefix: ${CMAKE_INSTALL_PREFIX}")
message(STATUS "  Build tests:    ${FORY_BUILD_TESTS}")
message(STATUS "  AVX2 enabled:   ${FORY_USE_AVX2} (x86_64: ${FORY_IS_X86_64})")
message(STATUS "")
