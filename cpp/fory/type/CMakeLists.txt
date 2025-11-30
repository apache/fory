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

# fory_type is a header-only library
add_library(fory_type INTERFACE)
add_library(fory::type ALIAS fory_type)

target_include_directories(fory_type
    INTERFACE
        $<BUILD_INTERFACE:${FORY_CPP_ROOT}>
        $<INSTALL_INTERFACE:include>
)

# Enable AVX2 on x86_64 if configured
if(FORY_USE_AVX2 AND FORY_IS_X86_64)
    if(MSVC)
        target_compile_options(fory_type INTERFACE /arch:AVX2)
    else()
        target_compile_options(fory_type INTERFACE -mavx2)
    endif()
endif()
