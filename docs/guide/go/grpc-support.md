---
title: gRPC Support
sidebar_position: 13
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

Fory can generate Go gRPC service companions for schemas that define services.
The generated code uses grpc-go for transport and a Fory-backed `CodecV2` for
request and response payloads.

Use this mode when every RPC peer is generated from the same Fory IDL, protobuf
IDL, or FlatBuffers IDL and you want gRPC transport semantics with Fory payload
encoding. Use standard protobuf gRPC code generation when clients or tools must
consume protobuf message bytes directly.

## Add Dependencies

Add grpc-go to your module. Fory Go packages do not add gRPC as a hard
dependency.

```bash
go get google.golang.org/grpc
```

Your generated code also imports the Fory Go module:

```bash
go get github.com/apache/fory/go/fory
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

Generate Go model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --go_out=./generated/go --grpc
```

For this schema, the Go generator emits:

| File                           | Purpose                                      |
| ------------------------------ | -------------------------------------------- |
| `greeter/demo_greeter.go`      | Fory model types and registration helpers    |
| `greeter/demo_greeter_grpc.go` | grpc-go client, server interfaces, and codec |

Generated Go methods use exported PascalCase names such as `SayHello`. The
underlying gRPC method path keeps the exact schema method name, so names such as
`sayHello` or `say_hello` continue to route by their schema spelling.

## Implement a Server

Implement the generated `GreeterServer` interface, create a grpc-go server with
the generated Fory codec, and register the service.

```go
package main

import (
    "context"
    "log"
    "net"

    "google.golang.org/grpc"

    "example.com/app/generated/go/greeter"
)

type greeterService struct {
    greeter.UnimplementedGreeterServer
}

func (greeterService) SayHello(
    ctx context.Context,
    request *greeter.HelloRequest,
) (*greeter.HelloReply, error) {
    return &greeter.HelloReply{Reply: "Hello, " + request.Name}, nil
}

func main() {
    listener, err := net.Listen("tcp", ":50051")
    if err != nil {
        log.Fatal(err)
    }

    server := grpc.NewServer(
        grpc.ForceServerCodecV2(greeter.CodecV2{}),
    )
    greeter.RegisterGreeterServer(server, greeterService{})

    if err := server.Serve(listener); err != nil {
        log.Fatal(err)
    }
}
```

`grpc.ForceServerCodecV2(...)` is required so the server decodes incoming frames
with the generated Fory codec instead of the default protobuf codec.

Use the zero-value generated `CodecV2{}` for the service schema. The generated
client methods force the same codec for outgoing calls.

## Create a Client

The generated client constructor accepts a grpc-go connection. Generated client
methods force the generated Fory codec for each call.

```go
package main

import (
    "context"
    "fmt"
    "log"
    "time"

    "google.golang.org/grpc"
    "google.golang.org/grpc/credentials/insecure"

    "example.com/app/generated/go/greeter"
)

func main() {
    conn, err := grpc.NewClient(
        "localhost:50051",
        grpc.WithTransportCredentials(insecure.NewCredentials()),
    )
    if err != nil {
        log.Fatal(err)
    }
    defer conn.Close()

    client := greeter.NewGreeterClient(conn)

    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()

    reply, err := client.SayHello(ctx, &greeter.HelloRequest{Name: "Fory"})
    if err != nil {
        log.Fatal(err)
    }
    fmt.Println(reply.Reply)
}
```

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

Generated Go code follows grpc-go conventions:

- Unary methods take `context.Context`, a request pointer, and return a response
  pointer plus `error`.
- Server-streaming client methods return a generated stream client.
- Client-streaming server methods receive a generated stream server.
- Bidirectional streaming methods use generated stream client and server
  interfaces.
- The generated codec is used for every message frame, including streaming
  frames.

## Operations

The generated service companion only supplies Fory serialization. Operational
behavior remains standard grpc-go behavior:

- Deadlines and cancellations
- TLS and credentials
- Unary and stream interceptors
- Status codes and metadata
- Name resolution and load balancing
- Connection lifecycle and backoff

## Troubleshooting

### Missing `google.golang.org/grpc` Packages

Add grpc-go to your module:

```bash
go get google.golang.org/grpc
```

### `grpc: error while marshaling`

Confirm that both the client and server use the generated `CodecV2{}` and that
the generated model file is compiled into the same package as the gRPC companion.

### `UNIMPLEMENTED`

Confirm that the generated service was registered with
`RegisterGreeterServer(...)`, and that the client and server were generated from
the same package, service, and method names.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or provide a separate
protobuf service endpoint for generic protobuf clients.
