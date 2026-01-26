---
title: Supported Types
sidebar_position: 8
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

This page documents all types supported by Fory C++ serialization.

## Primitive Types

All C++ primitive types are supported with efficient binary encoding:

| Type       | Size   | Fory TypeId | Notes                 |
| ---------- | ------ | ----------- | --------------------- |
| `bool`     | 1 byte | BOOL        | True/false            |
| `int8_t`   | 1 byte | INT8        | Signed byte           |
| `uint8_t`  | 1 byte | INT8        | Unsigned byte         |
| `int16_t`  | 2 byte | INT16       | Signed short          |
| `uint16_t` | 2 byte | INT16       | Unsigned short        |
| `int32_t`  | 4 byte | INT32       | Signed integer        |
| `uint32_t` | 4 byte | INT32       | Unsigned integer      |
| `int64_t`  | 8 byte | INT64       | Signed long           |
| `uint64_t` | 8 byte | INT64       | Unsigned long         |
| `float`    | 4 byte | FLOAT32     | IEEE 754 single       |
| `double`   | 8 byte | FLOAT64     | IEEE 754 double       |
| `char`     | 1 byte | INT8        | Character (as signed) |
| `char16_t` | 2 byte | INT16       | 16-bits characters    |
| `char32_t` | 4 byte | INT32       | 32-bits characters    |

```cpp
int32_t value = 42;
auto bytes = fory.serialize(value).value();
auto decoded = fory.deserialize<int32_t>(bytes).value();
assert(value == decoded);
```

## String Types

| Type               | Fory TypeId | Notes                    |
| ------------------ | ----------- | ------------------------ |
| `std::string`      | STRING      | UTF-8 encoded            |
| `std::string_view` | STRING      | Zero-copy view (read)    |
| `std::u16string`   | STRING      | UTF-16 (converted)       |
| `binary`           | BINARY      | Raw bytes without length |

```cpp
std::string text = "Hello, World!";
auto bytes = fory.serialize(text).value();
auto decoded = fory.deserialize<std::string>(bytes).value();
assert(text == decoded);
```

## Collection Types

### Vector / List

`std::vector<T>` for any serializable element type:

```cpp
std::vector<int32_t> numbers{1, 2, 3, 4, 5};
auto bytes = fory.serialize(numbers).value();
auto decoded = fory.deserialize<std::vector<int32_t>>(bytes).value();

// Nested vectors
std::vector<std::vector<std::string>> nested{
    {"a", "b"},
    {"c", "d", "e"}
};
```

### Set

`std::set<T>` and `std::unordered_set<T>`:

```cpp
std::set<std::string> tags{"cpp", "serialization", "fory"};
auto bytes = fory.serialize(tags).value();
auto decoded = fory.deserialize<std::set<std::string>>(bytes).value();

std::unordered_set<int32_t> ids{1, 2, 3};
```

### Map

`std::map<K, V>` and `std::unordered_map<K, V>`:

```cpp
std::map<std::string, int32_t> scores{
    {"Alice", 100},
    {"Bob", 95}
};
auto bytes = fory.serialize(scores).value();
auto decoded = fory.deserialize<std::map<std::string, int32_t>>(bytes).value();

// Unordered map
std::unordered_map<int32_t, std::string> lookup{
    {1, "one"},
    {2, "two"}
};
```

## Smart Pointers

### std::optional

Nullable wrapper for any type:

```cpp
std::optional<int32_t> maybe_value = 42;
std::optional<int32_t> empty_value = std::nullopt;

auto bytes = fory.serialize(maybe_value).value();
auto decoded = fory.deserialize<std::optional<int32_t>>(bytes).value();
assert(decoded.has_value() && *decoded == 42);
```

### std::shared_ptr

Shared ownership with reference tracking:

```cpp
auto shared = std::make_shared<Person>("Alice", 30);

auto bytes = fory.serialize(shared).value();
auto decoded = fory.deserialize<std::shared_ptr<Person>>(bytes).value();
```

**With reference tracking enabled (`track_ref(true)`):**

- Shared objects are serialized once
- References to the same object are preserved
- Circular references are handled automatically

### std::unique_ptr

Exclusive ownership:

```cpp
auto unique = std::make_unique<Person>("Bob", 25);

auto bytes = fory.serialize(unique).value();
auto decoded = fory.deserialize<std::unique_ptr<Person>>(bytes).value();
```

## Variant Type

`std::variant<Ts...>` for type-safe unions:

```cpp
using MyVariant = std::variant<int32_t, std::string, double>;

MyVariant v1 = 42;
MyVariant v2 = std::string("hello");
MyVariant v3 = 3.14;

auto bytes = fory.serialize(v1).value();
auto decoded = fory.deserialize<MyVariant>(bytes).value();
assert(std::get<int32_t>(decoded) == 42);
```

### std::monostate

Empty variant alternative:

```cpp
using OptionalInt = std::variant<std::monostate, int32_t>;

OptionalInt empty = std::monostate{};
OptionalInt value = 42;
```

## Temporal Types

### Duration

`std::chrono::nanoseconds`:

```cpp
using Duration = std::chrono::nanoseconds;

Duration d = std::chrono::seconds(30);
auto bytes = fory.serialize(d).value();
auto decoded = fory.deserialize<Duration>(bytes).value();
```

### Timestamp

Point in time since Unix epoch:

```cpp
using Timestamp = std::chrono::time_point<std::chrono::system_clock,
                                          std::chrono::nanoseconds>;

Timestamp now = std::chrono::system_clock::now();
auto bytes = fory.serialize(now).value();
auto decoded = fory.deserialize<Timestamp>(bytes).value();
```

### Date

Days since Unix epoch:

```cpp
Date date{18628};  // Days since 1970-01-01

auto bytes = fory.serialize(date).value();
auto decoded = fory.deserialize<Date>(bytes).value();
```

## User-Defined Structs

Any struct can be made serializable with `FORY_STRUCT`:

```cpp
struct Point {
  double x;
  double y;
  double z;
};
FORY_STRUCT(Point, x, y, z);

struct Line {
  Point start;
  Point end;
  std::string label;
};
FORY_STRUCT(Line, start, end, label);
```

## Enum Types

Both scoped and unscoped enums are supported with `FORY_ENUM`:

```cpp
// Scoped enum (C++11 enum class)
enum class Color { RED = 0, GREEN = 1, BLUE = 2 };

// Unscoped enum with incontinuous values
enum Priority : int32_t { LOW = -10, NORMAL = 0, HIGH = 10 };
FORY_ENUM(Priority, LOW, NORMAL, HIGH);
// FORY_ENUM must be defined at namespace scope.

// Usage
Color c = Color::GREEN;
auto bytes = fory.serialize(c).value();
auto decoded = fory.deserialize<Color>(bytes).value();
```

## Unsupported Types

Currently not supported:

- Raw pointers (`T*`) - use smart pointers instead
- `std::tuple<Ts...>` - use structs instead
- `std::array<T, N>` - use `std::vector<T>` instead
- Function pointers
- References (`T&`, `const T&`) - by value only

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using these types
- [Type Registration](type-registration.md) - Registering types
- [Cross-Language](cross-language.md) - Cross-language compatibility
