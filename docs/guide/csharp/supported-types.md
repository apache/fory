---
title: Supported Types
sidebar_position: 9
id: supported_types
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

This page summarizes built-in and generated type support in Apache Fory™ C#.

## Primitive Types

| C# Type                                  | Notes     |
| ---------------------------------------- | --------- |
| `bool`                                   | Supported |
| `sbyte`, `short`, `int`, `long`          | Supported |
| `byte`, `ushort`, `uint`, `ulong`        | Supported |
| `float`, `double`                        | Supported |
| `string`                                 | Supported |
| `byte[]`                                 | Supported |
| Nullable primitives (for example `int?`) | Supported |

## Arrays

- Primitive numeric arrays (`bool[]`, `int[]`, `ulong[]`, etc.)
- `byte[]`
- General arrays (`T[]`) through collection serializers

## Collections

### List-like

- `List<T>`
- `LinkedList<T>`
- `Queue<T>`
- `Stack<T>`

### Set-like

- `HashSet<T>`
- `SortedSet<T>`
- `ImmutableHashSet<T>`

### Map-like

- `Dictionary<TKey, TValue>`
- `SortedDictionary<TKey, TValue>`
- `SortedList<TKey, TValue>`
- `ConcurrentDictionary<TKey, TValue>`
- `NullableKeyDictionary<TKey, TValue>`

## Time Types

| C# Type          | Wire Type   |
| ---------------- | ----------- |
| `DateOnly`       | `Date`      |
| `DateTime`       | `Timestamp` |
| `DateTimeOffset` | `Timestamp` |
| `TimeSpan`       | `Duration`  |

## User Types

- `[ForyObject]` classes/structs/enums via source-generated serializers
- Custom serializer types registered through `Register<T, TSerializer>(...)`
- `Union` / `Union2<...>` typed union support

## Dynamic Types

Dynamic object APIs (`SerializeObject` / `DeserializeObject`) support:

- Primitive/object values
- Dynamic lists/sets/maps
- Nested dynamic structures

## Notes

- User-defined types should be registered explicitly.
- For cross-language usage, follow the [xlang guide](../xlang/index.md).

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Type Registration](type-registration.md)
- [Cross-Language](cross-language.md)
