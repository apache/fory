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
    name = "fory_meta",
    srcs = glob(["*.cc"], exclude=["*test.cc"], allow_empty = True),
    hdrs = glob(["*.h"]),
    strip_include_prefix = "/cpp",
    deps = [
        "//cpp/fory/thirdparty:libmmh3",
        "//cpp/fory/type:fory_type",
        "//cpp/fory/util:fory_util",
    ],
    visibility = ["//visibility:public"],
)

cc_test(
    name = "preprocessor_test",
    srcs = ["preprocessor_test.cc"],
    deps = [
        ":fory_meta",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "field_info_test",
    srcs = ["field_info_test.cc"],
    deps = [
        ":fory_meta",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "enum_info_test",
    srcs = ["enum_info_test.cc"],
    deps = [
        ":fory_meta",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "type_traits_test",
    srcs = ["type_traits_test.cc"],
    deps = [
        ":fory_meta",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "meta_string_test",
    srcs = ["meta_string_test.cc"],
    deps = [
        ":fory_meta",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "field_test",
    srcs = ["field_test.cc"],
    deps = [
        ":fory_meta",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)
