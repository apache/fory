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

Fory can generate Rust gRPC service companions for schemas that define
services. The generated code uses `tonic` for transport and Fory for request and
response payload serialization.

Use this mode when every RPC peer is generated from the same Fory IDL, protobuf
IDL, or FlatBuffers IDL and you want gRPC transport semantics with Fory payload
encoding. Use standard protobuf gRPC code generation when clients or tools must
consume protobuf message bytes directly.

## Add Dependencies

Add `tonic` and `bytes` to the crate that compiles the generated service files.
Fory Rust crates do not add gRPC as a hard dependency. Add `tokio` for async
servers and clients, and `tokio-stream` when your service implementation needs
to build streaming responses.

```toml
[dependencies]
fory = "1.1.0"
bytes = "1"
tonic = { version = "0.14", features = ["transport"] }
tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
tokio-stream = "0.1"
```

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

Generate Rust model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --rust_out=./generated/rust --grpc
```

For this schema, the Rust generator emits:

| File                           | Purpose                                      |
| ------------------------------ | -------------------------------------------- |
| `demo_greeter.rs`              | Fory model types and registration helpers    |
| `demo_greeter_service.rs`      | Async service trait and gRPC path constants  |
| `demo_greeter_service_grpc.rs` | tonic client, server wrapper, and Fory codec |

Add the generated files to your crate root:

```rust
pub mod demo_greeter;
pub mod demo_greeter_service;
pub mod demo_greeter_service_grpc;
```

## Implement a Server

Implement the generated async trait and add the generated server wrapper to a
normal `tonic` server.

```rust
use demo_greeter::{HelloReply, HelloRequest};
use demo_greeter_service::Greeter;
use demo_greeter_service_grpc::greeter_server::GreeterServer;
use tonic::{Request, Response, Status};

#[derive(Default)]
struct MyGreeter;

#[tonic::async_trait]
impl Greeter for MyGreeter {
    async fn say_hello(
        &self,
        request: Request<HelloRequest>,
    ) -> Result<Response<HelloReply>, Status> {
        let request = request.into_inner();
        Ok(Response::new(HelloReply {
            reply: format!("Hello, {}", request.name),
        }))
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let addr = "[::1]:50051".parse()?;
    tonic::transport::Server::builder()
        .add_service(GreeterServer::new(MyGreeter::default()))
        .serve(addr)
        .await?;
    Ok(())
}
```

Generated request and response types are serialized by the generated service
code, so service implementations do not perform manual Fory registration.

## Create a Client

Use the generated tonic client:

```rust
use demo_greeter::HelloRequest;
use demo_greeter_service_grpc::greeter_client::GreeterClient;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut client = GreeterClient::connect("http://[::1]:50051").await?;
    let response = client
        .say_hello(HelloRequest {
            name: "Fory".to_string(),
        })
        .await?;
    println!("{}", response.into_inner().reply);
    Ok(())
}
```

`tonic` still owns channel configuration, TLS, deadlines, metadata,
interceptors, and transport lifecycle.

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

Generated Rust code follows tonic conventions:

- Unary methods use `tonic::Request<T>` and return `tonic::Response<U>`.
- Server-streaming methods return a response whose inner value is a stream of
  `Result<U, tonic::Status>`.
- Client-streaming and bidirectional methods receive `tonic::Streaming<T>`.
- The generated client module exposes matching async methods for each service
  method.
- The generated codec is used for every message frame, including streaming
  frames.

Use the generated trait signatures as the source of truth for the concrete
associated stream types in your service implementation.

## Thread Safety and Payload Types

Generated Rust gRPC payloads must be `Send + 'static` so tonic can move request
and response values across async tasks. If a schema uses non-thread-safe
reference metadata for a request or response type, Rust gRPC generation rejects
that service. Use thread-safe reference shapes for gRPC payloads, or keep the
non-thread-safe type out of the RPC boundary.

## Operations

The generated service companion only supplies Fory serialization and tonic
bindings. Operational behavior remains standard tonic behavior:

- Deadlines and cancellations
- TLS and authentication
- Tower middleware and interceptors
- Status codes and metadata
- Channel and server lifecycle
- Backpressure through async streams

## Troubleshooting

### Missing `tonic` or `bytes` Crates

Add the dependencies shown above to the crate that compiles the generated
service files.

### `UNIMPLEMENTED`

Confirm that the generated server wrapper was added with
`Server::builder().add_service(...)`, and that the client and server were
generated from the same package, service, and method names.

### Non-Thread-Safe Reference Errors During Code Generation

Rust gRPC payloads must be `Send + 'static`. Change the request or response
schema to use thread-safe reference shapes, or wrap the non-thread-safe data in a
type that is not part of the gRPC payload.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or provide a separate
protobuf service endpoint for generic protobuf clients.
