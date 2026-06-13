---
title: gRPC Support
sidebar_position: 17
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

Fory can generate Java gRPC service companions for schemas that define
services. The generated service code uses normal grpc-java channels, servers,
deadlines, status codes, interceptors, and transport security, while request
and response objects are serialized with Fory instead of protobuf.

Use this mode when both sides of the RPC are generated from the same Fory IDL,
protobuf IDL, or FlatBuffers IDL and you want gRPC transport semantics with
Fory payload encoding. Use standard protobuf gRPC code generation when your API
must be consumed by generic protobuf clients, reflection tools, or components
that expect protobuf message bytes.

For Scala generated grpc-java companions, see
[Scala gRPC Support](../scala/grpc-support.md). For Kotlin coroutine stubs and
service bases, see [Kotlin gRPC Support](../kotlin/grpc-support.md).

## Add Dependencies

The generated Java service files compile against grpc-java. Fory Java artifacts
do not add gRPC as a hard dependency, so add grpc-java dependencies in your
application build and align the version with the rest of your service stack.

Maven:

```xml
<dependencies>
  <dependency>
    <groupId>org.apache.fory</groupId>
    <artifactId>fory-core</artifactId>
    <version>${fory.version}</version>
  </dependency>
  <dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-api</artifactId>
    <version>${grpc.version}</version>
  </dependency>
  <dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-stub</artifactId>
    <version>${grpc.version}</version>
  </dependency>
  <dependency>
    <groupId>io.grpc</groupId>
    <artifactId>grpc-netty-shaded</artifactId>
    <version>${grpc.version}</version>
  </dependency>
</dependencies>
```

Gradle:

```kotlin
dependencies {
  implementation("org.apache.fory:fory-core:$foryVersion")
  implementation("io.grpc:grpc-api:$grpcVersion")
  implementation("io.grpc:grpc-stub:$grpcVersion")
  implementation("io.grpc:grpc-netty-shaded:$grpcVersion")
}
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

Generate Java model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --java_out=./generated/java --grpc
```

For this schema, the Java generator emits:

| File                     | Purpose                                      |
| ------------------------ | -------------------------------------------- |
| `HelloRequest.java`      | Fory model type for the request              |
| `HelloReply.java`        | Fory model type for the response             |
| `GreeterForyModule.java` | Fory registration module for generated types |
| `GreeterGrpc.java`       | grpc-java service base class, stubs, codecs  |

## Implement a Server

Extend the generated `GreeterGrpc.GreeterImplBase` class and register it with a
standard grpc-java `Server`.

```java
package demo.greeter;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

final class GreeterService extends GreeterGrpc.GreeterImplBase {
  @Override
  public void sayHello(
      HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    HelloReply reply = new HelloReply();
    reply.setReply("Hello, " + request.getName());
    responseObserver.onNext(reply);
    responseObserver.onCompleted();
  }
}

public final class GreeterServer {
  public static void main(String[] args) throws Exception {
    Server server =
        ServerBuilder.forPort(50051)
            .addService(new GreeterService())
            .build()
            .start();
    server.awaitTermination();
  }
}
```

Generated request and response types are registered by the generated code, so
service implementations do not perform manual serializer registration.

## Create a Client

Use the generated stubs with an ordinary grpc-java channel:

```java
package demo.greeter;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public final class GreeterClient {
  public static void main(String[] args) {
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", 50051)
            .usePlaintext()
            .build();
    try {
      GreeterGrpc.GreeterBlockingStub stub =
          GreeterGrpc.newBlockingStub(channel);

      HelloRequest request = new HelloRequest();
      request.setName("Fory");
      HelloReply reply = stub.sayHello(request);
      System.out.println(reply.getReply());
    } finally {
      channel.shutdownNow();
    }
  }
}
```

For asynchronous calls, use `GreeterGrpc.newStub(channel)`. For future-based
unary calls, use `GreeterGrpc.newFutureStub(channel)`.

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

Generated Java service methods follow grpc-java conventions:

- Unary and server-streaming methods receive a request object and a
  `StreamObserver` for responses.
- Client-streaming and bidirectional methods return a `StreamObserver` for
  incoming requests and receive a `StreamObserver` for outgoing responses.
- Blocking stubs expose the grpc-java blocking APIs for supported streaming
  shapes.

## Operations

The generated service code only replaces request and response serialization.
All normal gRPC operational features still belong to grpc-java:

- Deadlines and cancellations
- TLS and authentication
- Name resolution and load balancing
- Client and server interceptors
- Status codes and metadata
- Channel pooling and lifecycle management

## Troubleshooting

### Missing `io.grpc` or Guava Classes

Add the grpc-java dependencies shown above. Generated Fory service files import
grpc-java APIs, but Fory Java artifacts intentionally do not depend on gRPC.

### `UNIMPLEMENTED`

Confirm that the generated service implementation is registered with
`ServerBuilder.addService(...)`, and that the client and server were generated
from the same package, service, and method names.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or provide a separate
protobuf service endpoint for generic protobuf clients.
