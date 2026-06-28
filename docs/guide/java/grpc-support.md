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

| IDL shape                                 | Server method shape                                    | Client method shape                    |
| ----------------------------------------- | ------------------------------------------------------ | -------------------------------------- |
| `rpc A (Req) returns (Res)`               | `void a(Req request, StreamObserver<Res> responses)`   | blocking, async, and future unary stub |
| `rpc A (Req) returns (stream Res)`        | `void a(Req request, StreamObserver<Res> responses)`   | blocking iterator or async observer    |
| `rpc A (stream Req) returns (Res)`        | `StreamObserver<Req> a(StreamObserver<Res> responses)` | async request observer                 |
| `rpc A (stream Req) returns (stream Res)` | `StreamObserver<Req> a(StreamObserver<Res> responses)` | async request observer                 |

Server implementations can use the generated streaming method shapes directly:

```java
package demo.greeter;

import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;

final class GreeterService extends GreeterGrpc.GreeterImplBase {
  @Override
  public void lotsOfReplies(
      HelloRequest request, StreamObserver<HelloReply> responseObserver) {
    HelloReply first = new HelloReply();
    first.setReply("Hello, " + request.getName());
    responseObserver.onNext(first);

    HelloReply second = new HelloReply();
    second.setReply("Welcome, " + request.getName());
    responseObserver.onNext(second);
    responseObserver.onCompleted();
  }

  @Override
  public StreamObserver<HelloRequest> lotsOfGreetings(
      StreamObserver<HelloReply> responseObserver) {
    List<String> names = new ArrayList<>();
    return new StreamObserver<>() {
      @Override
      public void onNext(HelloRequest request) {
        names.add(request.getName());
      }

      @Override
      public void onError(Throwable error) {
        responseObserver.onError(error);
      }

      @Override
      public void onCompleted() {
        HelloReply reply = new HelloReply();
        reply.setReply(String.join(", ", names));
        responseObserver.onNext(reply);
        responseObserver.onCompleted();
      }
    };
  }

  @Override
  public StreamObserver<HelloRequest> chat(
      StreamObserver<HelloReply> responseObserver) {
    return new StreamObserver<>() {
      @Override
      public void onNext(HelloRequest request) {
        HelloReply reply = new HelloReply();
        reply.setReply("Hello, " + request.getName());
        responseObserver.onNext(reply);
      }

      @Override
      public void onError(Throwable error) {
        responseObserver.onError(error);
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }
}
```

Generated clients return the standard grpc-java call shapes:

```java
package demo.greeter;

import io.grpc.stub.StreamObserver;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class StreamingClient {
  private final GreeterGrpc.GreeterBlockingStub blockingStub;
  private final GreeterGrpc.GreeterStub asyncStub;

  StreamingClient(
      GreeterGrpc.GreeterBlockingStub blockingStub,
      GreeterGrpc.GreeterStub asyncStub) {
    this.blockingStub = blockingStub;
    this.asyncStub = asyncStub;
  }

  void run() throws InterruptedException {
    Iterator<HelloReply> replies =
        blockingStub.lotsOfReplies(newRequest("Fory"));
    while (replies.hasNext()) {
      System.out.println(replies.next().getReply());
    }

    CountDownLatch greetingsDone = new CountDownLatch(1);
    StreamObserver<HelloRequest> greetings =
        asyncStub.lotsOfGreetings(new StreamObserver<>() {
          @Override
          public void onNext(HelloReply reply) {
            System.out.println(reply.getReply());
          }

          @Override
          public void onError(Throwable error) {
            greetingsDone.countDown();
          }

          @Override
          public void onCompleted() {
            greetingsDone.countDown();
          }
        });
    greetings.onNext(newRequest("Ada"));
    greetings.onNext(newRequest("Grace"));
    greetings.onCompleted();
    greetingsDone.await(5, TimeUnit.SECONDS);

    CountDownLatch chatDone = new CountDownLatch(1);
    StreamObserver<HelloRequest> chat =
        asyncStub.chat(new StreamObserver<>() {
          @Override
          public void onNext(HelloReply reply) {
            System.out.println(reply.getReply());
          }

          @Override
          public void onError(Throwable error) {
            chatDone.countDown();
          }

          @Override
          public void onCompleted() {
            chatDone.countDown();
          }
        });
    chat.onNext(newRequest("Fory"));
    chat.onCompleted();
    chatDone.await(5, TimeUnit.SECONDS);
  }

  private static HelloRequest newRequest(String name) {
    HelloRequest request = new HelloRequest();
    request.setName(name);
    return request;
  }
}
```

The generated descriptors preserve the exact IDL service and method names for
the gRPC path.

## gRPC Runtime Behavior

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
