load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_util",
    srcs = glob(["*.cc"], exclude=["*test.cc"]),
    hdrs = glob(["*.h"]),
    strip_include_prefix = "/cpp",
    alwayslink=True,
    linkstatic=True,
    deps = [
        "//cpp/fory/meta:fory_meta",
        "@com_google_absl//absl/debugging:failure_signal_handler",
        "@com_google_absl//absl/debugging:stacktrace",
        "@com_google_absl//absl/debugging:symbolize",
        "@simdutf//:simdutf"
    ],
    visibility = ["//visibility:public"],
)


cc_test(
    name = "time_util_test",
    srcs = ["time_util_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "logging_test",
    srcs = ["logging_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
    ],
)

cc_test(
    name = "result_test",
    srcs = ["result_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
    ],
)

cc_test(
    name = "error_test",
    srcs = ["error_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
    ],
)

cc_test(
    name = "buffer_test",
    srcs = ["buffer_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "pool_test",
    srcs = ["pool_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "string_util_test",
    srcs = ["string_util_test.cc"],
    deps = [
        ":fory_util",
        "@com_google_googletest//:gtest",
    ],
)
