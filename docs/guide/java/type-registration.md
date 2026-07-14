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

This page explains how to register Java classes and how to restrict classes when registration is
disabled.

## Class Registration

Class registration is enabled by default. It prevents input from selecting unregistered
application classes. ID registration also reduces class metadata size.

Keep registration enabled when deserializing untrusted data. If you disable it, configure a
`TypeChecker` as described below.

### Register by ID

Use `Fory#register` with an automatically assigned ID or an explicit ID:

```java
Fory fory = Fory.builder().withXlang(false).build();
fory.register(Order.class);        // Automatically assigned ID
fory.register(Customer.class, 10); // Explicit ID
```

Automatically assigned IDs depend on registration order, so readers and writers must register the
same classes in the same order. With explicit IDs, the order may differ, but each ID must map to the
same class on both sides.

Complete class and serializer registration before the first `serialize`, `deserialize`, or `copy`
call. Later registration attempts are rejected.

`registerSerializer(Foo.class, ...)` is sufficient to use `Foo` when class registration is enabled.
Use `registerSerializerAndType(Foo.class, ...)` when you also want Fory to assign a numeric type ID.

### Register by Name

Numeric IDs provide the smallest class metadata. Register by name when coordinating numeric IDs is
inconvenient:

```java
fory.register(Foo.class, "demo.Foo");
```

If there are no duplicate names for types, use a name without a namespace prefix to reduce
serialized size.

Readers and writers must register the same name for each class. Name registration uses more bytes
than numeric ID registration, but it does not depend on registration order.

## Security Configuration

### Type Checker

When class registration is disabled, use `ForyBuilder#withTypeChecker` to restrict the classes Fory
can serialize and deserialize. Implement `TypeChecker` only when you need custom matching logic.
Array classes are passed to custom checkers in `Class#getName()` format, such as
`[[Lorg.example.Foo;`. Custom checkers must handle this format explicitly. `AllowListChecker`
handles array component names automatically.

### AllowListChecker

`AllowListChecker` provides exact-name and package-prefix allow and disallow rules:

```java
AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
checker.allowClass("org.example.*");
checker.disallowClass("org.example.internal.*");
Fory fory = Fory.builder().withXlang(false)
  .requireClassRegistration(false)
  .withTypeChecker(checker)
  .build();
```

`STRICT` rejects every class outside the allow list. `WARN` rejects disallowed classes and logs a
warning for classes outside the allow list. `DISABLE` skips allow-list checking.

Configure disallow rules before the first `serialize`, `deserialize`, or `copy` call. To use
different disallow rules later, create a new Fory instance.

## Limit Max Deserialization Depth

`ForyBuilder#withMaxDepth` limits nested deserialization depth. The default is 50. Fory throws
`ForyException` when input exceeds the configured depth.

```java
Fory fory = Fory.builder()
  .withXlang(false)
  .withMaxDepth(100)
  .build();
```

## Best Practices

1. Keep class registration enabled for untrusted input.
2. Prefer explicit numeric IDs when readers and writers can share a stable ID mapping.
3. Use the same registration order on both sides when IDs are assigned automatically.
4. Configure all classes, serializers, and disallow rules before the first operation.
5. Configure `AllowListChecker` when class registration is disabled.

## Related Topics

- [Configuration](configuration.md) - ForyBuilder security options
- [Custom Serializers](custom-serializers.md) - Register custom serializers
- [Troubleshooting](troubleshooting.md) - Common registration issues
