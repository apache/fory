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

load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_encoder",
    srcs = glob(["*.cc"], exclude=["*test.cc"], allow_empty = True),
    hdrs = glob(["*.h"]),
    strip_include_prefix = "/cpp",
    deps = [
      "//cpp/fory/row:fory_row_format",
      "//cpp/fory/meta:fory_meta"
    ],
    visibility = ["//visibility:public"],
)

cc_test(
    name = "row_encoder_test",
    srcs = glob(["*_test.cc"]),
    deps = [
        ":fory_encoder",
        "@googletest//:gtest",
    ],
)
