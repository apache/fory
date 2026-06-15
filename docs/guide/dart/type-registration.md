---
title: Type Registration
sidebar_position: 6
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

Fory needs to know which class corresponds to which type in a serialized message. You do this by registering each class before you serialize or deserialize it.

## Choosing a Registration Strategy

Fory offers two strategies. Pick one and use it consistently across every language that reads or writes the type.

### Strategy 1: Numeric ID

Compact and fast. Good when a small team can coordinate IDs across services.

```dart
ModelsForyModule.register(fory, User, id: 100);
```

The same number must be used in every other language:

```java
// Java side
fory.register(User.class, 100);
```

### Strategy 2: Name

More self-describing. Good when multiple teams or packages define types independently and numeric ID coordination is impractical.

```dart
ModelsForyModule.register(
  fory,
  User,
  name: 'example.User',
);
```

Every peer that reads or writes this type must use the same name. Use `.` inside `name`
to add a namespace prefix.

> **Do not mix strategies for the same type.** If one side uses a numeric ID and the other uses a name, deserialization will fail.

## Registering Generated Types

Call the generated `register` function from the `.fory.dart` file. It installs all the serializer metadata for you:

```dart
UserModelsForyModule.register(fory, User, id: 100);
```

## Registering a Custom Serializer

For types that you cannot annotate with `@ForyStruct()`, pass a serializer instance directly:

```dart
fory.registerSerializer(
  ExternalType,
  const ExternalTypeSerializer(),
  name: 'example.ExternalType',
);
```

See [Custom Serializers](custom-serializers.md) for how to implement a serializer.

## Rules to Follow

- Register **before** the first `serialize` or `deserialize` call.
- Register **every** class that can appear in a message, not only the root type.
- Keep IDs (or names) **stable** once payloads are persisted or exchanged across services. Changing them will break deserialization of old messages.
- Do not mix a numeric ID on one side with a name on the other for the same type.

## Xlang Requirements

The same numeric ID or name must be used in every peer that reads or writes the type. See [Xlang Serialization](xlang-serialization.md) for examples.

## Related Topics

- [Code Generation](code-generation.md)
- [Xlang Serialization](xlang-serialization.md)
- [Custom Serializers](custom-serializers.md)
