---
title: Virtual Threads
sidebar_position: 11
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

Apache Fory™ Java provides `buildVirtualThreadSafeFory(...)` for JDK 21+ virtual-thread workloads.

## When `buildVirtualThreadSafeFory` Is Enough

If your code uses the byte-array or `MemoryBuffer` APIs directly, `buildVirtualThreadSafeFory(...)`
is usually enough:

```java
ThreadSafeFory fory = Fory.builder()
    .requireClassRegistration(false)
    .buildVirtualThreadSafeFory();
```

This is the preferred choice when serialization work is CPU-bound and each virtual thread only
needs a `Fory` instance while it is actively encoding or decoding data.

Examples:

- `serialize(Object)`
- `serialize(MemoryBuffer, Object)`
- `deserialize(byte[])`
- `deserialize(MemoryBuffer)`
- `copy(Object)`

## When Blocking I/O Changes The Tradeoff

If your code uses Java I/O or NIO based APIs such as:

- `serialize(OutputStream, Object)`
- `deserialize(ForyInputStream)`
- `deserialize(ForyReadableChannel)`

then virtual threads can yield while blocked on I/O.

That matters because the borrowed `Fory` instance stays occupied for the whole operation, including
the time spent waiting on the stream or channel. In that case, many virtual threads can require
many `Fory` instances at the same time.

Each `Fory` instance uses about `30+ KB` of memory. As a rough estimate, `100 MB` of memory can
hold `3000+` `Fory` instances.

Before using `buildVirtualThreadSafeFory(...)` for stream or channel heavy workloads, evaluate:

- whether you may have too many concurrent virtual threads blocked on I/O
- whether you have enough memory for the needed number of `Fory` instances
- whether a lower pool cap will cause extra waiting

## Pool Exhaustion Behavior

`buildVirtualThreadSafeFory(int maxPoolSize)` limits how many idle `Fory` instances are retained
for reuse after operations complete.

If stream or channel operations cause many virtual threads to block while holding pooled `Fory`
instances, and there are not enough available instances for the ready virtual threads, those ready
virtual threads may still need to yield again while waiting for another `Fory` instance to become
free.

In other words:

- blocked I/O can keep `Fory` instances occupied for longer
- ready virtual threads may wait for a free `Fory` even after their stream becomes ready
- too small a retained pool can add extra scheduling delay under heavy blocking I/O

If that is not acceptable, prefer the first approach: use `buildVirtualThreadSafeFory(...)` only
for non-stream, non-channel serialization paths, or provision enough memory for the number of
`Fory` instances your workload can keep active.
