---
title: gRPC Support
sidebar_position: 8
id: grpc_support
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

Fory can generate C++ gRPC service companions for schemas that define services.
The generated code uses gRPC C++ for transport and Fory for request and
response payload serialization.

Use this mode when every RPC peer is generated from the same Fory IDL, protobuf
IDL, or FlatBuffers IDL and you want gRPC transport semantics with Fory payload
encoding. Use standard protobuf gRPC code generation when clients or tools must
consume protobuf message bytes directly.

## Add Dependencies

With Bazel, use the Fory label that is visible from your workspace.
The example below is for a project that consumes Fory as an external module named `fory`:

```bazel
load("@rules_cc//cc:defs.bzl", "cc_library")

cc_library(
    name = "greeter_generated",
    srcs = ["generated/demo_greeter_service_grpc.cc"],
    hdrs = glob(["generated/*.h"]),
    includes = ["generated"],
    deps = [
        "@fory//cpp/fory/serialization:fory_serialization",
        "@grpc//:grpc++",
    ],
)
```

Inside the Fory repository, use `//cpp/fory/serialization:fory_serialization`
instead of `@fory//cpp/fory/serialization:fory_serialization`.

With CMake, make the Fory C++ target visible first. For an installed Fory
package, use `find_package(Fory CONFIG REQUIRED)`, then add the generated source
and link both libraries explicitly:

```cmake
find_package(Fory CONFIG REQUIRED)
find_package(gRPC CONFIG REQUIRED)

add_library(greeter_generated
    generated/demo_greeter_service_grpc.cc
)
target_compile_features(greeter_generated PUBLIC cxx_std_17)
target_include_directories(greeter_generated PUBLIC generated)
target_link_libraries(greeter_generated PUBLIC
    fory::serialization
    gRPC::grpc++
)
```

If your project brings Fory in with `FetchContent` or `add_subdirectory`, call
that before linking `fory::serialization`.

Use dependency versions that are compatible with the rest of your service
stack.

## Define a Service

Service definitions can come from Fory IDL, protobuf IDL, or FlatBuffers
`rpc_service` definitions. A Fory IDL service looks like this:

```protobuf
package demo.greeter;

message HelloRequest {
  string name = 1;
}

message HelloReply {
  string reply = 1;
}

service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
}
```

Generate C++ model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --cpp_out=./generated/cpp --grpc
```

For this schema, the C++ generator emits:

| File                           | Purpose                                            |
| ------------------------------ | -------------------------------------------------- |
| `demo_greeter.h`               | Fory model types and registration helpers          |
| `demo_greeter_service.h`       | Synchronous service interface and path constants   |
| `demo_greeter_service_grpc.h`  | Synchronous client, server adapter, and Fory codec |
| `demo_greeter_service_grpc.cc` | Stub calls and server route implementations        |

Include the generated gRPC header from application code and compile
`demo_greeter_service_grpc.cc` once in your build target. The codec is generated
directly in the gRPC header; there is no separate Fory gRPC runtime source file.

## Implement a Server

Implement the generated synchronous interface and register the generated server
adapter with a normal gRPC C++ server.

```cpp
#include "demo_greeter_service_grpc.h"

#include <memory>
#include <grpcpp/server_builder.h>
#include <grpcpp/security/server_credentials.h>

class MyGreeter final : public demo::greeter::service::Greeter {
 public:
  ::grpc::Status SayHello(::grpc::ServerContext* context,
                          const ::demo::greeter::HelloRequest* request,
                          ::demo::greeter::HelloReply* response) override {
    (void)context;
    response->set_reply("Hello, " + request->name());
    return ::grpc::Status::OK;
  }
};

MyGreeter implementation;
demo::greeter::service::grpc::GreeterServiceGrpc service(&implementation);
::grpc::ServerBuilder builder;
builder.AddListeningPort("0.0.0.0:50051", ::grpc::InsecureServerCredentials());
builder.RegisterService(&service);
std::unique_ptr<::grpc::Server> server = builder.BuildAndStart();
server->Wait();
```

Generated request and response types are serialized by the generated service
code, so service implementations do not perform manual Fory registration.

## Create a Client

Use the generated synchronous client stub:

```cpp
#include "demo_greeter_service_grpc.h"

