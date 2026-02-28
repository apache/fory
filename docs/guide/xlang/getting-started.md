---
title: Getting Started
sidebar_position: 10
id: getting_started
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

This guide covers installation and basic setup for cross-language serialization in all supported languages.

## Installation

### Java

**Maven:**

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>0.15.0</version>
</dependency>
```

**Gradle:**

```gradle
implementation 'org.apache.fory:fory-core:0.15.0'
```

### Python

```bash
pip install pyfory
```

### Go

```bash
go get github.com/apache/fory/go/fory
```

### Rust

```toml
[dependencies]
fory = "0.13"
```

### JavaScript

```bash
npm install @apache-fory/fory
```

### C++

Use Bazel or CMake to build from source. See [C++ Guide](../cpp/index.md) for details.

## Enable Cross-Language Mode

Each language requires enabling xlang mode to ensure binary compatibility across languages.

### Java

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)  // Enable cross-language mode
    .withRefTracking(true)          // Optional: for circular references
    .build();
```

### Python

```python
import pyfory

# Cross-language mode must be enabled explicitly
fory = pyfory.Fory(xlang=True)

# Enable reference tracking when needed
fory = pyfory.Fory(xlang=True, ref=True)
```

### Go

```go
import forygo "github.com/apache/fory/go/fory"

fory := forygo.NewFory(forygo.WithXlang(true))
// Or with reference tracking
fory := forygo.NewFory(forygo.WithXlang(true), forygo.WithTrackRef(true))
```

### Rust

```rust
use fory::Fory;

let fory = Fory::default().xlang(true);
```

### JavaScript

```javascript
import Fory from "@apache-fory/fory";

const fory = new Fory();
```

### C++

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

auto fory = Fory::builder()
    .xlang(true)
    .build();
```

## Type Registration

Custom types must be registered with consistent names or IDs across all languages.

### Register by Name (Recommended)

Using string names is more flexible and less prone to conflicts:

**Java:**

```java
fory.register(Person.class, "example.Person");
```

**Python:**

```python
fory.register_type(Person, typename="example.Person")
```

**Go:**

```go
fory.RegisterNamedStruct(Person{}, "example.Person")
```

**Rust:**

```rust
use fory::{Fory, ForyObject};

#[derive(ForyObject)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::default().xlang(true);
fory
    .register_by_namespace::<Person>("example", "Person")
    .expect("register Person");
```

**JavaScript:**

```javascript
const description = Type.object("example.Person", {
  name: Type.string(),
  age: Type.int32(),
});
fory.registerSerializer(description);
```

**C++:**

```cpp
fory.register_struct<Person>("example.Person");
// For enums, use register_enum:
// fory.register_enum<Color>("example.Color");
```

### Register by ID

Using numeric IDs is faster and produces smaller binary output:

**Java:**

```java
fory.register(Person.class, 100);
```

**Python:**

```python
fory.register_type(Person, type_id=100)
```

**Go:**

```go
fory.RegisterStruct(Person{}, 100)
```

**C++:**

```cpp
fory.register_struct<Person>(100);
// For enums, use register_enum:
// fory.register_enum<Color>(101);
```

## Hello World Example

A complete example showing serialization in Java and deserialization in Python:

### Java (Serializer)

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import java.nio.file.*;

public class Person {
    public String name;
    public int age;
}

public class HelloWorld {
    public static void main(String[] args) throws Exception {
        Fory fory = Fory.builder()
            .withLanguage(Language.XLANG)
            .build();
        fory.register(Person.class, "example.Person");

        Person person = new Person();
        person.name = "Alice";
        person.age = 30;

        byte[] bytes = fory.serialize(person);
        Files.write(Path.of("person.bin"), bytes);
        System.out.println("Serialized to person.bin");
    }
}
```

### Python (Deserializer)

```python
import pyfory
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: pyfory.Int32Type

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, typename="example.Person")

with open("person.bin", "rb") as f:
    data = f.read()

person = fory.deserialize(data)
print(f"Name: {person.name}, Age: {person.age}")
# Output: Name: Alice, Age: 30
```

## Best Practices

1. **Use consistent type names**: Ensure all languages use the same type name or ID
2. **Enable reference tracking**: If your data has circular or shared references
3. **Reuse Fory instances**: Creating Fory is expensive; reuse instances
4. **Use type annotations**: In Python, use `pyfory.Int32Type` etc. for precise type mapping
5. **Test cross-language**: Verify serialization works across all target languages

## Next Steps

- [Type Mapping](../../specification/xlang_type_mapping.md) - Cross-language type mapping reference
- [Serialization](serialization.md) - Detailed serialization examples
- [Troubleshooting](troubleshooting.md) - Common issues and solutions
