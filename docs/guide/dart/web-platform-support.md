---
title: Web Platform Support
sidebar_position: 10
id: dart_web_platform_support
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

Fory Dart supports Dart VM/AOT, Flutter, browser, and Flutter web builds
through generated serializers and platform-specific runtime implementations.
The public API and registration flow are the same across these platforms, but
web builds have stricter integer precision rules because Dart `int` is
represented by JavaScript numbers.

## Supported Targets

The Dart runtime supports:

- Dart VM/JIT applications.
- Dart AOT/native applications.
- Flutter mobile and desktop applications.
- Dart applications compiled to JavaScript for browsers.
- Flutter web applications.
- Generated `@ForyStruct` serializers and manually registered serializers on
  all supported targets.

## Code Generation Is Required

Fory Dart uses explicit registration instead of runtime reflection. For
annotated structs, run code generation and register the generated serializer
before serializing or deserializing values:

```dart
import 'package:fory/fory.dart';

part 'account.fory.dart';

@ForyStruct()
class Account {
  Account();

  String name = '';
  Int64 sequence = Int64(0);
}

void main() {
  final fory = Fory();
  AccountFory.register(
    fory,
    Account,
    namespace: 'example',
    typeName: 'Account',
  );

  final bytes = fory.serialize(Account()..name = 'web');
  final account = fory.deserialize<Account>(bytes);
  print(account.name);
}
```

Generate the companion file before building or testing:

```bash
cd dart/packages/fory
dart run build_runner build --delete-conflicting-outputs
```

The registration call is the same on VM/AOT, Flutter, and web. Manual
serializers use `registerSerializer(...)`; generated structs use the generated
`register` wrapper.

## 64-Bit Integer Rules

Dart VM `int` values are signed 64-bit values. Dart web `int` values are backed
by JavaScript numbers and are precise only in the JS-safe integer range:

```text
-9007199254740991 <= value <= 9007199254740991
```

Use this rule when choosing field types:

| Logical value                            | Recommended Dart field type on web                                   | Notes                                                                |
| ---------------------------------------- | -------------------------------------------------------------------- | -------------------------------------------------------------------- |
| Signed 64-bit value within JS-safe range | `int`                                                                | Works with default `int64` mapping and `@Int64Type` encodings.       |
| Full signed 64-bit range                 | `Int64`                                                              | Preserves values outside the JS-safe range.                          |
| Unsigned 64-bit value                    | `Uint64`                                                             | Required for values that do not fit in signed or JS-safe Dart `int`. |
| 8/16/32-bit integer                      | `Int8`, `Int16`, `Int32`, `Uint8`, `Uint16`, `Uint32` or annotations | Use wrappers or numeric annotations to match peer runtimes exactly.  |

`@Int64Type` controls the wire encoding of a Dart `int` field:

```dart
@ForyStruct()
class SafeCounter {
  SafeCounter();

  @Int64Type(encoding: LongEncoding.tagged)
  int count = 0; // keep web values inside the JS-safe range
}
```

It does not make Dart `int` capable of storing every 64-bit value on web. For
full-range signed values, use `Int64`:

```dart
@ForyStruct()
class FullRangeCounter {
  FullRangeCounter();

  Int64 count = Int64(0);
}
```

For unsigned values, use `Uint64`:

```dart
@ForyStruct()
class StorageExtent {
  StorageExtent();

  Uint64 byteOffset = Uint64(0);
}
```

## Custom Serializers

Custom serializers can use the same `Buffer`, `WriteContext`, and `ReadContext`
APIs on VM/AOT, Flutter, and web. For 64-bit values:

- Use `buffer.writeInt64(Int64(...))` and `buffer.readInt64()` for full-range
  signed 64-bit values.
- Use `buffer.writeUint64(Uint64(...))` and `buffer.readUint64()` for full-range
  unsigned 64-bit values.
- Use `writeInt64FromInt`, `writeVarInt64FromInt`, and matching `AsInt` reads
  only when the value is intended to be a Dart `int` and therefore must stay
  JS-safe on web.

Example:

```dart
final class OffsetSerializer extends Serializer<StorageExtent> {
  const OffsetSerializer();

  @override
  void write(WriteContext context, StorageExtent value) {
    context.buffer.writeUint64(value.byteOffset);
  }

  @override
  StorageExtent read(ReadContext context) {
    return StorageExtent()..byteOffset = context.buffer.readUint64();
  }
}
```

## Collections And Typed Arrays

`List`, `Set`, `Map`, `Uint8List`, numeric typed arrays, `Int64List`, and
`Uint64List` are supported on web. The `Int64List` and `Uint64List`
implementations preserve 64-bit values without depending on JavaScript integer
precision. Use the Fory wrapper list types when the wire type is `int64_array`
or `uint64_array`.

## Testing Browser Builds

Run the package tests in both VM and Chrome when changing code that must work on
web:

```bash
cd dart/packages/fory
dart run build_runner build --delete-conflicting-outputs
dart test
dart test -p chrome
```

If Chrome tests fail with a stale generated file or missing part file, rerun
`build_runner` and then retry the test command from `dart/packages/fory`.

## Common Web Failures

### `Dart int value ... is outside the JS-safe signed int64 range`

The serializer is trying to write a Dart `int` as a signed 64-bit value on web,
but the value is outside the range that JavaScript numbers can represent
exactly. Change the field type to `Int64`, or keep the value inside the JS-safe
range.

### `Int64 value ... is not a JS-safe int`

The deserializer read a full-range `Int64`, but the target field or custom
serializer asked for a Dart `int`. Change the field type to `Int64`, or decode
with `readInt64()` instead of an `AsInt` helper.

### `Uint64 value ... is not a JS-safe int`

The code is converting a `Uint64` to Dart `int` on web. Keep the value as
`Uint64` unless the application has already validated that it is in the
JS-safe non-negative range.

## Related Topics

- [Supported Types](supported-types.md)
- [Field Configuration](field-configuration.md)
- [Code Generation](code-generation.md)
- [Troubleshooting](troubleshooting.md)
