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

Fory can generate Dart gRPC service companions for schemas that define services.
The generated code uses normal `package:grpc` clients, service bases, method
descriptors, call options, deadlines, cancellations, and status codes, while
request and response objects are serialized with Fory instead of protobuf.

Use this mode when both RPC peers are generated from the same Fory IDL, protobuf
IDL, or FlatBuffers IDL and both sides expect Fory-encoded message bodies. Use
normal protobuf gRPC generation for APIs that must be consumed by generic
protobuf clients, reflection tools, or components that expect protobuf message
bytes.

## Add Dependencies

The `fory` package does not add gRPC dependencies. Add `grpc` (and the
`build_runner` dev dependency that generates the Fory serializer code) in the
application that compiles or runs generated service companions:

```yaml
dependencies:
  fory: ^1.3.0
  grpc: ^4.0.0

dev_dependencies:
  build_runner: ^2.4.0
```

The same dependencies cover both client and server applications.

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

Generate Dart model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --dart_out=./lib/generated --grpc
```

Then run `build_runner` once to emit the Fory serializer part file for the
generated models (this step is required before the code can run):

```bash
dart run build_runner build --delete-conflicting-outputs
```

For this schema, the Dart generator emits (the model file and module are named
from the package leaf, `greeter`):

| File                                        | Purpose                                              |
| ------------------------------------------- | ---------------------------------------------------- |
| `demo/greeter/greeter.dart`                 | Fory model types and the schema module               |
| `demo/greeter/greeter.fory.dart`            | Serializers and registration (built by build_runner) |
| `demo/greeter/greeter_grpc.dart`            | gRPC client, service base, and method descriptors    |
| `GreeterForyModule` in `greeter.dart`       | Fory registration module for generated types         |
| `GreeterServiceBase` in `greeter_grpc.dart` | Base class for server implementations                |
| `GreeterClient` in `greeter_grpc.dart`      | Client stub for gRPC calls                           |

The generated client and service base obtain a ready `Fory` automatically and
register the schema's types on first use, so no manual registration step is
required. To share a custom `Fory` (for example one configured with extra
modules), call `GreeterForyModule.install(yourFory)` once before the first RPC;
this is optional.

## Implement a Server

Extend the generated `GreeterServiceBase` and host it with grpc-dart's `Server`:

```dart
import 'dart:io';

import 'package:grpc/grpc.dart';
import 'demo/greeter/greeter.dart';
import 'demo/greeter/greeter_grpc.dart';

class GreeterService extends GreeterServiceBase {
  @override
  Future<HelloReply> sayHello(ServiceCall call, HelloRequest request) async {
    final reply = HelloReply()..reply = 'Hello, ${request.name}';
    return reply;
  }
}

Future<void> main() async {
  final server = Server.create(services: [GreeterService()]);
  await server.serve(address: InternetAddress.loopbackIPv4, port: 50051);
}
```

## Create a Client

Use the generated client with a `ClientChannel`:

```dart
import 'package:grpc/grpc.dart';
import 'demo/greeter/greeter.dart';
import 'demo/greeter/greeter_grpc.dart';

