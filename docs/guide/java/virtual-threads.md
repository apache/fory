---
title: Virtual Threads
sidebar_position: 8
id: java_virtual_threads
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

Apache Fory Java uses `buildThreadSafeFory()` for virtual-thread workloads. It builds a fixed-size
shared `ThreadPoolFory` sized to `4 * availableProcessors()`. If you need a different fixed pool
size, use `buildThreadSafeForyPool(poolSize)`.

## Use Binary Input/Output APIs

When you use virtual threads, always use Fory's binary input/output APIs:

- `serialize(Object)` or `serialize(MemoryBuffer, Object)`
- `deserialize(byte[])` or `deserialize(MemoryBuffer)`

Typical usage:

```java
ThreadSafeFory fory = Fory.builder()
    .requireClassRegistration(false)
    .buildThreadSafeFory();

byte[] bytes = fory.serialize(request);
Object value = fory.deserialize(bytes);
```

## Do Not Use Stream APIs For Large Virtual-Thread Counts

Do not use stream or channel based APIs for virtual-thread-heavy workloads:

- `serialize(OutputStream, Object)`
- `deserialize(ForyInputStream)`
- `deserialize(ForyReadableChannel)`

Those APIs keep a pooled `Fory` instance occupied for the whole blocking call. With many virtual
threads, that means many `Fory` instances stay busy while waiting on I/O. Each `Fory` instance
typically uses around `30~50 KB` of memory, so holding many of them during blocking I/O adds up
quickly.

Use stream APIs with virtual threads only when you have at most several hundred virtual threads and
the extra retained `Fory` memory is still acceptable.

## Why Binary APIs Are The Right Fit

Serialization and deserialization are CPU work. Fory is fast, so this CPU time is usually short
compared with network transfer time.

In most cases, you do not need to overlap network transfer with Fory deserialization. Fory
deserialization is usually less than `1/10` of network transfer time, so optimizing the transport
path matters much more than trying to stream one object graph through Fory.

Most RPC systems also already work with framed byte messages instead of Java object streams. For
example, gRPC uses length-delimited frames, which matches Fory's binary APIs naturally.

A good virtual-thread pattern is:

1. Read one framed message into bytes.
2. Call `fory.deserialize(bytes)`.
3. Produce the response object.
4. Call `fory.serialize(response)`.
5. Write the response bytes as the next framed chunk.

## Recommended Pattern

```java
byte[] requestBytes = readOneFrame(channel);
Request request = (Request) fory.deserialize(requestBytes);

Response response = handle(request);
byte[] responseBytes = fory.serialize(response);
writeOneFrame(channel, responseBytes);
```

This keeps Fory on the fast CPU-bound part and keeps blocking I/O outside the serializer.

## Huge Payloads: Chunked Length-Delimited Streaming

For most cases, the normal framed-byte pattern above is enough. Only consider chunked streaming for
very large payloads when you want to overlap transport with serialization and deserialization.

Even in that case, do not use Fory's stream APIs. Instead, split one large payload into multiple
sub object graphs, serialize each sub object graph to a `byte[]`, then write:

1. frame length
2. chunk bytes

On deserialization in virtual threads:

1. read the frame length
2. read exactly that many bytes
3. call `fory.deserialize(chunkBytes)`

This lets the transport move data chunk by chunk while Fory still works on complete binary frames.

```java
for (Object chunk : splitIntoSubGraphs(largePayload)) {
  byte[] bytes = fory.serialize(chunk);
  writeFrame(output, bytes);
}

while (hasMoreFrames(input)) {
  int length = readLength(input);
  byte[] bytes = readBytes(input, length);
  Object chunk = fory.deserialize(bytes);
  consumeChunk(chunk);
}
```

Length-delimited framing is common, and gRPC also uses length-delimited frames instead of Java
object streams, so this pattern fits typical RPC and virtual-thread transports well.