#include <iostream>
#include <grpcpp/create_channel.h>
#include <grpcpp/security/credentials.h>

auto channel =
    ::grpc::CreateChannel("localhost:50051", ::grpc::InsecureChannelCredentials());
auto stub = demo::greeter::service::grpc::GreeterStub::NewStub(channel);

demo::greeter::HelloRequest request;
request.set_name("Fory");
demo::greeter::HelloReply response;
::grpc::ClientContext context;
::grpc::Status status = stub->SayHello(&context, request, &response);
if (status.ok()) {
  std::cout << response.reply() << std::endl;
}
```

gRPC C++ still owns channel configuration, credentials, deadlines, metadata,
cancellation, retry policy, and transport lifecycle.

## Streaming RPCs

Fory service definitions can use unary, server-streaming, client-streaming, and
bidirectional streaming RPC shapes:

```protobuf
service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
  rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
  rpc LotsOfGreetings (stream HelloRequest) returns (HelloReply);
  rpc Chat (stream HelloRequest) returns (stream HelloReply);
}
```

Generated C++ code follows synchronous gRPC C++ conventions:

- Unary methods return `grpc::Status` and use request and response pointers.
- Server-streaming methods return `std::unique_ptr<grpc::ClientReader<U>>` on
  clients and receive `grpc::ServerWriter<U>*` on servers.
- Client-streaming methods return `std::unique_ptr<grpc::ClientWriter<T>>` on
  clients and receive `grpc::ServerReader<T>*` on servers.
- Bidirectional methods return
  `std::unique_ptr<grpc::ClientReaderWriter<T, U>>` on clients and receive
  `grpc::ServerReaderWriter<U, T>*` on servers.
- The generated codec is used for every message frame, including streaming
  frames.

Use the generated method signatures as the source of truth for concrete request
and response types in your service implementation:

```cpp
::grpc::Status LotsOfReplies(
    ::grpc::ServerContext* context,
    const ::demo::greeter::HelloRequest* request,
    ::grpc::ServerWriter<::demo::greeter::HelloReply>* writer) override {
  (void)context;
  ::demo::greeter::HelloReply reply;
  reply.set_reply("Hello, " + request->name());
  writer->Write(reply);
  reply.set_reply("Welcome, " + request->name());
  writer->Write(reply);
  return ::grpc::Status::OK;
}
```

Generated clients return standard gRPC C++ streaming helpers:

```cpp
demo::greeter::HelloRequest request;
request.set_name("Fory");

::grpc::ClientContext context;
auto reader = stub->LotsOfReplies(&context, request);
demo::greeter::HelloReply reply;
while (reader->Read(&reply)) {
  std::cout << reply.reply() << std::endl;
}
::grpc::Status status = reader->Finish();
```

Complete client streams with `WritesDone()` and always call `Finish()` to obtain
the final status.

The generated descriptors preserve the exact IDL service and method names for
the gRPC path.

## gRPC Runtime Behavior

The generated service companion only supplies Fory serialization and gRPC C++
bindings. Operational behavior remains standard gRPC C++ behavior:

- Deadlines and cancellations
- TLS and authentication
- Status codes and metadata
- Channel and server lifecycle
- Synchronous streaming backpressure

## Troubleshooting

### Missing gRPC C++ Headers or Symbols

Add the gRPC C++ dependency shown above to the target that compiles the
generated service files, and compile the generated `_service_grpc.cc` file
exactly once.

### `UNIMPLEMENTED`

Confirm that the generated server adapter was registered with
`ServerBuilder::RegisterService(...)`, and that the client and server were
generated from the same package, service, and method names.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or provide a separate
protobuf service endpoint for generic protobuf clients.
