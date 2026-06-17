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
  <version>1.2.0</version>
</dependency>
```

**Gradle:**

```gradle
implementation 'org.apache.fory:fory-core:1.2.0'
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
fory = "1.2.0"
```

### JavaScript/TypeScript

```bash
npm install @apache-fory/core
```

For the optional Node.js string fast path:

```bash
npm install @apache-fory/core @apache-fory/hps
```

### C\#

```bash
dotnet add package Apache.Fory --version 1.2.0
```

### Dart

```bash
dart pub add fory:^1.2.0
dart pub add dev:build_runner
```

### Swift

Add Fory to `Package.swift`:

```swift
dependencies: [
  .package(url: "https://github.com/apache/fory.git", exact: "1.2.0")
]
```

### Scala

```scala
libraryDependencies += "org.apache.fory" %% "fory-scala" % "1.2.0"
```

### Kotlin

```kotlin
implementation("org.apache.fory:fory-kotlin:1.2.0")
```

### C++

Use Bazel or CMake to build from source. See [C++ Guide](../cpp/index.md) for details.

## Create an Xlang Fory Instance

Xlang mode is the default for implementations that expose a mode switch. Swift, C#, JavaScript/TypeScript,
and Dart only expose the xlang wire format. The examples below keep compatible schema evolution on
the default path and show only options that change another setting.

### Java

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

Fory fory = Fory.builder()
    .withXlang(true)
    .withRefTracking(true)  // Optional: for circular references
    .build();
```

### Python

```python
import pyfory

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

let fory = Fory::builder().xlang(true).build();
```

### JavaScript/TypeScript

```javascript
import Fory, { Type } from "@apache-fory/core";

const fory = new Fory();
```

### C\#

```csharp
using Apache.Fory;

Fory fory = Fory.Builder().Build();
```

### Dart

```dart
import 'package:fory/fory.dart';

final fory = Fory();
```

### Swift

```swift
import Fory

let fory = Fory()
```

### Scala

```scala
import org.apache.fory.scala.ForyScala

val fory = ForyScala.builder()
  .withXlang(true)
  .build()
```

### Kotlin

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder()
    .withXlang(true)
    .build()
```

### C++

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

auto fory = Fory::builder().xlang(true).build();
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
fory.register_type(Person, name="example.Person")
```

**Go:**

```go
fory.RegisterStructByName(Person{}, "example.Person")
```

**Rust:**

```rust
use fory::{Fory, ForyStruct};

#[derive(ForyStruct)]
struct Person {
    name: String,
    age: i32,
}

let mut fory = Fory::builder().xlang(true).build();
fory
    .register_by_name::<Person>("example.Person")
    .expect("register Person");
```

**JavaScript/TypeScript:**

```javascript
const personType = Type.struct(
  { typeName: "example.Person" },
  {
    name: Type.string(),
    age: Type.int32(),
  },
);
const { serialize, deserialize } = fory.register(personType);
```

**C++:**

```cpp
fory.register_struct<Person>("example.Person");
// For enums, use register_enum:
// fory.register_enum<Color>("example.Color");
```

**C#:**

```csharp
fory.Register<Person>("example.Person");
```

**Dart:**

```dart
PersonForyModule.register(
  fory,
  Person,
  name: 'example.Person',
);
```

**Swift:**

```swift
try fory.register(Person.self, name: "example.Person")
```

**Scala:**

```scala
fory.register(classOf[Person], "example.Person")
```

**Kotlin:**

```kotlin
fory.register(Person::class.java, "example.Person")
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

**Rust:**

```rust
fory.register::<Person>(100)?;
```

**JavaScript/TypeScript:**

```javascript
const personType = Type.struct(
  { typeId: 100 },
  {
    name: Type.string(),
    age: Type.int32(),
  },
);
```

**C++:**

```cpp
fory.register_struct<Person>(100);
// For enums, use register_enum:
// fory.register_enum<Color>(101);
```

**C#:**

```csharp
fory.Register<Person>(100);
```

**Dart:**

```dart
PersonForyModule.register(fory, Person, id: 100);
```

**Swift:**

```swift
fory.register(Person.self, id: 100)
```

**Scala:**

```scala
fory.register(classOf[Person], 100)
```

**Kotlin:**

```kotlin
fory.register(Person::class.java, 100)
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
        Fory fory = Fory.builder().withXlang(true).build();
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
    age: pyfory.Int32

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, name="example.Person")

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
4. **Use type annotations**: In Python, use markers such as `pyfory.Int32` for precise type mapping
5. **Test cross-language**: Verify serialization works across all target languages

## Next Steps

- [Type Mapping](../../specification/xlang_type_mapping.md) - Cross-language type mapping reference
- [Serialization](serialization.md) - Detailed serialization examples
- [Troubleshooting](troubleshooting.md) - Common issues and solutions
