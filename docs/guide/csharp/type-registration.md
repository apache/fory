---
title: Type Registration
sidebar_position: 5
id: type_registration
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

This page covers how to register user types in Apache Fory™ C#.

## Register by Numeric Type ID

Use explicit IDs for compact and stable cross-service mapping.

```csharp
Fory fory = Fory.Builder().Build();
fory.Register<User>(100);
fory.Register<Order>(101);
```

## Register by Type Name

Use name registration when you prefer symbolic mappings. The single-string overload accepts the
full user-facing name and splits it at the last dot.

```csharp
Fory fory = Fory.Builder().Build();
fory.Register<User>("com.example.User");
```

Names without dots use an empty namespace:

```csharp
fory.Register<User>("User");
```

The split overload is also available when you already have the namespace and final type name
separately:

```csharp
fory.Register<User>("com.example", "User");
```

## Register a Custom Serializer

```csharp
Fory fory = Fory.Builder().Build();
fory.Register<MyType, MyTypeSerializer>(200);
```

Name-based custom serializer registration is also supported:

```csharp
fory.Register<MyType, MyTypeSerializer>("com.example.MyType");
```

## Thread-Safe Registration

`ThreadSafeFory` exposes the same registration APIs. Registrations are propagated to all per-thread Fory instances.

```csharp
using ThreadSafeFory fory = Fory.Builder().BuildThreadSafe();
fory.Register<User>(100);
fory.Register<Order>(101);
```

## Registration Rules

- Register user-defined types on both writer and reader sides.
- Keep ID/name mappings consistent across services and languages.
- For the split overloads, `typeName` must be non-empty and must not contain dots.
- Register before high-volume serialization workloads to avoid missing type metadata.

## Related Topics

- [Basic Serialization](basic-serialization.md)
- [Custom Serializers](custom-serializers.md)
- [Xlang Serialization](xlang-serialization.md)
