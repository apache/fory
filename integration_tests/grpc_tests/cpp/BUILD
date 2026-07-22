load("@rules_cc//cc:defs.bzl", "cc_binary", "cc_library")

cc_library(
    name = "generated_services",
    srcs = glob(["generated/*_service_grpc.cc"], allow_empty = True),
    hdrs = glob(["generated/*.h"], allow_empty = True),
    includes = ["generated"],
    deps = [
        "//cpp/fory/serialization:fory_serialization",
        "@grpc//:grpc++",
    ],
)

cc_binary(
    name = "grpc_interop",
    srcs = ["interop/main.cc"],
    deps = [
        ":generated_services",
        "@grpc//:grpc++",
    ],
)
