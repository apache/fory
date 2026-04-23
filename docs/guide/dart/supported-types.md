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

This page lists the Dart types you can use in Fory messages, and flags where you need to be careful for cross-language compatibility.

## Built-in Primitive Types

The following Dart types serialize directly without any special handling:

| Dart type            | Cross-language notes                                                                                        |
| -------------------- | ----------------------------------------------------------------------------------------------------------- |
| `bool`               | Direct mapping                                                                                              |
| `int`                | Serialized as 64-bit by default. Use wrappers or `@Int32Type` etc. when the peer expects a narrower integer |
| `double`             | Maps to 64-bit float. Use `Float32` wrapper when the peer expects 32-bit                                    |
| `String`             | Direct mapping                                                                                              |
| `Uint8List`          | Binary blob                                                                                                 |
| `List`, `Set`, `Map` | Supported; element types must also be supported                                                             |
| `DateTime`           | Use `Timestamp` or `LocalDate` wrappers for explicit semantics                                              |

## Integer Wrappers

Dart VM/native `int` can represent signed 64-bit values, while Dart web `int`
is limited to JavaScript-safe integer precision. If the peer language expects a
32-bit integer (Java `int`, Go `int32`, C# `int`) and you send a Dart `int`,
the deserialization may fail or silently truncate. For browser and Flutter web
precision rules, see [Web Platform Support](web-platform-support.md).

Use an integer wrapper or field annotation to select the wire type explicitly:

```dart
final Int8 tiny = Int8(-1);        // 8-bit signed
final Int16 shortValue = Int16(7); // 16-bit signed
final Int32 age = Int32(36);       // 32-bit signed, varint by default
final Int64 seq = Int64(0);        // signed 64-bit, varint by default
final Uint8 flags = Uint8(255);    // 8-bit unsigned
final Uint16 port = Uint16(65535); // 16-bit unsigned
final Uint32 count = Uint32(4000000000); // 32-bit unsigned, varint by default
final Uint64 offset = Uint64(0);   // unsigned 64-bit, varint by default
```

Each wrapper clamps or normalizes the stored value to the target bit width.
Root `Int32`, `Int64`, `Uint32`, and `Uint64` values use compact varint wire
types by default. Use `@Int64Type`, `@Uint32Type`, `@Uint64Type`, or generated
field metadata when a fixed-width or tagged encoding is required.

On Dart VM, `Int64` and `Uint64` are extension types over `int`. Once a value is
passed through an `Object`-typed dynamic/root boundary, the VM cannot recover
whether it was originally a plain `int`, `Int64`, or `Uint64`. Use generated
field metadata or explicit `Buffer` APIs when native VM payloads must preserve
unsigned 64-bit identity across dynamic boundaries. Dart web uses wrapper
classes, so web root `Uint64` values keep `varuint64` metadata.

## Floating-Point Wrappers

Dart `double` maps to 64-bit float. If the peer uses reduced-precision
floating-point values, use an explicit wrapper:

- `Float32` — 32-bit float (matches Java `float`, C# `float`, Go `float32`)
- `Float16` — half-precision, for specialized numeric payloads
- `Bfloat16` — brain floating point, useful when interoperating with ML-oriented payloads

For contiguous 16-bit floating-point arrays, use `Float16List` and
`Bfloat16List` rather than `Uint16List` when the wire type is `float16_array`
or `bfloat16_array`.

## Time and Date Types

Avoid sending raw `DateTime` across languages — time zone handling and epoch differences vary. Use the explicit wrappers instead:

- `Timestamp` — a UTC instant with nanosecond precision (seconds + nanoseconds)
- `LocalDate` — a calendar date without time or time zone
- `Duration` — an elapsed time value using Dart's built-in `Duration`

```dart
final now = Timestamp.fromDateTime(DateTime.now().toUtc());
final birthday = LocalDate(1990, 12, 1);
final timeout = const Duration(seconds: 30);
```

The temporal wrappers expose conversion helpers:

- `Timestamp.fromDateTime(...)` and `timestamp.toDateTime()`
- `LocalDate.fromEpochDay(Int64(...))`, `date.toEpochDay()` returns `Int64`
- `LocalDate.fromDateTime(...)` and `date.toDateTime()`

`Duration` support in Dart is exact to microseconds. Incoming xlang duration
payloads that use sub-microsecond nanoseconds are rejected instead of being
silently truncated.

## Structs and Enums

Annotate classes with `@ForyStruct()` and run `build_runner` to make them serializable. Enums in the same file are included automatically.

```dart
@ForyStruct()
class User {
  User();

  String name = '';
  Int32 age = Int32(0); // use Int32 when peers expect a 32-bit integer
}
```

See [Code Generation](code-generation.md).

## Collections

Fory supports `List<T>`, `Set<T>`, and `Map<K, V>`. Element and key types must also be serializable types. Avoid using mutable objects as map keys.

## Compatibility Tip

When in doubt about whether a Dart type will match what the peer expects, use the explicit wrapper types. Guessing the wrong numeric width is one of the most common cross-language bugs.

## Related Topics

- [Field Configuration](field-configuration.md)
- [Cross-Language](cross-language.md)
- [Schema Evolution](schema-evolution.md)
