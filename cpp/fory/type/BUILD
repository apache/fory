load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_type",
    srcs = glob(["*.cc"], exclude=["*test.cc"]),
    hdrs = glob(["*.h"]),
    copts = select({
        "@platforms//cpu:x86_64": ["-mavx2"],
        "//conditions:default": [],
    }),
    linkopts = select({
        "@platforms//cpu:x86_64": ["-mavx2"],
        "//conditions:default": [],
    }),
    strip_include_prefix = "/cpp",
    alwayslink=True,
    linkstatic=True,
    deps = [
    ],
    visibility = ["//visibility:public"],
)
