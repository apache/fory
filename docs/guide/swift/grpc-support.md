---
title: gRPC Support
sidebar_position: 12
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

Fory can generate Swift gRPC service companions for schemas that define
services. The companion provides the usual gRPC service providers, clients,
method descriptors, and service metadata, while request and response objects are
serialized with Fory instead of protobuf.

Use this mode when both RPC peers are generated from the same Fory IDL, protobuf
IDL, or FlatBuffers IDL and both sides expect Fory-encoded message bodies. Use
normal protobuf gRPC generation for APIs that must be consumed by generic
protobuf clients, reflection tools, or components that expect protobuf bytes.

The companion targets [grpc-swift](https://github.com/grpc/grpc-swift) 1.x. That
line keeps the same platform floor as the Fory Swift package (macOS 13, iOS 16);
grpc-swift 2.x requires a newer floor.

## Add Dependencies

The `Fory` package does not depend on grpc-swift. Add grpc-swift in the package
that compiles or runs the generated companions:

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/apache/fory.git", from: "1.2.0"),
    .package(url: "https://github.com/grpc/grpc-swift.git", from: "1.23.0"),
],
targets: [
    .target(
        name: "App",
        dependencies: [
            .product(name: "Fory", package: "fory"),
            .product(name: "GRPC", package: "grpc-swift"),
        ]
    )
]
```

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

Generate Swift model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --swift_out=./Sources/App --grpc
```

For this schema the Swift generator emits:

| File                             | Purpose                                      |
| -------------------------------- | -------------------------------------------- |
| `demo/greeter/greeter.swift`     | Fory model types and the `ForyModule` helper |
| `demo/greeter/GreeterGrpc.swift` | gRPC providers, client, and service metadata |

Generated gRPC symbols are prefixed with the package, so the schema above emits
`Demo_Greeter_GreeterAsyncProvider`, `Demo_Greeter_GreeterAsyncClient`, and
`Demo_Greeter_GreeterProvider`. A schema with no package drops the prefix
(`GreeterAsyncProvider`).

## Implement a Server

Conform a type to the generated `async`/`await` provider and host it with a
normal grpc-swift `Server`:

```swift
import Fory
import GRPC
import NIOPosix

final class GreeterService: Demo_Greeter_GreeterAsyncProvider {
  func sayHello(
    request: Demo.Greeter.HelloRequest,
    context: GRPCAsyncServerCallContext
  ) async throws -> Demo.Greeter.HelloReply {
    Demo.Greeter.HelloReply(reply: "Hello, " + request.name)
  }
}

let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
let server = try await Server.insecure(group: group)
  .withServiceProviders([GreeterService()])
  .bind(host: "127.0.0.1", port: 1234)
  .get()
```

Request and response types are registered by the generated schema module that
the companion uses, so server code does not register serializers by hand. An
`EventLoopFuture`-based `Demo_Greeter_GreeterProvider` is also emitted for
servers that do not use `async`/`await`.

## Create a Client

Use the generated async client over a grpc-swift channel:

```swift
import Fory
import GRPC
import NIOPosix

let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
let channel = try GRPCChannelPool.with(
  target: .host("127.0.0.1", port: 1234),
  transportSecurity: .plaintext,
  eventLoopGroup: group)

let client = Demo_Greeter_GreeterAsyncClient(channel: channel)
let reply = try await client.sayHello(Demo.Greeter.HelloRequest(name: "Fory"))
print(reply.reply)
```

## Streaming RPCs

Fory service definitions can use the four gRPC streaming shapes:

```protobuf
service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
  rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
  rpc LotsOfGreetings (stream HelloRequest) returns (HelloReply);
  rpc BidiHello (stream HelloRequest) returns (stream HelloReply);
}
```

Streaming methods present clean request and response types. The provider receives
a response writer (`send(_:)`) for server output and an `AsyncSequence` for client
input; the client returns an `AsyncSequence` of responses for server-streamed
replies:

```swift
// Server side
func lotsOfReplies(
  request: Demo.Greeter.HelloRequest,
  responseStream: Demo_Greeter_GreeterAsyncResponseStream<Demo.Greeter.HelloReply>,
  context: GRPCAsyncServerCallContext
) async throws {
  try await responseStream.send(Demo.Greeter.HelloReply(reply: "Hi " + request.name))
}

// Client side
for try await reply in client.lotsOfReplies(Demo.Greeter.HelloRequest(name: "Fory")) {
  print(reply.reply)
}
```

## gRPC Runtime Behavior

Generated companions carry Fory-encoded bytes inside a private `GRPCPayload`
wrapper. The Swift `Fory` instance is single-threaded, so the wrapper uses one
`Fory` per thread, built from the schema module's configuration and registrations,
which makes concurrent RPCs safe without sharing a single instance. Imported
request and response types resolve to their own namespace and are registered
transitively through the owning module, so a service that crosses an import
boundary works without extra registration.

## Known Limitations

The generated client is async/await only. grpc-swift's `EventLoopFuture` client
returns call objects parameterized by the on-the-wire message type, which would
expose the internal Fory wrapper, so it is not emitted. Both providers (async and
`EventLoopFuture`) are generated.

Interceptors are not generated. grpc-swift interceptors are typed on the
on-the-wire message, which is the internal Fory wrapper; emitting interceptor
hooks would expose that wrapper. Use a custom channel or server configuration for
cross-cutting concerns instead.

Swift models put each package under a nested `enum` namespace, so two schemas that
share a top-level package component (for example `demo.shared` and `demo.greeter`)
both emit `public enum Demo`. Swift treats that as an invalid redeclaration when
both compile into one module. This is a model-generation behavior, not specific to
gRPC, but it also affects a service that imports across such packages. Give the
schemas disjoint top-level packages (for example `shared.models` and
`greeter.api`), or compile them as separate Swift modules.

## Troubleshooting

### Missing grpc-swift Types

If the build cannot find `GRPCAsyncServerCallContext`, `Server`, or
`GRPCChannelPool`, add the grpc-swift dependency and the `GRPC` product to the
target that compiles the generated companion.

### Protobuf Clients Cannot Decode the Service

Generated companions exchange Fory-encoded bodies, not protobuf bytes. A generic
protobuf client cannot decode them. Both peers must be generated from the same
Fory IDL and use the generated Fory companions.
