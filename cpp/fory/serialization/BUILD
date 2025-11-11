load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_serialization",
    srcs = [
        "context.cc",
        "type_resolver.cc",
        "skip.cc",
    ],
    hdrs = [
        "array_serializer.h",
        "basic_serializer.h",
        "collection_serializer.h",
        "config.h",
        "context.h",
        "enum_serializer.h",
        "fory.h",
        "map_serializer.h",
        "ref_resolver.h",
        "serializer.h",
        "serializer_traits.h",
        "skip.h",
        "smart_ptr_serializers.h",
        "struct_serializer.h",
        "temporal_serializers.h",
        "type_resolver.h",
    ],
    strip_include_prefix = "/cpp",
    deps = [
        "//cpp/fory/meta:fory_meta",
        "//cpp/fory/type:fory_type",
        "//cpp/fory/util:fory_util",
    ],
    visibility = ["//visibility:public"],
)

cc_test(
    name = "serialization_test",
    srcs = ["serialization_test.cc"],
    deps = [
        ":fory_serialization",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "struct_test",
    srcs = ["struct_test.cc"],
    deps = [
        ":fory_serialization",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "struct_compatible_test",
    srcs = ["struct_compatible_test.cc"],
    deps = [
        ":fory_serialization",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "smart_ptr_serializer_test",
    srcs = ["smart_ptr_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_test(
    name = "map_serializer_test",
    srcs = ["map_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@com_google_googletest//:gtest",
        "@com_google_googletest//:gtest_main",
    ],
)

cc_binary(
    name = "xlang_test_main",
    srcs = ["xlang_test_main.cc"],
    deps = [
        ":fory_serialization",
    ],
    visibility = ["//visibility:public"],
)
