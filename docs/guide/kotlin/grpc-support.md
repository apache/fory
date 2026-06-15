---
title: Kotlin gRPC Support
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

Fory IDL can generate Kotlin coroutine gRPC companions. The generated gRPC
files use normal grpc-java and grpc-kotlin APIs, while each request and response
message is serialized with Fory.

## Dependencies

Add Fory Kotlin, KSP, grpc-java, grpc-kotlin, coroutines, and one grpc-java
transport to the application or service module that compiles the generated
source.

```kotlin
plugins {
  id("com.google.devtools.ksp") version "<ksp-version>"
}

dependencies {
  implementation("org.apache.fory:fory-kotlin:<fory-version>")
  ksp("org.apache.fory:fory-kotlin-ksp:<fory-version>")

  implementation("io.grpc:grpc-api:<grpc-version>")
  implementation("io.grpc:grpc-stub:<grpc-version>")
  implementation("io.grpc:grpc-kotlin-stub:<grpc-kotlin-version>")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:<coroutines-version>")

  runtimeOnly("io.grpc:grpc-netty-shaded:<grpc-version>")
}
```

Use a different grpc-java transport if your application already standardizes on
one. Generated Kotlin Fory gRPC does not require `grpc-protobuf` for payload
encoding.

## Generate Code

For a schema such as:

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

run:

```bash
foryc service.fdl --kotlin_out=./generated/kotlin --grpc
```

The compiler writes Kotlin model files, a schema module such as
`ServiceForyModule.kt`, and one service companion such as `GreeterGrpcKt.kt`.
Run KSP when compiling the generated model files so the schema serializers are
available at runtime.

## Server

Implement the generated coroutine base class and register it with a normal
grpc-java server.

```kotlin
import demo.greeter.GreeterGrpcKt
import demo.greeter.HelloReply
import demo.greeter.HelloRequest
import io.grpc.ServerBuilder

class GreeterService : GreeterGrpcKt.GreeterCoroutineImplBase() {
  override suspend fun sayHello(request: HelloRequest): HelloReply =
    HelloReply(reply = "Hello, ${request.name}")
}

val server = ServerBuilder
  .forPort(50051)
  .addService(GreeterService())
  .build()
  .start()
```

Unimplemented generated methods fail with gRPC `UNIMPLEMENTED`. Exceptions
thrown by your service method follow grpc-kotlin server behavior.

## Client

Construct the generated coroutine stub directly from a grpc-java channel.

```kotlin
import demo.greeter.GreeterGrpcKt
import demo.greeter.HelloRequest
import io.grpc.ManagedChannelBuilder

val channel = ManagedChannelBuilder
  .forAddress("localhost", 50051)
  .usePlaintext()
  .build()

val stub = GreeterGrpcKt.GreeterCoroutineStub(channel)
val reply = stub.sayHello(HelloRequest(name = "Fory"))
```

Channel construction, shutdown, deadlines, credentials, interceptors, load
balancing, retries, and server lifecycle stay normal grpc-java/grpc-kotlin
responsibilities.

## Streaming

Streaming RPCs use `kotlinx.coroutines.flow.Flow`.

| IDL shape                                 | Server method                             | Client method                             |
| ----------------------------------------- | ----------------------------------------- | ----------------------------------------- |
| `rpc A (Req) returns (Res)`               | `suspend fun a(request: Req): Res`        | `suspend fun a(request: Req): Res`        |
| `rpc A (Req) returns (stream Res)`        | `fun a(request: Req): Flow<Res>`          | `fun a(request: Req): Flow<Res>`          |
| `rpc A (stream Req) returns (Res)`        | `suspend fun a(requests: Flow<Req>): Res` | `suspend fun a(requests: Flow<Req>): Res` |
| `rpc A (stream Req) returns (stream Res)` | `fun a(requests: Flow<Req>): Flow<Res>`   | `fun a(requests: Flow<Req>): Flow<Res>`   |

The generated method path keeps the exact service and method names from the
schema, for example `/demo.greeter.Greeter/SayHello`.

## Interoperability

Generated Kotlin service companions use Fory binary payloads inside gRPC
frames. They are interoperable with other Fory gRPC companions generated from
the same schema, such as Java, Go, Python, and Rust companions. Generic
protobuf gRPC clients cannot decode these payloads.

Direct union request and response types are supported for Fory IDL services.
For protobuf input, use the protobuf service shapes accepted by the protobuf
frontend; protobuf `oneof` fields are translated into Fory union fields inside
messages.

## Troubleshooting

**Generated service file is missing**

Pass `--grpc` together with `--kotlin_out`. Schemas without service definitions
only generate model files and the schema module.

**Serializer class not found at runtime**

Ensure KSP runs for the generated Kotlin model sources and that
`fory-kotlin-ksp` uses the same Fory version as `fory-kotlin`.

**gRPC classes are unresolved**

Add grpc-java and grpc-kotlin dependencies to the application module. Fory
Kotlin artifacts do not add those dependencies automatically.

**A protobuf client cannot read responses**

Fory gRPC uses Fory binary protocol payloads, not protobuf wire-format messages.
Use generated Fory gRPC companions on both sides for the same service schema.