Future<void> main() async {
  final channel = ClientChannel(
    'localhost',
    port: 50051,
    options: const ChannelOptions(
      credentials: ChannelCredentials.insecure(),
    ),
  );
  final client = GreeterClient(channel);

  final reply = await client.sayHello(HelloRequest()..name = 'Fory');
  print(reply.reply);

  await channel.shutdown();
}
```

## Streaming RPCs

Fory service definitions can use the same gRPC streaming shapes:

```protobuf
service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
  rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
  rpc LotsOfGreetings (stream HelloRequest) returns (HelloReply);
  rpc Chat (stream HelloRequest) returns (stream HelloReply);
}
```

Generated Dart methods follow grpc-dart conventions. Single responses return a
`ResponseFuture<R>` (client-streaming adapts the call with `.single`); streaming
responses return a `ResponseStream<R>`. On the server, single requests arrive as
the message type and streaming requests as a `Stream`; the method returns a
`Future` for single responses and a `Stream` for streaming responses:

| IDL shape                                 | Client method                                        | Server method (override)                               |
| ----------------------------------------- | ---------------------------------------------------- | ------------------------------------------------------ |
| `rpc A (Req) returns (Res)`               | `ResponseFuture<Res> a(Req request, {CallOptions?})` | `Future<Res> a(ServiceCall call, Req request)`         |
| `rpc A (Req) returns (stream Res)`        | `ResponseStream<Res> a(Req request, {CallOptions?})` | `Stream<Res> a(ServiceCall call, Req request)`         |
| `rpc A (stream Req) returns (Res)`        | `ResponseFuture<Res> a(Stream<Req> request, {...})`  | `Future<Res> a(ServiceCall call, Stream<Req> request)` |
| `rpc A (stream Req) returns (stream Res)` | `ResponseStream<Res> a(Stream<Req> request, {...})`  | `Stream<Res> a(ServiceCall call, Stream<Req> request)` |

Server implementations use the generated streaming method shapes directly:

```dart
class GreeterService extends GreeterServiceBase {
  @override
  Stream<HelloReply> lotsOfReplies(
    ServiceCall call,
    HelloRequest request,
  ) async* {
    for (final greeting in ['Hello, ${request.name}', 'Welcome, ${request.name}']) {
      yield HelloReply()..reply = greeting;
    }
  }

  @override
  Future<HelloReply> lotsOfGreetings(
    ServiceCall call,
    Stream<HelloRequest> request,
  ) async {
    final names = <String>[];
    await for (final message in request) {
      names.add(message.name);
    }
    return HelloReply()..reply = names.join(', ');
  }

  @override
  Stream<HelloReply> chat(
    ServiceCall call,
    Stream<HelloRequest> request,
  ) async* {
    await for (final message in request) {
      yield HelloReply()..reply = 'Hello, ${message.name}';
    }
  }
}
```

Generated clients return the standard grpc-dart call objects:

```dart
// Server streaming.
await for (final reply in client.lotsOfReplies(HelloRequest()..name = 'Fory')) {
  print(reply.reply);
}

// Client streaming.
final summary = await client.lotsOfGreetings(
  Stream.fromIterable([
    HelloRequest()..name = 'Ada',
    HelloRequest()..name = 'Grace',
  ]),
);
print(summary.reply);

// Bidirectional streaming.
await for (final reply in client.chat(
  Stream.fromIterable([HelloRequest()..name = 'Fory']),
)) {
  print(reply.reply);
}
```

The generated descriptors preserve the exact IDL service and method names for
the gRPC path, while the Dart methods use camelCase names.

## Generated Module Names

Dart model files and schema modules are named after the package's last segment,
not the gRPC service name. (When a schema has no package, the source file stem is
used instead.)

| Schema input (package)          | Model file          | Schema module           |
| ------------------------------- | ------------------- | ----------------------- |
| `service.fdl` (`demo.greeter`)  | `greeter.dart`      | `GreeterForyModule`     |
| `api.fdl` (`demo.order_events`) | `order_events.dart` | `OrderEventsForyModule` |
| `greeter.fdl` (`demo.greeter`)  | `greeter.dart`      | `GreeterForyModule`     |

A gRPC service named `Greeter` still generates the companion
`<stem>_grpc.dart` with `GreeterClient` and `GreeterServiceBase`; it does not
change the schema module name. If several schema files use the same package
leaf, place them in distinct output directories or choose package/file names that
produce distinct Dart model files.

## Operations

The generated service code only replaces request and response serialization.
All normal gRPC operational features still belong to your gRPC stack:

- Deadlines and cancellations
- TLS and authentication
- Name resolution and load balancing
- Client and server interceptors
- Status codes and metadata
- Channel lifecycle management

## Troubleshooting

### Missing `package:grpc` Types

Add `grpc` to your application dependencies. Generated Fory service files import
grpc-dart APIs, but `fory` intentionally does not depend on gRPC.

### Generated Code References a Missing `.fory.dart` Part

Run `dart run build_runner build --delete-conflicting-outputs` after generating
or regenerating the Dart sources. The serializer part file is produced by
`build_runner`, not by `foryc`.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or expose a separate protobuf
service endpoint for generic protobuf clients.
