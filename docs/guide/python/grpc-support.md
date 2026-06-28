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

Python gRPC generation defaults to the `grpc.aio` AsyncIO API. Generated
servicer bases use `async def` methods, generated stubs are used with
`grpc.aio.Channel` instances, and streaming RPCs use async iterables. Synchronous
`grpcio` companions are still available with `--grpc-python-mode=sync`.

## Install Dependencies

Install `grpcio` alongside `pyfory`. The generated companion imports `grpc` and,
in the default mode, `grpc.aio`, but `pyfory` does not add gRPC as a hard
dependency.

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

| File                   | Purpose                                       |
| ---------------------- | --------------------------------------------- |
| `demo_greeter.py`      | Fory dataclasses and registration helpers     |
| `demo_greeter_grpc.py` | `grpc.aio` stub, servicer base, and registrar |

The module name is derived from the Fory package by replacing dots with
underscores. A schema with no package uses `generated.py` and
`generated_grpc.py`.

## Implement an Async Server

Subclass the generated servicer and register it with a `grpc.aio` server.
Generated Python method names use snake_case, while the gRPC wire path keeps the
original IDL method name.

```python
import asyncio

import grpc.aio

import demo_greeter
import demo_greeter_grpc


class Greeter(demo_greeter_grpc.GreeterServicer):
    async def say_hello(self, request, context):
        return demo_greeter.HelloReply(reply=f"Hello, {request.name}")


async def serve():
    server = grpc.aio.server()
    demo_greeter_grpc.add_servicer(Greeter(), server)
    server.add_insecure_port("[::]:50051")
    await server.start()
    await server.wait_for_termination()


if __name__ == "__main__":
    asyncio.run(serve())
```

Generated request and response types are serialized by the generated companion,
so service implementations do not perform manual Fory registration.

## Create an Async Client

Use the generated stub with a `grpc.aio` channel. Production clients usually
pass a TLS/auth-configured channel:

```python
import asyncio

import grpc
import grpc.aio

import demo_greeter
import demo_greeter_grpc


async def main():
    credentials = grpc.ssl_channel_credentials()
    async with grpc.aio.secure_channel("api.example.com:443", credentials) as channel:
        stub = demo_greeter_grpc.GreeterStub(channel)
        reply = await stub.say_hello(demo_greeter.HelloRequest(name="Fory"))
        print(reply.reply)


if __name__ == "__main__":
    asyncio.run(main())
```

For local tests and development, an insecure channel can be used explicitly:

```python
# Test-only channel. Use a TLS/auth-configured grpc.aio.Channel in production.
async with grpc.aio.insecure_channel("localhost:50051") as channel:
    stub = demo_greeter_grpc.GreeterStub(channel)
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

Default Python gRPC output follows `grpc.aio` conventions:

| IDL shape                                 | Servicer method shape                           | Stub method shape                      |
| ----------------------------------------- | ----------------------------------------------- | -------------------------------------- |
| `rpc A (Req) returns (Res)`               | `async def` returns one response object         | awaitable returns one response object  |
| `rpc A (Req) returns (stream Res)`        | `async def` yields response objects             | returns an async iterator of responses |
| `rpc A (stream Req) returns (Res)`        | consumes an async iterator and returns response | accepts an async iterator of requests  |
| `rpc A (stream Req) returns (stream Res)` | consumes and yields async iterators             | accepts and returns async iterators    |

Servicer methods use snake_case names, while generated descriptors preserve the
exact IDL service and method names for the gRPC path.

Server implementations use async methods and async iteration:

```python
class Greeter(demo_greeter_grpc.GreeterServicer):
    async def lots_of_replies(self, request, context):
        yield demo_greeter.HelloReply(reply=f"Hello, {request.name}")
        yield demo_greeter.HelloReply(reply=f"Welcome, {request.name}")

    async def lots_of_greetings(self, request_iterator, context):
        names = []
        async for request in request_iterator:
            names.append(request.name)
        return demo_greeter.HelloReply(reply=", ".join(names))

    async def chat(self, request_iterator, context):
        async for request in request_iterator:
            yield demo_greeter.HelloReply(reply=f"Hello, {request.name}")
```

Generated clients use `grpc.aio` streaming call shapes:

```python
credentials = grpc.ssl_channel_credentials()
async with grpc.aio.secure_channel("api.example.com:443", credentials) as channel:
    stub = demo_greeter_grpc.GreeterStub(channel)

    async for reply in stub.lots_of_replies(
        demo_greeter.HelloRequest(name="Fory")
    ):
        print(reply.reply)

    async def greeting_requests():
        yield demo_greeter.HelloRequest(name="Ada")
        yield demo_greeter.HelloRequest(name="Grace")

    summary = await stub.lots_of_greetings(greeting_requests())
    print(summary.reply)

    async def chat_requests():
        yield demo_greeter.HelloRequest(name="Fory")
        yield demo_greeter.HelloRequest(name="RPC")

    async for reply in stub.chat(chat_requests()):
        print(reply.reply)
```

## Sync Mode

Use sync mode for existing synchronous `grpcio` applications or environments
that do not run an asyncio event loop. Generate sync companions explicitly:

```bash
foryc service.fdl --python_out=./generated/python --grpc --grpc-python-mode=sync
```

Sync mode emits the same `<module>_grpc.py` filename and public names, but the
servicer methods use regular `def`, and applications use `grpc.server(...)` and
standard `grpc.Channel` instances.

Unary sync server example:

```python
from concurrent import futures

import grpc

import demo_greeter
import demo_greeter_grpc


class Greeter(demo_greeter_grpc.GreeterServicer):
    def say_hello(self, request, context):
        return demo_greeter.HelloReply(reply=f"Hello, {request.name}")


server = grpc.server(futures.ThreadPoolExecutor(max_workers=8))
demo_greeter_grpc.add_servicer(Greeter(), server)
server.add_insecure_port("[::]:50051")
server.start()
server.wait_for_termination()
```

Unary sync client example:

```python
import grpc

import demo_greeter
import demo_greeter_grpc


with grpc.insecure_channel("localhost:50051") as channel:
    stub = demo_greeter_grpc.GreeterStub(channel)
    reply = stub.say_hello(demo_greeter.HelloRequest(name="Fory"))
    print(reply.reply)
```

Sync streaming follows the normal `grpcio` iterator and generator conventions.

## gRPC Runtime Behavior

The generated service companion only supplies Fory serialization callbacks.
Operational behavior remains standard `grpcio` behavior:

- Deadlines and cancellations
- TLS and authentication credentials
- Client and server interceptors
- Status codes, details, and metadata
- Async event loop, channel, and server lifecycle in default mode
- Thread pool sizing for synchronous servers in sync mode

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
