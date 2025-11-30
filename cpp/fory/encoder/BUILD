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
