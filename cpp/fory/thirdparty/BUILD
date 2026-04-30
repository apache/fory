load("@rules_cc//cc:defs.bzl", "cc_library")

cc_library(
    name = "libmmh3",
    srcs = ["MurmurHash3.cc"],
    hdrs = ["MurmurHash3.h"],
    strip_include_prefix = "/cpp",
    alwayslink=True,
    linkstatic=True,
    visibility = ["//visibility:public"],
)

cc_library(
    name = "flat_hash_map",
    hdrs = ["flat_hash_map.h"],
    strip_include_prefix = "/cpp",
    visibility = ["//visibility:public"],
)

cc_test(
    name = "flat_hash_map_test",
    srcs = ["flat_hash_map_test.cc"],
    deps = [
        ":flat_hash_map",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)
