---
title: Type Registration
sidebar_position: 4
id: dart_type_registration
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

This page covers how to register user-defined types in Apache Fory™ Dart.

## Registration Modes

Fory Dart supports the same two registration styles used by other xlang runtimes.

### Register by Numeric ID

Numeric IDs are compact on the wire and fast to resolve.

```dart
ModelsFory.register(fory, User, id: 100);
```

Use the same numeric ID for the same logical type in every language.

### Register by Namespace and Type Name

Name-based registration avoids global numeric ID coordination and is often easier across multiple teams.

```dart
ModelsFory.register(
  fory,
  User,
  namespace: 'example',
  typeName: 'User',
);
```

The same `namespace + typeName` pair must be used by every runtime that reads or writes the type.

## Generated Type Registration

Generated types should normally be registered through the generated namespace wrapper:

```dart
UserModelsFory.register(fory, User, id: 100);
```

Internally, the wrapper installs generated metadata and then calls `Fory.register(...)`.

## Register a Manual Serializer

Customized serializers register through `registerSerializer`:

```dart
fory.registerSerializer(
  ExternalType,
  const ExternalTypeSerializer(),
  namespace: 'example',
  typeName: 'ExternalType',
);
```

## Cross-Language Requirements

Registration must match across runtimes:

- same numeric ID, or
- same namespace and type name

The wire format distinguishes built-in xlang type IDs from user-registered type IDs. The user ID is encoded separately as an unsigned varint32, as described in the [xlang serialization specification](../../specification/xlang_serialization_spec.md).

## Common Rules

- Register before the first serialize or deserialize call.
- Register nested user-defined types as well as root types.
- Keep IDs stable once payloads are persisted or exchanged across services.
- Do not mix a numeric ID on one side with a name-based registration on the other side for the same type.

## Related Topics

- [Code Generation](code-generation.md)
- [Cross-Language](cross-language.md)
- [Custom Serializers](custom-serializers.md)
