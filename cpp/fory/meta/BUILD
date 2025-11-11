load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_meta",
    srcs = glob(["*.cc"], exclude=["*test.cc"]),
    hdrs = glob(["*.h"]),
    strip_include_prefix = "/cpp",
    visibility = ["//visibility:public"],
)

cc_test(
    name = "preprocessor_test",
    srcs = ["preprocessor_test.cc"],
    deps = [
        ":fory_meta",
        "@com_google_googletest//:gtest",
    ],
)

cc_test(
    name = "field_info_test",
    srcs = ["field_info_test.cc"],
    deps = [
        ":fory_meta",
        "@com_google_googletest//:gtest",
    ],
)

cc_test(
    name = "enum_info_test",
    srcs = ["enum_info_test.cc"],
    deps = [
        ":fory_meta",
        "@com_google_googletest//:gtest",
    ],
)

cc_test(
    name = "type_traits_test",
    srcs = ["type_traits_test.cc"],
    deps = [
        ":fory_meta",
        "@com_google_googletest//:gtest",
    ],
)
