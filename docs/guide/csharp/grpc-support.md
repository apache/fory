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

Fory can generate C# gRPC service companions for schemas that define services.
The generated code uses normal gRPC clients, service bases, method descriptors,
metadata, deadlines, cancellations, and status codes, while request and response
objects are serialized with Fory instead of protobuf.

Use this mode when both RPC peers are generated from the same Fory IDL,
protobuf IDL, or FlatBuffers IDL and both sides expect Fory-encoded message
bodies. Use normal protobuf gRPC generation for APIs that must be consumed by
generic protobuf clients, reflection tools, or components that expect protobuf
message bytes.

## Add Dependencies

The `Apache.Fory` package does not add gRPC dependencies. Add the gRPC packages
in the application that compiles or runs generated service companions.

Server project:

```xml
<ItemGroup>
  <PackageReference Include="Apache.Fory" Version="1.3.0" />
  <PackageReference Include="Grpc.AspNetCore" Version="2.71.0" />
</ItemGroup>
```

Client project:

```xml
<ItemGroup>
  <PackageReference Include="Apache.Fory" Version="1.3.0" />
  <PackageReference Include="Grpc.Core.Api" Version="2.71.0" />
  <PackageReference Include="Grpc.Net.Client" Version="2.71.0" />
</ItemGroup>
```

`Grpc.Core.Api` is the API surface used by generated companions. Server and
client applications can choose their normal gRPC hosting or transport packages.

## Define a Service

Service definitions can come from Fory IDL, protobuf IDL, or FlatBuffers
`rpc_service` definitions. A Fory IDL service looks like this:

```protobuf
package demo.greeter;
option csharp_namespace = "Demo.Greeter";

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

Generate C# model and gRPC companion code with `--grpc`:

```bash
foryc service.fdl --csharp_out=./Generated --grpc
```

For this schema, the C# generator emits:

| File                                        | Purpose                                      |
| ------------------------------------------- | -------------------------------------------- |
| `Demo/Greeter/Service.cs`                   | Fory model types and schema module           |
| `Demo/Greeter/GreeterGrpc.cs`               | gRPC service base, client, and descriptors   |
| `ServiceForyModule` in `Service.cs`         | Fory registration module for generated types |
| `Greeter.GreeterBase` in `GreeterGrpc.cs`   | Base class for server implementations        |
| `Greeter.GreeterClient` in `GreeterGrpc.cs` | Client stub for gRPC calls                   |

## Implement a Server

Extend the generated `Greeter.GreeterBase` class and map it through normal
ASP.NET Core gRPC hosting:

```csharp
using System.Threading.Tasks;
using Demo.Greeter;
using Grpc.Core;

var builder = WebApplication.CreateBuilder(args);
builder.Services.AddGrpc();

var app = builder.Build();
app.MapGrpcService<GreeterService>();
app.Run();

public sealed class GreeterService : Greeter.GreeterBase
{
    public override Task<HelloReply> SayHello(
        HelloRequest request,
        ServerCallContext context)
    {
        return Task.FromResult(new HelloReply
        {
            Reply = "Hello, " + request.Name,
        });
    }
}
```

Generated request and response types are registered by the generated schema
module used by the service companion, so service implementations do not perform
manual serializer registration.

## Create a Client

Use the generated client with a `Grpc.Net.Client` call invoker:

```csharp
using Demo.Greeter;
using Grpc.Net.Client;

using GrpcChannel channel = GrpcChannel.ForAddress("https://localhost:5001");
var client = new Greeter.GreeterClient(channel.CreateCallInvoker());

HelloReply reply = await client.SayHelloAsync(
    new HelloRequest { Name = "Fory" });
Console.WriteLine(reply.Reply);
```

The generated client also exposes synchronous unary methods and the normal
gRPC streaming call shapes.

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

Generated C# service methods follow gRPC C# conventions:

| IDL shape                                 | Server method                                                                 | Client method                                      |
| ----------------------------------------- | ----------------------------------------------------------------------------- | -------------------------------------------------- |
| `rpc A (Req) returns (Res)`               | `Task<Res> A(Req request, ServerCallContext context)`                         | `Res A(...)` and `AsyncUnaryCall<Res> AAsync(...)` |
| `rpc A (Req) returns (stream Res)`        | `Task A(Req request, IServerStreamWriter<Res> responseStream, ...)`           | `AsyncServerStreamingCall<Res> A(...)`             |
| `rpc A (stream Req) returns (Res)`        | `Task<Res> A(IAsyncStreamReader<Req> requestStream, ...)`                     | `AsyncClientStreamingCall<Req, Res> A(...)`        |
| `rpc A (stream Req) returns (stream Res)` | `Task A(IAsyncStreamReader<Req> requestStream, IServerStreamWriter<Res> ...)` | `AsyncDuplexStreamingCall<Req, Res> A(...)`        |

Server implementations can use the generated streaming method shapes directly:

```csharp
using System.Collections.Generic;
using System.Threading.Tasks;
using Demo.Greeter;
using Grpc.Core;

