---
title: gRPC Support
sidebar_position: 6
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

Fory can generate Scala 3 gRPC service companions for schemas that define
services. The generated service code uses normal grpc-java channels, servers,
deadlines, status codes, interceptors, and transport security, while request
and response objects are serialized with Fory instead of protobuf.

Use this mode when both sides of the RPC are generated from the same Fory IDL,
protobuf IDL, or FlatBuffers IDL and you want gRPC transport semantics with
Fory payload encoding. Use standard protobuf gRPC code generation when your API
must be consumed by generic protobuf clients, reflection tools, or components
that expect protobuf message bytes.

## Add Dependencies

Generated Scala service files compile against grpc-java. The `fory-scala`
artifact does not add gRPC as a hard dependency, so add grpc-java dependencies
in your application build and align the version with the rest of your service
stack.

```sbt
libraryDependencies ++= Seq(
  "org.apache.fory" %% "fory-scala" % "<fory-version>",
  "io.grpc" % "grpc-api" % "<grpc-version>",
  "io.grpc" % "grpc-stub" % "<grpc-version>",
  "io.grpc" % "grpc-netty-shaded" % "<grpc-version>"
)
```

Generated Scala models and gRPC service companions are Scala 3 source. The
`fory-scala` artifact remains cross-built for Scala 2.13 and Scala 3, and the
dependency-free `org.apache.fory.scala.rpc` handle traits are available from
the shared artifact.

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

Generate Scala model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --scala_out=./generated/scala --grpc
```

For this schema, the Scala generator emits:

| File                      | Purpose                                      |
| ------------------------- | -------------------------------------------- |
| `HelloRequest.scala`      | Fory model type for the request              |
| `HelloReply.scala`        | Fory model type for the response             |
| `GreeterForyModule.scala` | Fory registration module for generated types |
| `GreeterGrpc.scala`       | grpc-java service base, client, and codecs   |

## Implement a Server

Extend the generated `GreeterGrpc.GreeterImplBase` class and register it with a
standard grpc-java `Server`. Unary RPCs can be implemented with a direct
request-to-response method:

```scala
package demo.greeter

import io.grpc.ServerBuilder

final class GreeterService extends GreeterGrpc.GreeterImplBase {
  override def sayHello(request: HelloRequest): HelloReply =
    HelloReply(s"Hello, ${request.name}")
}

@main def runServer(): Unit = {
  val server = ServerBuilder
    .forPort(50051)
    .addService(new GreeterService)
    .build()
    .start()
  server.awaitTermination()
}
```

Generated request and response types are registered by the generated code, so
service implementations do not perform manual serializer registration.

## Create a Client

Use the generated client with an ordinary grpc-java channel:

```scala
package demo.greeter

import io.grpc.ManagedChannelBuilder
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

@main def runClient(): Unit = {
  val channel = ManagedChannelBuilder
    .forAddress("localhost", 50051)
    .usePlaintext()
    .build()
  try {
    val client = GreeterGrpc.newClient(channel)
    val call = client.sayHello(HelloRequest("Fory"))
    val reply = Await.result(call.asFuture, 30.seconds)
    println(reply.reply)
  } finally {
    channel.shutdownNow()
  }
}
```

Unary Scala-friendly methods return `RpcFuture[A]`. Use `asFuture` for Scala
composition, and call `cancel()` when the RPC should be cancelled before it
completes. The same generated client also exposes grpc-java-style per-method
variants such as observer-based async calls, blocking calls, and
`ListenableFuture` unary calls.

## Streaming RPCs

Fory service definitions can use the same gRPC streaming shapes as grpc-java:

```protobuf
service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
  rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
  rpc LotsOfGreetings (stream HelloRequest) returns (HelloReply);
  rpc Chat (stream HelloRequest) returns (stream HelloReply);
}
```

Generated Scala methods use these shapes:

| IDL shape               | Scala client convenience | grpc-java-style methods                          |
| ----------------------- | ------------------------ | ------------------------------------------------ |
| Unary                   | `RpcFuture[Resp]`        | Async observer, blocking, and `ListenableFuture` |
| Server streaming        | `RpcIterator[Resp]`      | Async observer and blocking iterator             |
| Client streaming        | None                     | `StreamObserver` request stream                  |
| Bidirectional streaming | None                     | `StreamObserver` request and response streams    |

The Scala-friendly convenience layer covers the cases where a direct Scala
handle can keep the important lifecycle controls. Unary calls use
`RpcFuture[A]` so callers can compose with Scala `Future` without losing
cancellation. Server-streaming calls use `RpcIterator[A]` so callers can consume
responses with the normal Scala `Iterator` contract while still being able to
close the underlying RPC. Client-streaming and bidirectional streaming stay on
grpc-java `StreamObserver` APIs because the request stream lifecycle,
completion, cancellation, and flow-control rules are the grpc-java rules.

### Server-Streaming Clients

Use the Scala-friendly method when the client wants pull-style consumption:

```scala
val stream = client.lotsOfReplies(HelloRequest("Fory"))
try {
  while (stream.hasNext) {
    val reply = stream.next()
    println(reply.reply)
  }
} finally {
  stream.close()
}
```

`RpcIterator[A]` extends Scala `Iterator[A]` and `AutoCloseable`. A fully
consumed stream closes when the server completes it. If the caller stops early,
call `close()` or `cancel()` to release the gRPC call and notify the server that
the response stream is no longer needed.

Use the observer overload when the client wants grpc-java async callbacks:

```scala
import io.grpc.stub.StreamObserver

