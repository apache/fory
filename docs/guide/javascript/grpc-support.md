---
title: gRPC Support
sidebar_position: 25
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

Fory can generate JavaScript service companions for schemas that define
services. The generated service code uses normal gRPC transports while request
and response objects are serialized with Fory instead of protobuf.

Use `--grpc` for Node.js server and client code. Use `--grpc-web` for browser
clients that call a gRPC-Web compatible server or proxy.

## Add Dependencies

The generated model file depends on `@apache-fory/core`.

Node.js gRPC companions import `@grpc/grpc-js`:

```bash
npm install @apache-fory/core @grpc/grpc-js
```

Browser gRPC-Web companions import `grpc-web`:

```bash
npm install @apache-fory/core grpc-web
```

Fory does not add gRPC packages as hard dependencies. Add only the transport
package used by your application.

## Define a Service

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

Generate Node.js gRPC bindings:

```bash
foryc service.fdl --javascript_out=./generated/javascript --grpc
```

Generate browser gRPC-Web bindings:

```bash
foryc service.fdl --javascript_out=./generated/javascript --grpc-web
```

Generate both:

```bash
foryc service.fdl --javascript_out=./generated/javascript --grpc --grpc-web
```

For `service.fdl`, JavaScript output contains:

| File                  | Purpose                                       |
| --------------------- | --------------------------------------------- |
| `service.ts`          | Interfaces, enums, unions, and schema helpers |
| `service_grpc.ts`     | Node.js `@grpc/grpc-js` server/client code    |
| `service_grpc_web.ts` | Browser `grpc-web` clients                    |

The generated model file exports `registerXxxTypes(fory)` for custom `Fory`
instances and default root helpers such as `serializeHelloRequest` and
`deserializeHelloRequest`. Generated gRPC companions import those helpers
automatically.

## Implement a Node.js Server

```ts
import * as grpc from "@grpc/grpc-js";
import {
  GreeterHandlers,
  addGreeterService,
} from "./generated/javascript/service_grpc";

const greeter: GreeterHandlers = {
  sayHello(call, callback) {
    callback(null, {
      reply: `Hello, ${call.request.name}`,
    });
  },
};

const server = new grpc.Server();
addGreeterService(server, greeter);
server.bindAsync(
  "0.0.0.0:50051",
  grpc.ServerCredentials.createInsecure(),
  (error, port) => {
    if (error) {
      throw error;
    }
    server.start();
    console.log(`listening on ${port}`);
  },
);
```

## Create a Node.js Client

```ts
import * as grpc from "@grpc/grpc-js";
import { createGreeterClient } from "./generated/javascript/service_grpc";

const client = createGreeterClient(
  "localhost:50051",
  grpc.credentials.createInsecure(),
);

client.sayHello({ name: "Fory" }, (error, reply) => {
  if (error) {
    throw error;
  }
  console.log(reply.reply);
});
```

Use normal `@grpc/grpc-js` metadata, call options, credentials, deadlines, and
interceptors with the generated client and server.

## Create a Browser Client

```ts
import { createGreeterWebClient } from "./generated/javascript/service_grpc_web";

const client = createGreeterWebClient("https://api.example.com", {
  wireFormat: "grpcweb",
});

client.sayHello({ name: "Fory" }, null, (error, reply) => {
  if (error) {
    console.error(error.message);
    return;
  }
  console.log(reply.reply);
});
```

For unary calls, the generated promise client is also available:

```ts
import { createGreeterWebPromiseClient } from "./generated/javascript/service_grpc_web";

const client = createGreeterWebPromiseClient("https://api.example.com");
const reply = await client.sayHello({ name: "Fory" });
console.log(reply.reply);
```

## Streaming RPCs

Node.js companions support all gRPC streaming shapes:

```protobuf
service Greeter {
  rpc SayHello (HelloRequest) returns (HelloReply);
  rpc LotsOfReplies (HelloRequest) returns (stream HelloReply);
  rpc LotsOfGreetings (stream HelloRequest) returns (HelloReply);
  rpc Chat (stream HelloRequest) returns (stream HelloReply);
}
```

Browser gRPC-Web companions support unary and server-streaming methods. gRPC-Web
does not support client-streaming or bidirectional methods; the compiler rejects
those shapes for `--grpc-web`.

Node.js server implementations use the normal `@grpc/grpc-js` streaming call
objects:

```ts
const greeter: GreeterHandlers = {
  sayHello(call, callback) {
    callback(null, { reply: `Hello, ${call.request.name}` });
  },

  lotsOfReplies(call) {
    call.write({ reply: `Hello, ${call.request.name}` });
    call.write({ reply: `Welcome, ${call.request.name}` });
    call.end();
  },

  lotsOfGreetings(call, callback) {
    const names: string[] = [];
    call.on("data", (request) => {
      names.push(request.name);
    });
    call.on("end", () => {
      callback(null, { reply: `Hello, ${names.join(", ")}` });
    });
  },

  chat(call) {
    call.on("data", (request) => {
      call.write({ reply: `Hello, ${request.name}` });
    });
    call.on("end", () => {
      call.end();
    });
  },
};
```

Node.js clients use the generated methods that match the RPC shape:

```ts
const replies = client.lotsOfReplies({ name: "Fory" });
replies.on("data", (reply) => {
  console.log(reply.reply);
});

const greetings = client.lotsOfGreetings((error, reply) => {
  if (error) {
    throw error;
  }
  console.log(reply.reply);
});
greetings.write({ name: "Alice" });
greetings.write({ name: "Bob" });
greetings.end();

const chat = client.chat();
chat.on("data", (reply) => {
  console.log(reply.reply);
});
chat.write({ name: "Alice" });
chat.write({ name: "Bob" });
chat.end();
```

For services with server-streaming methods, the generated gRPC-Web companion
defaults to `grpcwebtext` wire format. Unary-only services default to
`grpcweb`. You can choose the format explicitly:

```ts
const client = createGreeterWebClient("https://api.example.com", {
  wireFormat: "grpcwebtext",
});
```

Browser clients can consume server-streaming RPCs with the callback client:

```ts
const stream = client.lotsOfReplies({ name: "Fory" });

stream.on("data", (reply) => {
  console.log(reply.reply);
});
stream.on("error", (error) => {
  console.error(error.message);
});
stream.on("end", () => {
  console.log("stream ended");
});
```

## Operations

Generated service code only replaces request and response serialization. Normal
gRPC operational features still belong to the transport package:

- TLS and credentials
- Metadata and status codes
- Deadlines and cancellation
- Client and server interceptors
- Load balancing and deployment-specific proxy configuration