public sealed class GreeterService : Greeter.GreeterBase
{
    public override async Task LotsOfReplies(
        HelloRequest request,
        IServerStreamWriter<HelloReply> responseStream,
        ServerCallContext context)
    {
        foreach (string reply in new[]
        {
            "Hello, " + request.Name,
            "Welcome, " + request.Name,
        })
        {
            await responseStream.WriteAsync(new HelloReply { Reply = reply });
        }
    }

    public override async Task<HelloReply> LotsOfGreetings(
        IAsyncStreamReader<HelloRequest> requestStream,
        ServerCallContext context)
    {
        List<string> names = new();
        while (await requestStream.MoveNext(context.CancellationToken))
        {
            names.Add(requestStream.Current.Name);
        }

        return new HelloReply { Reply = string.Join(", ", names) };
    }

    public override async Task Chat(
        IAsyncStreamReader<HelloRequest> requestStream,
        IServerStreamWriter<HelloReply> responseStream,
        ServerCallContext context)
    {
        while (await requestStream.MoveNext(context.CancellationToken))
        {
            await responseStream.WriteAsync(new HelloReply
            {
                Reply = "Hello, " + requestStream.Current.Name,
            });
        }
    }
}
```

Generated clients return the standard gRPC streaming call objects:

```csharp
using System;
using System.Threading;
using System.Threading.Tasks;
using Demo.Greeter;
using Grpc.Core;

using AsyncServerStreamingCall<HelloReply> replies =
    client.LotsOfReplies(new HelloRequest { Name = "Fory" });
while (await replies.ResponseStream.MoveNext(CancellationToken.None))
{
    Console.WriteLine(replies.ResponseStream.Current.Reply);
}

using AsyncClientStreamingCall<HelloRequest, HelloReply> greetings =
    client.LotsOfGreetings();
await greetings.RequestStream.WriteAsync(new HelloRequest { Name = "Ada" });
await greetings.RequestStream.WriteAsync(new HelloRequest { Name = "Grace" });
await greetings.RequestStream.CompleteAsync();
HelloReply summary = await greetings.ResponseAsync;
Console.WriteLine(summary.Reply);

using AsyncDuplexStreamingCall<HelloRequest, HelloReply> chat = client.Chat();
Task readTask = Task.Run(async () =>
{
    while (await chat.ResponseStream.MoveNext(CancellationToken.None))
    {
        Console.WriteLine(chat.ResponseStream.Current.Reply);
    }
});
await chat.RequestStream.WriteAsync(new HelloRequest { Name = "Fory" });
await chat.RequestStream.CompleteAsync();
await readTask;
```

The generated descriptors preserve the exact IDL service and method names for
the gRPC path.

## Generated Module Names

C# schema module names come from the source file stem. They do not come from
`csharp_namespace` and they do not come from gRPC service names.

For example:

| Schema input       | Model file       | Schema module           |
| ------------------ | ---------------- | ----------------------- |
| `service.fdl`      | `Service.cs`     | `ServiceForyModule`     |
| `order-events.fdl` | `OrderEvents.cs` | `OrderEventsForyModule` |
| `greeter.fdl`      | `Greeter.cs`     | `GreeterForyModule`     |
| `Greeter.fdl`      | `Greeter.cs`     | `GreeterForyModule`     |

A gRPC service named `Greeter` still generates the service companion
`GreeterGrpc.cs`; it does not change the schema module name. This lets several
schema files target the same C# namespace without colliding. No
namespace-derived or service-derived module alias is generated.

## gRPC Runtime Behavior

The generated service code only replaces request and response serialization.
All normal gRPC operational features still belong to your gRPC stack:

- Deadlines and cancellations
- TLS and authentication
- Name resolution and load balancing
- Client and server interceptors
- Status codes and metadata
- Channel pooling and lifecycle management

## Troubleshooting

### Missing `Grpc.Core` Types

Add `Grpc.Core.Api` or a server/client package that brings it transitively.
Generated Fory service files import gRPC APIs, but `Apache.Fory` intentionally
does not depend on gRPC.

### Protobuf Clients Cannot Decode the Service

Fory gRPC companions do not use protobuf wire encoding for messages. Use a
Fory-generated client for Fory-generated services, or expose a separate protobuf
service endpoint for generic protobuf clients.
