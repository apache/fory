load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_meta",
    srcs = glob(["*.cc"], exclude=["*test.cc"], allow_empty = True),
    hdrs = glob(["*.h"]),
    strip_include_prefix = "/cpp",
    deps = [
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
