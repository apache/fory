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
    name = "fory_util",
    srcs = glob(["*.cc"], exclude=["*test.cc"]),
    hdrs = glob(["*.h"]),
    strip_include_prefix = "/cpp",
    alwayslink=True,
    linkstatic=True,
    visibility = ["//visibility:public"],
)


cc_test(
    name = "time_util_test",
    srcs = ["time_util_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "logging_test",
    srcs = ["logging_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "result_test",
    srcs = ["result_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "error_test",
    srcs = ["error_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "buffer_test",
    srcs = ["buffer_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "pool_test",
    srcs = ["pool_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "string_util_test",
    srcs = ["string_util_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "flat_int_map_test",
    srcs = ["flat_int_map_test.cc"],
    deps = [
        ":fory_util",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "float16_test",
    srcs = ["float16_test.cc"],
    deps = [
        ":fory_util",
        "//cpp/fory/serialization:fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)
