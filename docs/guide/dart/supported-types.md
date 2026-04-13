---
title: Supported Types
sidebar_position: 7
id: dart_supported_types
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

This page summarizes the main xlang-facing types in Apache Fory Dart.

## Primitive-Like Dart Values

The runtime supports common Dart values directly where they map cleanly to xlang types:

- `bool`
- `int`
- `double`
- `String`
- `Uint8List` and other supported typed byte containers
- `List`, `Set`, and `Map`
- `DateTime`-adjacent values through explicit wrappers such as `Timestamp` and `LocalDate`

Because Dart `int` and `double` are broader runtime concepts than many xlang scalar types, wrapper types and field annotations are used when the exact wire type matters.

## Integer Wrappers

Use wrapper classes when you need an exact xlang fixed-width integer type.

```dart
final Int8 tiny = Int8(-1);
final Int16 shortValue = Int16(7);
final Int32 age = Int32(36);
final UInt8 flags = UInt8(255);
final UInt16 port = UInt16(65535);
final UInt32 count = UInt32(4000000000);
```

Available wrappers:

- `Int8`
- `Int16`
- `Int32`
- `UInt8`
- `UInt16`
- `UInt32`

Each wrapper normalizes the stored value to the target bit width.

## Floating-Point Wrappers

Use explicit wrapper types when you need a precise non-`float64` wire type.

- `Float16`
- `Float32`

Dart `double` maps naturally to the xlang `float64` family.

## Time and Date Types

The runtime exposes explicit xlang-friendly temporal wrappers:

- `Timestamp` for seconds-plus-nanoseconds UTC instants
- `LocalDate` for timezone-free calendar dates

```dart
final timestamp = Timestamp.fromDateTime(DateTime.now().toUtc());
final birthday = LocalDate(1990, 12, 1);
```

## Structs and Enums

User-defined structs and enums are supported through generated registration or customized serializers.

```dart
@ForyStruct()
class User {
  User();

  String name = '';
  Int32 age = Int32(0);
}
```

## Collections

Fory Dart supports the core xlang collection shapes:

- `List<T>`
- `Set<T>`
- `Map<K, V>`

Cross-language compatibility still depends on element and key types having compatible peer-language mappings. Avoid mutable collection keys.

## Typed Arrays and Binary Values

The xlang spec defines dedicated wire types for binary payloads and numeric arrays. In Dart, use the typed values exported by the runtime and test the exact round trip you need with your peer languages.

## Exact Mapping Rules

For the complete cross-language mapping, see:

- [Xlang type mapping](../../specification/xlang_type_mapping.md)
- [Cross-Language](cross-language.md)

## Related Topics

- [Field Configuration](field-configuration.md)
- [Cross-Language](cross-language.md)
- [Schema Evolution](schema-evolution.md)