client.lotsOfReplies(
  HelloRequest("Fory"),
  new StreamObserver[HelloReply] {
    override def onNext(value: HelloReply): Unit =
      println(value.reply)

    override def onError(t: Throwable): Unit =
      t.printStackTrace()

    override def onCompleted(): Unit =
      println("done")
  }
)
```

The generated client also exposes a blocking grpc-java-style iterator through
`lotsOfRepliesBlocking(request)`. Prefer the Scala-friendly `RpcIterator` when
you need early cancellation; use the blocking iterator only when matching an
existing grpc-java workflow.

### Client-Streaming Clients

For client-streaming RPCs, the generated method accepts a response observer and
returns the request observer. Send every request with `onNext`, then call
`onCompleted` exactly once when the client has finished sending:

```scala
import io.grpc.stub.StreamObserver

val requests = client.lotsOfGreetings(
  new StreamObserver[HelloReply] {
    override def onNext(value: HelloReply): Unit =
      println(value.reply)

    override def onError(t: Throwable): Unit =
      t.printStackTrace()

    override def onCompleted(): Unit =
      println("server completed")
  }
)

requests.onNext(HelloRequest("Ada"))
requests.onNext(HelloRequest("Grace"))
requests.onCompleted()
```

If the client cannot finish sending requests, signal the failure with
`requests.onError(error)`. Deadlines, cancellation, and call options are the
standard grpc-java stub features, so they are configured on the generated client
stub before starting the call.

### Bidirectional Clients

Bidirectional streaming uses the same grpc-java request observer pattern, but
responses can arrive while the client is still sending requests:

```scala
import io.grpc.stub.StreamObserver

val requests = client.chat(
  new StreamObserver[HelloReply] {
    override def onNext(value: HelloReply): Unit =
      println(value.reply)

    override def onError(t: Throwable): Unit =
      t.printStackTrace()

    override def onCompleted(): Unit =
      println("chat closed")
  }
)

requests.onNext(HelloRequest("first"))
requests.onNext(HelloRequest("second"))
requests.onCompleted()
```

Use grpc-java observer subtypes such as `ClientResponseObserver`,
`ClientCallStreamObserver`, or `ServerCallStreamObserver` when an application
needs manual inbound flow control, readiness callbacks, cancellation handlers,
or direct transport-level cancellation. The generated Scala methods accept the
standard grpc-java observer types, so those advanced grpc-java patterns remain
available without a separate Fory API.

### Streaming Servers

Unary server methods can use the direct Scala-friendly override shown earlier.
Streaming server methods use grpc-java observers. A server-streaming
implementation receives one request and writes zero or more responses:

```scala
import io.grpc.stub.StreamObserver
import scala.util.control.NonFatal

final class GreeterService extends GreeterGrpc.GreeterImplBase {
  override def lotsOfReplies(
      request: HelloRequest,
      responseObserver: StreamObserver[HelloReply]
  ): Unit =
    try {
      responseObserver.onNext(HelloReply(s"Hello, ${request.name}"))
      responseObserver.onNext(HelloReply(s"Welcome, ${request.name}"))
      responseObserver.onCompleted()
    } catch {
      case NonFatal(e) =>
        responseObserver.onError(e)
    }
}
```

Client-streaming servers return an observer for incoming requests and write the
single response when the request stream completes:

```scala
import io.grpc.stub.StreamObserver
import scala.collection.mutable.ArrayBuffer

final class GreeterService extends GreeterGrpc.GreeterImplBase {
  override def lotsOfGreetings(
      responseObserver: StreamObserver[HelloReply]
  ): StreamObserver[HelloRequest] =
    new StreamObserver[HelloRequest] {
      private val names = ArrayBuffer.empty[String]

      override def onNext(value: HelloRequest): Unit =
        names += value.name

      override def onError(t: Throwable): Unit =
        names.clear()

      override def onCompleted(): Unit = {
        responseObserver.onNext(HelloReply(names.mkString("Hello ", ", ", "")))
        responseObserver.onCompleted()
      }
    }
}
```

Bidirectional servers also return an observer for incoming requests, but may
emit responses from each `onNext` call:

```scala
import io.grpc.stub.StreamObserver

final class GreeterService extends GreeterGrpc.GreeterImplBase {
  override def chat(
      responseObserver: StreamObserver[HelloReply]
  ): StreamObserver[HelloRequest] =
    new StreamObserver[HelloRequest] {
      override def onNext(value: HelloRequest): Unit =
        responseObserver.onNext(HelloReply(s"Echo: ${value.name}"))

      override def onError(t: Throwable): Unit = ()

      override def onCompleted(): Unit =
        responseObserver.onCompleted()
    }
}
```

Server-streaming, client-streaming, and bidirectional server methods use
grpc-java `StreamObserver` APIs because streaming completion, request flow
control, cancellation, and backpressure follow grpc-java behavior.

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
grpc-java APIs, but `fory-scala` intentionally does not depend on gRPC.

### Generic Protobuf Clients Cannot Read Payloads

Fory-generated gRPC services use Fory bytes inside gRPC message frames, not
protobuf message bytes. Use a Fory-generated client for Fory-generated services,
or provide a separate protobuf service endpoint for generic protobuf clients.
