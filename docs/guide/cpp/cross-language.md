---
title: Cross-Language Serialization
sidebar_position: 10
id: cross_language
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

This page explains how to use Fory for cross-language serialization between C++ and other languages.

## Overview

Apache Fory™ enables seamless data exchange between C++, Java, Python, Go, Rust, and JavaScript. The xlang (cross-language) mode ensures binary compatibility across all supported languages.

## Enabling Cross-Language Mode

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

auto fory = Fory::builder()
    .xlang(true)  // Enable cross-language mode
    .build();
```

## Cross-Language Example

### C++ Producer

```cpp
#include "fory/serialization/fory.h"
#include <fstream>

using namespace fory::serialization;

struct Message {
  std::string topic;
  int64_t timestamp;
  std::map<std::string, std::string> headers;
  std::vector<uint8_t> payload;

  bool operator==(const Message &other) const {
    return topic == other.topic && timestamp == other.timestamp &&
           headers == other.headers && payload == other.payload;
  }
};
FORY_STRUCT(Message, topic, timestamp, headers, payload);

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_struct<Message>(100);

  Message msg{
      "events.user",
      1699999999000,
      {{"content-type", "application/json"}},
      {'h', 'e', 'l', 'l', 'o'}
  };

  auto result = fory.serialize(msg);
  if (result.ok()) {
    auto bytes = std::move(result).value();
    // write to file, send over network, etc.
    std::ofstream file("message.bin", std::ios::binary);
    file.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
  }
  return 0;
}
```

### Java Consumer

```java
import org.apache.fory.Fory;
import org.apache.fory.config.Language;

public class Message {
    public String topic;
    public long timestamp;
    public Map<String, String> headers;
    public byte[] payload;
}

public class Consumer {
    public static void main(String[] args) throws Exception {
        Fory fory = Fory.builder()
            .withLanguage(Language.XLANG)
            .build();
        fory.register(Message.class, 100);  // Same ID as C++

        byte[] bytes = Files.readAllBytes(Path.of("message.bin"));
        Message msg = (Message) fory.deserialize(bytes);

        System.out.println("Topic: " + msg.topic);
        System.out.println("Timestamp: " + msg.timestamp);
    }
}
```

### Python Consumer

```python
import pyfory

class Message:
    topic: str
    timestamp: int
    headers: dict[str, str]
    payload: bytes

fory = pyfory.Fory(xlang=True)
fory.register(Message, type_id=100)  # Same ID as C++

with open("message.bin", "rb") as f:
    data = f.read()

msg = fory.deserialize(data)
print(f"Topic: {msg.topic}")
print(f"Timestamp: {msg.timestamp}")
```

## Type Mapping

### Primitive Types

| C++ Type  | Java Type | Python Type | Go Type   | Rust Type |
| --------- | --------- | ----------- | --------- | --------- |
| `bool`    | `boolean` | `bool`      | `bool`    | `bool`    |
| `int8_t`  | `byte`    | `int`       | `int8`    | `i8`      |
| `int16_t` | `short`   | `int`       | `int16`   | `i16`     |
| `int32_t` | `int`     | `int`       | `int32`   | `i32`     |
| `int64_t` | `long`    | `int`       | `int64`   | `i64`     |
| `float`   | `float`   | `float`     | `float32` | `f32`     |
| `double`  | `double`  | `float`     | `float64` | `f64`     |

### String Types

| C++ Type      | Java Type | Python Type | Go Type  | Rust Type |
| ------------- | --------- | ----------- | -------- | --------- |
| `std::string` | `String`  | `str`       | `string` | `String`  |

### Collection Types

| C++ Type         | Java Type  | Python Type | Go Type          |
| ---------------- | ---------- | ----------- | ---------------- |
| `std::vector<T>` | `List<T>`  | `list`      | `[]T`            |
| `std::set<T>`    | `Set<T>`   | `set`       | `map[T]struct{}` |
| `std::map<K,V>`  | `Map<K,V>` | `dict`      | `map[K]V`        |

### Temporal Types

| C++ Type    | Java Type   | Python Type     | Go Type         |
| ----------- | ----------- | --------------- | --------------- |
| `Timestamp` | `Instant`   | `datetime`      | `time.Time`     |
| `Duration`  | `Duration`  | `timedelta`     | `time.Duration` |
| `Date`      | `LocalDate` | `datetime.date` | `time.Time`     |

## Field Order Requirements

**Critical:** Fields are sorted by snake_case field name. The converted names must match across languages.

### C++

```cpp
struct Person {
  std::string name;   // Field 0
  int32_t age;        // Field 1
  std::string email;  // Field 2
};
FORY_STRUCT(Person, name, age, email);  // Order matters!
```

### Java

```java
public class Person {
    public String name;   // Field 0
    public int age;       // Field 1
    public String email;  // Field 2
}
```

### Python

```python
class Person:
    name: str    # Field 0
    age: int     # Field 1
    email: str   # Field 2
```

## Type ID Consistency

All languages must use the same type IDs:

```cpp
// C++
fory.register_struct<Person>(100);
fory.register_struct<Address>(101);
fory.register_struct<Order>(102);
```

```java
// Java
fory.register(Person.class, 100);
fory.register(Address.class, 101);
fory.register(Order.class, 102);
```

```python
# Python
fory.register(Person, type_id=100)
fory.register(Address, type_id=101)
fory.register(Order, type_id=102)
```

## Compatible Mode

For schema evolution across language boundaries:

```cpp
// C++ with compatible mode
auto fory = Fory::builder()
    .xlang(true)
    .compatible(true)  // Enable schema evolution
    .build();
```

Compatible mode allows:

- Adding new fields (with defaults)
- Removing unused fields
- Reordering fields

## Troubleshooting

### Type Mismatch Errors

```
Error: Type mismatch: expected 100, got 101
```

**Solution:** Ensure type IDs match across all languages.

### Encoding Errors

```
Error: Invalid UTF-8 sequence
```

**Solution:** Ensure strings are valid UTF-8 in all languages.

## Related Topics

- [Configuration](configuration.md) - Builder options
- [Type Registration](type-registration.md) - Registering types
- [Supported Types](supported-types.md) - Type compatibility
