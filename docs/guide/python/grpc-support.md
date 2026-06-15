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

Fory can generate Python gRPC service companions for schemas that define
services. The generated modules use `grpcio` for transport and use Fory to
serialize request and response objects.

Use this mode when every RPC peer is generated from the same Fory IDL, protobuf
IDL, or FlatBuffers IDL and you want gRPC transport semantics with Fory payload
encoding. Use standard protobuf gRPC code generation when clients or tools must
consume protobuf message bytes directly.

## Install Dependencies

Install `grpcio` alongside `pyfory`. The generated companion imports `grpc`, but
`pyfory` does not add gRPC as a hard dependency.

```bash
pip install pyfory grpcio
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

Generate Python model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --python_out=./generated/python --grpc
```

For this schema, the Python generator emits:

| File                   | Purpose                                     |
| ---------------------- | ------------------------------------------- |
| `demo_greeter.py`      | Fory dataclasses and registration helpers   |
| `demo_greeter_grpc.py` | `grpcio` stub, servicer base, and registrar |

The module name is derived from the Fory package by replacing dots with
underscores. A schema with no package uses `generated.py` and
`generated_grpc.py`.

## Implement a Server

Subclass the generated servicer and register it with a normal `grpcio` server.
Generated Python method names use snake_case, while the gRPC wire path keeps the
original IDL method name.

```python
from concurrent import futures

import grpc

import demo_greeter
import demo_greeter_grpc


class Greeter(demo_greeter_grpc.GreeterServicer):
    def say_hello(self, request, context):
        return demo_greeter.HelloReply(reply=f"Hello, {request.name}")


def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
    demo_greeter_grpc.add_servicer(Greeter(), server)
    server.add_insecure_port("[::]:50051")
    server.start()
    server.wait_for_termination()


if __name__ == "__main__":
    serve()
```

Generated request and response types are serialized by the generated companion,
so service implementations do not perform manual Fory registration.

## Create a Client

Use the generated stub with a normal `grpcio` channel:

```python
import grpc

import demo_greeter
import demo_greeter_grpc


def main():
    with grpc.insecure_channel("localhost:50051") as channel:
        stub = demo_greeter_grpc.GreeterStub(channel)
        reply = stub.say_hello(demo_greeter.HelloRequest(name="Fory"))
        print(reply.reply)


if __name__ == "__main__":
    main()
```

`grpcio` still owns channel options, credentials, deadlines, metadata, retries,
and interceptors.

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

Generated Python code follows `grpcio` conventions:

- Unary stubs call `channel.unary_unary(...)`.
- Server-streaming stubs return an iterator over response objects.
- Client-streaming stubs accept an iterator of request objects.
- Bidirectional stubs accept a request iterator and return a response iterator.
- Servicer methods use snake_case names and return either a single response or
  an iterator, depending on the streaming shape.

Example server-streaming implementation:

```python
class Greeter(demo_greeter_grpc.GreeterServicer):
    def lots_of_replies(self, request, context):
        for index in range(3):
            yield demo_greeter.HelloReply(
                reply=f"Hello {request.name}, response {index}"
            )
```

## Operations

The generated service companion only supplies Fory serialization callbacks.
Operational behavior remains standard `grpcio` behavior:

- Deadlines and cancellations
- TLS and authentication credentials
- Client and server interceptors
- Status codes, details, and metadata
- Channel and server lifecycle
- Thread pool sizing for synchronous servers

## Troubleshooting

### `ModuleNotFoundError: No module named 'grpc'`

Install `grpcio` in the environment that runs the generated service module:

```bash
pip install grpcio
```

### `TypeError: Unsupported gRPC servicer type`

Pass an instance of the generated servicer subclass to
`demo_greeter_grpc.add_servicer(...)`. If the schema contains multiple services,
the generated registrar accepts only the matching generated servicer types.

### `UNIMPLEMENTED`

Confirm that the generated servicer was registered with the server, and that the
client and server were generated from the same package, service, and method
names.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or provide a separate
protobuf service endpoint for generic protobuf clients.
