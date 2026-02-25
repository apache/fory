---
title: References
sidebar_position: 6
id: references
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

Apache Fory™ C# can preserve shared and circular references when `TrackRef(true)` is enabled.

## Enable Reference Tracking

```csharp
Fory fory = Fory.Builder()
    .TrackRef(true)
    .Build();
```

When enabled:

- Shared object identity is preserved.
- Circular object graphs can be serialized/deserialized safely.

## Circular Reference Example

```csharp
using Apache.Fory;

[ForyObject]
public sealed class Node
{
    public int Value { get; set; }
    public Node? Next { get; set; }
}

Fory fory = Fory.Builder()
    .TrackRef(true)
    .Build();
fory.Register<Node>(200);

Node node = new() { Value = 7 };
node.Next = node;

byte[] payload = fory.Serialize(node);
Node decoded = fory.Deserialize<Node>(payload);

// The cycle is preserved.
System.Diagnostics.Debug.Assert(object.ReferenceEquals(decoded, decoded.Next));
```

## When to Use `TrackRef(false)`

`TrackRef(false)` can be faster for tree-like, acyclic data where reference identity does not matter.

## Related Topics

- [Configuration](configuration.md)
- [Basic Serialization](basic-serialization.md)
- [Thread Safety](thread-safety.md)
