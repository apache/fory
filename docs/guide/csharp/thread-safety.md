---
title: Thread Safety
sidebar_position: 10
id: thread_safety
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

Apache Fory C# provides two runtime forms with different threading guarantees.

## `Fory` (Single-Threaded Runtime)

`Fory` is optimized for single-threaded reuse and must not be used concurrently by multiple threads.

```csharp
Fory fory = Fory.Builder().Build();
```

Use one `Fory` instance per thread when managing thread affinity explicitly.

## `ThreadSafeFory` (Concurrent Wrapper)

`ThreadSafeFory` wraps one `Fory` instance per thread and exposes thread-safe APIs.

```csharp
using Apache.Fory;

using ThreadSafeFory fory = Fory.Builder()
    .Compatible(true)
    .TrackRef(true)
    .BuildThreadSafe();

fory.Register<MyType>(100);

Parallel.For(0, 64, i =>
{
    byte[] payload = fory.Serialize(i);
    int decoded = fory.Deserialize<int>(payload);
});
```

## Registration Behavior

- `ThreadSafeFory.Register(...)` stores registrations centrally.
- Existing per-thread runtimes are updated.
- New threads receive all previous registrations automatically.

## Disposal

`ThreadSafeFory` implements `IDisposable` and should be disposed when no longer needed.

```csharp
using ThreadSafeFory fory = Fory.Builder().BuildThreadSafe();
```

## Related Topics

- [Configuration](configuration.md)
- [Type Registration](type-registration.md)
- [References](references.md)
