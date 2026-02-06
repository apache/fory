load("@rules_cc//cc:defs.bzl", "cc_library", "cc_test")

cc_library(
    name = "fory_serialization",
    srcs = [
        "context.cc",
        "type_resolver.cc",
        "skip.cc",
    ],
    hdrs = [
        "any_serializer.h",
        "array_serializer.h",
        "basic_serializer.h",
        "collection_serializer.h",
        "config.h",
        "context.h",
        "enum_serializer.h",
        "fory.h",
        "map_serializer.h",
        "ref_mode.h",
        "ref_resolver.h",
        "serializer.h",
        "serializer_traits.h",
        "skip.h",
        "smart_ptr_serializers.h",
        "string_serializer.h",
        "struct_serializer.h",
        "temporal_serializers.h",
        "tuple_serializer.h",
        "type_info.h",
        "type_resolver.h",
        "unsigned_serializer.h",
        "union_serializer.h",
        "variant_serializer.h",
        "weak_ptr_serializer.h",
    ],
    strip_include_prefix = "/cpp",
    deps = [
        "//cpp/fory/meta:fory_meta",
        "//cpp/fory/thirdparty:libmmh3",
        "//cpp/fory/type:fory_type",
        "//cpp/fory/util:fory_util",
        "@abseil-cpp//absl/container:flat_hash_map",
    ],
    visibility = ["//visibility:public"],
)

cc_test(
    name = "serialization_test",
    srcs = ["serialization_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "struct_test",
    srcs = ["struct_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "struct_compatible_test",
    srcs = ["struct_compatible_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "smart_ptr_serializer_test",
    srcs = ["smart_ptr_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "map_serializer_test",
    srcs = ["map_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "collection_serializer_test",
    srcs = ["collection_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "variant_serializer_test",
    srcs = ["variant_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "tuple_serializer_test",
    srcs = ["tuple_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "unsigned_serializer_test",
    srcs = ["unsigned_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "any_serializer_test",
    srcs = ["any_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "field_serializer_test",
    srcs = ["field_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
    ],
)

cc_test(
    name = "namespace_macro_test",
    srcs = ["namespace_macro_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "weak_ptr_serializer_test",
    srcs = ["weak_ptr_serializer_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_test(
    name = "stream_test",
    srcs = ["stream_test.cc"],
    deps = [
        ":fory_serialization",
        "@googletest//:gtest",
        "@googletest//:gtest_main",
    ],
)

cc_binary(
    name = "xlang_test_main",
    srcs = ["xlang_test_main.cc"],
    deps = [
        ":fory_serialization",
        "//cpp/fory/thirdparty:libmmh3",
    ],
    visibility = ["//visibility:public"],
)
