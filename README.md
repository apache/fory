<div align="center">
  <img width="65%" alt="Apache Fory logo" src="docs/images/logo/fory-horizontal.png"><br>
</div>

[![Build Status](https://img.shields.io/github/actions/workflow/status/apache/fory/ci.yml?branch=main&style=for-the-badge&label=GITHUB%20ACTIONS&logo=github)](https://github.com/apache/fory/actions/workflows/ci.yml)
[![Slack Channel](https://img.shields.io/badge/slack-join-3f0e40?logo=slack&style=for-the-badge)](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)
[![X](https://img.shields.io/badge/@ApacheFory-follow-blue?logo=x&style=for-the-badge)](https://x.com/ApacheFory)
[![Maven Version](https://img.shields.io/maven-central/v/org.apache.fory/fory-core?style=for-the-badge)](https://search.maven.org/#search|gav|1|g:"org.apache.fory"%20AND%20a:"fory-core")
[![Crates.io](https://img.shields.io/badge/crates.io-v1.2.0-blue?logo=rust&style=for-the-badge)](https://crates.io/crates/fory)
[![PyPI](https://img.shields.io/pypi/v/pyfory.svg?logo=PyPI&style=for-the-badge)](https://pypi.org/project/pyfory/)
[![npm](https://img.shields.io/npm/v/%40apache-fory%2Fcore?logo=npm&style=for-the-badge)](https://www.npmjs.com/package/@apache-fory/core)
[![NuGet](https://img.shields.io/nuget/v/Apache.Fory?logo=nuget&style=for-the-badge)](https://www.nuget.org/packages/Apache.Fory)
[![pub.dev](https://img.shields.io/pub/v/fory?logo=dart&style=for-the-badge)](https://pub.dev/packages/fory)

**Apache Fory™** is a blazingly fast multi-language serialization framework for
idiomatic domain objects, schema IDL, and cross-language data exchange.

<https://fory.apache.org>

## Why Fory

Fory is built for fast, compact serialization across languages and implementations. It
works with idiomatic objects in each language, supports shared schemas when you
need a contract, and preserves object features such as shared and circular
references.

- **Efficient Cross-Language Encoding**: Exchange payloads across supported
  languages with compact binary encoding, metadata packing, schema evolution,
  shared/circular references, and polymorphic runtime types.
- **Domain Objects First**: Serialize Java classes, Python dataclasses, Go
  structs, Rust/C++ structs, and generated or annotated model types directly.
  Preserve shared and circular references when object identity matters.
- **Reference-Aware Schema IDL**: Support shared and circular references
  directly in the schema, alongside numbers, strings, lists, maps, arrays,
  enums, structs, and unions. Define schemas once, then generate native domain
  objects for each language without forcing wrapper types into user code.
- **Row-Format Random Access**: Read fields, arrays, and nested values without
  rebuilding full objects, with zero-copy access and partial reads.
- **Optimized Implementations**: Java JIT serializers and generated/static serializers
  in other language implementations keep hot paths fast and payloads compact.
- **Language And Platform Support**: Java, Python, C++, Go, Rust,
  JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin, including GraalVM
  native image, Android, Dart VM/Flutter/web, and Node.js/browser JavaScript.

## Performance

Benchmarks show Fory delivering higher throughput and smaller serialized
payloads than common serialization frameworks on representative workloads. Java
has the broadest comparison set; the other charts show language-specific results
across supported languages.

**Java** [Benchmarks](docs/benchmarks/java)

In Java serialization benchmarks, Fory reaches up to **170x** the throughput of
JDK serialization on selected workloads.

<p align="center">
<img src="docs/benchmarks/java/java_repo_serialization_throughput.png" width="95%" alt="Java serialization throughput">
</p>

<p align="center">
<img src="docs/benchmarks/java/java_repo_deserialization_throughput.png" width="95%" alt="Java deserialization throughput">
</p>

<p align="center">
<img src="docs/benchmarks/java/throughput.png" width="95%" alt="Java xlang throughput">
</p>

**Python** [Benchmarks](benchmarks/python)

<p align="center">
<img src="docs/benchmarks/python/throughput.png" width="95%" alt="Python serialization throughput">
</p>

**Rust** [Benchmarks](benchmarks/rust)

<p align="center">
<img src="docs/benchmarks/rust/throughput.png" width="95%" alt="Rust serialization throughput">
</p>

<details>
<summary><strong>Benchmarks for <a href="benchmarks/cpp">C++</a>, <a href="benchmarks/go">Go</a>, <a href="docs/benchmarks/javascript">JavaScript/TypeScript</a>, <a href="docs/benchmarks/csharp">C#</a>, <a href="docs/benchmarks/swift">Swift</a>, and <a href="docs/benchmarks/dart">Dart</a></strong></summary>

**C++** [Benchmarks](benchmarks/cpp)

<p align="center">
<img src="docs/benchmarks/cpp/throughput.png" width="95%" alt="C++ serialization throughput">
</p>

**Go** [Benchmarks](benchmarks/go)

<p align="center">
<img src="docs/benchmarks/go/throughput.png" width="95%" alt="Go serialization throughput">
</p>

**JavaScript/TypeScript** [Benchmarks](docs/benchmarks/javascript)

<p align="center">
<img src="docs/benchmarks/javascript/throughput.png" width="95%" alt="JavaScript serialization throughput">
</p>

**C#** [Benchmarks](docs/benchmarks/csharp)

<p align="center">
<img src="docs/benchmarks/csharp/throughput.png" width="95%" alt="C# serialization throughput">
</p>

**Swift** [Benchmarks](docs/benchmarks/swift)

<p align="center">
<img src="docs/benchmarks/swift/throughput.png" width="95%" alt="Swift serialization throughput">
</p>

**Dart** [Benchmarks](docs/benchmarks/dart)

<p align="center">
<img src="docs/benchmarks/dart/throughput.png" width="95%" alt="Dart serialization throughput">
</p>

</details>

## Installation

Pick your language and run the package-manager command, or paste the
dependency block into your build file.

**Java**

Maven:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>1.2.0</version>
</dependency>
```

Gradle:

```gradle
implementation "org.apache.fory:fory-core:1.2.0"
```

On JDK25+, open `java.lang.invoke` to Fory. Use `ALL-UNNAMED` when Fory is on
the classpath:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

Use the Fory core module name when Fory is on the module path:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

**Scala**

sbt:

```scala
libraryDependencies += "org.apache.fory" %% "fory-scala" % "1.2.0"
```

**Kotlin**

Gradle:

```kotlin
implementation("org.apache.fory:fory-kotlin:1.2.0")
```

Maven:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-kotlin</artifactId>
  <version>1.2.0</version>
</dependency>
```

**Python**

```bash
pip install pyfory
```

For row-format support:

```bash
pip install "pyfory[format]"
```

**Rust**

`Cargo.toml`:

```toml
[dependencies]
fory = "1.2.0"
```

**C++**

CMake:

```cmake
include(FetchContent)
FetchContent_Declare(
  fory
  GIT_REPOSITORY https://github.com/apache/fory.git
  GIT_TAG v1.2.0
  SOURCE_SUBDIR cpp
)
FetchContent_MakeAvailable(fory)
target_link_libraries(my_app PRIVATE fory::serialization)
```

Bazel:

```bazel
# MODULE.bazel
bazel_dep(name = "fory", version = "1.2.0")
git_override(module_name = "fory", remote = "https://github.com/apache/fory.git", commit = "v1.2.0")

# BUILD
deps = ["@fory//cpp/fory/serialization:fory_serialization"]
```

When building C++ with MSVC, enable the conforming preprocessor option
`/Zc:preprocessor`; see the C++ installation guide for setup details.

See the [C++ installation guide](https://fory.apache.org/docs/guide/cpp/#installation)
for complete CMake, Bazel, and source-build details.

**Go**

```bash
go get github.com/apache/fory/go/fory
```

**JavaScript/TypeScript**

```bash
npm install @apache-fory/core
```

For the Node.js string fast path:

```bash
npm install @apache-fory/core @apache-fory/hps
```

**C#**

```bash
dotnet add package Apache.Fory --version 1.2.0
```

**Dart**

```bash
dart pub add fory:^1.2.0
dart pub add dev:build_runner
```

**Swift**

Add Fory to `Package.swift`:

```swift
dependencies: [
  .package(url: "https://github.com/apache/fory.git", exact: "1.2.0")
],
targets: [
  .target(
    name: "YourTarget",
    dependencies: [.product(name: "Fory", package: "fory")]
  )
]
```

See the [Swift guide](https://fory.apache.org/docs/guide/swift/) for generated
serializer setup.

**Development From Source**

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

Snapshots for Java, Scala, and Kotlin are available from
`https://repository.apache.org/snapshots/` with the matching `-SNAPSHOT` version.

## Choose Serialization Mode

| Mode        | Use it when                                                   | Start here                                               |
| ----------- | ------------------------------------------------------------- | -------------------------------------------------------- |
| Xlang mode  | Data crosses language boundaries                              | [Cross-language guide](docs/guide/xlang)                 |
| Native mode | Producer and consumer are in the same language                | Language guide                                           |
| Row format  | You need random field access or analytics-style partial reads | [Row format spec](docs/specification/row_format_spec.md) |

For Java, Scala, Kotlin, Python, C++, Go, and Rust, use native mode for
same-language traffic. It avoids xlang's cross-language type mapping and
metadata constraints, stays closer to each language's native type system, and
supports broader language-specific object graphs. Use it when both producer and
consumer are in the same language family and you want the native object model
rather than a portable cross-language schema.

For Java/JVM-only systems, native mode is the replacement path for JDK
serialization, Kryo, FST, Hessian, and Java-only Protocol Buffers payloads. For
Python-only systems, native mode is the replacement path for pickle and
cloudpickle.

Compatible mode is Fory's schema-evolution mode. It writes the metadata readers
and writers need to tolerate schema differences. It is the default for xlang
mode and native mode in implementations that expose the option.

Use compatible mode when services deploy independently or when fields may be
added or deleted over time. Set compatible mode to `false` only when every reader
and writer always uses the same schema and you want faster serialization and
smaller size. For xlang payloads, set compatible mode to `false` only after
verifying that every language uses the same schema, or when native types are
generated from Fory schema IDL.

For xlang, all peers must agree on type identity. Name-based registration is
easier to read in examples. Numeric IDs are smaller and faster, but they require
coordination across every reader and writer.

## Cross-Language Serialization

Xlang mode writes the cross-language Fory wire format. Bytes produced by one
language implementation can be read by another when every peer uses the same type identity,
compatible mode setting, and field schema.

**Java**

```java
import org.apache.fory.Fory;

public class Example {
  public static class Person {
    public String name;
    public int age;
  }

  public static void main(String[] args) {
    Fory fory = Fory.builder().withXlang(true).build();
    fory.register(Person.class, "example.Person");

    Person person = new Person();
    person.name = "Alice";
    person.age = 30;

    byte[] bytes = fory.serialize(person);
    Person decoded = (Person) fory.deserialize(bytes);
    System.out.println(decoded.name);
  }
}
```

**Python**

```python
from dataclasses import dataclass

import pyfory

@dataclass
class Person:
    name: str
    age: pyfory.Int32

fory = pyfory.Fory(xlang=True)
fory.register_type(Person, name="example.Person")

data = fory.serialize(Person("Alice", 30))
person = fory.deserialize(data)
print(person.name)
```

**Go**

```go
package main

import (
    "fmt"

    "github.com/apache/fory/go/fory"
)

type Person struct {
    Name string
    Age  int32
}

func main() {
    f := fory.New(fory.WithXlang(true))
    if err := f.RegisterStructByName(Person{}, "example.Person"); err != nil {
        panic(err)
    }

    data, _ := f.Serialize(&Person{Name: "Alice", Age: 30})
    var person Person
    if err := f.Deserialize(data, &person); err != nil {
        panic(err)
    }
    fmt.Println(person.Name)
}
```

**Rust**

```rust
use fory::{Error, Fory, ForyStruct};

#[derive(ForyStruct, Debug, PartialEq)]
struct Person {
    name: String,
    age: i32,
}

fn main() -> Result<(), Error> {
    let mut fory = Fory::builder().xlang(true).build();
    fory.register_by_name::<Person>("example.Person")?;

    let bytes = fory.serialize(&Person {
        name: "Alice".to_string(),
        age: 30,
    })?;
    let person: Person = fory.deserialize(&bytes)?;
    println!("{}", person.name);
    Ok(())
}
```

**C++**

```cpp
#include "fory/serialization/fory.h"
#include <cstdint>
#include <iostream>
#include <string>

using namespace fory::serialization;

struct Person {
  std::string name;
  int32_t age;
};
FORY_STRUCT(Person, name, age);

int main() {
  auto fory = Fory::builder().xlang(true).build();
  fory.register_struct<Person>("example.Person");

  auto bytes = fory.serialize(Person{"Alice", 30}).value();
  Person person = fory.deserialize<Person>(bytes).value();
  std::cout << person.name << std::endl;
}
```

**JavaScript/TypeScript**

```ts
import Fory, { Type } from "@apache-fory/core";

const personType = Type.struct(
  { typeName: "example.Person" },
  {
    name: Type.string(),
    age: Type.int32(),
  },
);

const fory = new Fory();
const { serialize, deserialize } = fory.register(personType);

const bytes = serialize({ name: "Alice", age: 30 });
const person = deserialize(bytes);
console.log(person.name);
```

**C#**

```csharp
using Apache.Fory;

[ForyStruct]
public sealed class Person
{
    public string Name { get; set; } = string.Empty;
    public int Age { get; set; }
}

Fory fory = Fory.Builder().Build();
fory.Register<Person>("example", "Person");

byte[] bytes = fory.Serialize(new Person { Name = "Alice", Age = 30 });
Person person = fory.Deserialize<Person>(bytes);
Console.WriteLine(person.Name);
```

C# always writes the xlang frame header, so there is no separate xlang builder
flag.

**Dart**

```dart
import 'package:fory/fory.dart';

part 'person.fory.dart';

@ForyStruct()
class Person {
  Person();

  String name = '';

  @ForyField(type: Int32Type())
  int age = 0;
}

void main() {
  final fory = Fory();
  PersonFory.register(
    fory,
    Person,
    name: 'example.Person',
  );

  final bytes = fory.serialize(Person()
    ..name = 'Alice'
    ..age = 30);
  final person = fory.deserialize<Person>(bytes);
  print(person.name);
}
```

Dart uses the xlang wire format directly. Generate the companion file before
running:

```bash
dart run build_runner build --delete-conflicting-outputs
```

**Swift**

```swift
import Fory

@ForyStruct
struct Person {
    var name: String = ""
    var age: Int32 = 0
}

let fory = Fory()
try fory.register(Person.self, name: "example.Person")

let bytes = try fory.serialize(Person(name: "Alice", age: 30))
let person: Person = try fory.deserialize(bytes)
print(person.name)
```

**Scala**

```scala
import org.apache.fory.scala.ForyScala

case class Person(name: String, age: Int)

val fory = ForyScala.builder().withXlang(true).build()
fory.register(classOf[Person], "example.Person")

val bytes = fory.serialize(Person("Alice", 30))
val person = fory.deserialize(bytes).asInstanceOf[Person]
println(person.name)
```

**Kotlin**

```kotlin
import org.apache.fory.kotlin.ForyKotlin

data class Person(val name: String, val age: Int)

fun main() {
    val fory = ForyKotlin.builder().withXlang(true).build()
    fory.register(Person::class.java, "example.Person")

    val bytes = fory.serialize(Person("Alice", 30))
    val person = fory.deserialize(bytes) as Person
    println(person.name)
}
```

For shared/circular references, polymorphism, numeric IDs versus names, and
type-mapping rules, see the [cross-language guide](docs/guide/xlang) and
[type mapping specification](docs/specification/xlang_type_mapping.md).

## Native Serialization

Use native mode when the writer and reader are in the same language. It is
optimized for each language's native type system and can cover language-specific
types, object graphs, and framework-replacement cases that xlang mode keeps out
of the portable wire format. The languages below expose an explicit
`xlang=false` or native-mode setting; implementations without that switch stay on their
documented default path.

Choose Java native mode for Java/JVM-only replacements of JDK serialization,
Kryo, FST, Hessian, or Java-only Protocol Buffers payloads. Choose Python native
mode when replacing pickle or cloudpickle for Python-only payloads.

Keep class/type registration enabled for untrusted input. See the language guides
for language-specific security and compatibility settings.

**Java**

```java
Fory fory = Fory.builder()
    .withXlang(false)
    .requireClassRegistration(true)
    .build();
// Register, serialize, and deserialize as in the xlang example above.
```

**Python**

```python
fory = pyfory.Fory(xlang=False, ref=True)
# Register, serialize, and deserialize as in the xlang example above.
```

**Go**

```go
f := fory.New(fory.WithXlang(false))
// Register, serialize, and deserialize as in the xlang example above.
```

**Rust**

```rust
let mut fory = Fory::builder().xlang(false).build();
// Register, serialize, and deserialize as in the xlang example above.
```

**C++**

```cpp
auto fory = Fory::builder().xlang(false).build();
// Register, serialize, and deserialize as in the xlang example above.
```

**Scala**

```scala
import org.apache.fory.scala.ForyScala

val fory = ForyScala.builder()
  .withXlang(false)
  .requireClassRegistration(true)
  .build()
// Register, serialize, and deserialize as in the xlang example above.
```

**Kotlin**

```kotlin
import org.apache.fory.kotlin.ForyKotlin

val fory = ForyKotlin.builder()
    .withXlang(false)
    .requireClassRegistration(true)
    .build()
// Register, serialize, and deserialize as in the xlang example above.
```

## Schema IDL

Fory IDL is Fory's schema language for shared data models. It supports
references, nullable fields, lists, maps, arrays, enums, messages, and unions,
and generates native data structures for Java, Python, C++, Go, Rust,
JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin. Use it when multiple
languages need one shared contract.

```protobuf
package tree;

message TreeNode {
    string id = 1;
    string name = 2;

    list<ref TreeNode> children = 3;
    ref(weak=true) TreeNode parent = 4; // back-pointer
}
```

See the [Fory IDL and compiler guide](https://fory.apache.org/docs/compiler).

## Row Format

Row format is for random access and partial reads. These examples encode an
object with an integer array field, then read one array element from the binary
row without rebuilding the object.

**Python**

```python
from dataclasses import dataclass
from typing import List

import pyfory

@dataclass
class User:
    id: pyfory.Int32
    name: str
    scores: List[pyfory.Int32]

encoder = pyfory.encoder(User)
binary = encoder.to_row(User(1, "Alice", [98, 100, 95])).to_bytes()

row = pyfory.RowData(encoder.schema, binary)
print(row.name)
print(row.scores[1])
```

**Java**

```java
public class User {
  public int id;
  public String name;
  public int[] scores;
}

RowEncoder<User> encoder = Encoders.bean(User.class);

User user = new User();
user.id = 1;
user.name = "Alice";
user.scores = new int[] {98, 100, 95};

BinaryRow row = encoder.toRow(user);

Schema schema = encoder.schema();
Schema.StringField nameField = schema.stringField("name");
Schema.ArrayField scoresField = schema.arrayField("scores");

String name = nameField.get(row);
ArrayData scores = scoresField.get(row);
int secondScore = scores.getInt32(1);
```

For Java imports, nested structs, arrays/maps, Arrow integration, and partial
deserialization, see the
[Java row-format guide](https://fory.apache.org/docs/guide/java/row_format), the
[Python row-format guide](docs/guide/python/row-format.md), and the
[row-format specification](docs/specification/row_format_spec.md).

## Documentation

**User Guides**

| Guide                 | Source                                                                       | Website                                                            |
| --------------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| Java                  | [docs/guide/java](docs/guide/java)                                           | [View](https://fory.apache.org/docs/guide/java/)                   |
| Python                | [docs/guide/python](docs/guide/python)                                       | [View](https://fory.apache.org/docs/guide/python/)                 |
| Rust                  | [docs/guide/rust](docs/guide/rust)                                           | [View](https://fory.apache.org/docs/guide/rust/)                   |
| C++                   | [docs/guide/cpp](docs/guide/cpp)                                             | [View](https://fory.apache.org/docs/guide/cpp/)                    |
| Go                    | [docs/guide/go](docs/guide/go)                                               | [View](https://fory.apache.org/docs/guide/go/)                     |
| JavaScript/TypeScript | [docs/guide/javascript](docs/guide/javascript)                               | [View](https://fory.apache.org/docs/guide/javascript/)             |
| C#                    | [docs/guide/csharp](docs/guide/csharp)                                       | [View](https://fory.apache.org/docs/guide/csharp/)                 |
| Swift                 | [docs/guide/swift](docs/guide/swift)                                         | [View](https://fory.apache.org/docs/guide/swift/)                  |
| Dart                  | [docs/guide/dart](docs/guide/dart)                                           | [View](https://fory.apache.org/docs/guide/dart/)                   |
| Scala                 | [docs/guide/scala](docs/guide/scala)                                         | [View](https://fory.apache.org/docs/guide/scala/)                  |
| Kotlin                | [docs/guide/kotlin](docs/guide/kotlin)                                       | [View](https://fory.apache.org/docs/guide/kotlin/)                 |
| Cross-language xlang  | [docs/guide/xlang](docs/guide/xlang)                                         | [View](https://fory.apache.org/docs/guide/xlang/)                  |
| Schema IDL/compiler   | [docs/compiler](docs/compiler)                                               | [View](https://fory.apache.org/docs/compiler/)                     |
| GraalVM native image  | [docs/guide/java/graalvm-support.md](docs/guide/java/graalvm-support.md)     | [View](https://fory.apache.org/docs/guide/java/graalvm_support)    |
| Android               | [docs/guide/kotlin/android-support.md](docs/guide/kotlin/android-support.md) | [View](https://fory.apache.org/docs/guide/kotlin/android_support/) |
| Development           | [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)                                   | [View](docs/DEVELOPMENT.md)                                        |

**Specifications**

| Specification          | Source                                                                        | Website                                                                     |
| ---------------------- | ----------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| Xlang serialization    | [xlang_serialization_spec.md](docs/specification/xlang_serialization_spec.md) | [View](https://fory.apache.org/docs/specification/xlang_serialization_spec) |
| Java serialization     | [java_serialization_spec.md](docs/specification/java_serialization_spec.md)   | [View](https://fory.apache.org/docs/specification/java_serialization_spec)  |
| Row format             | [row_format_spec.md](docs/specification/row_format_spec.md)                   | [View](https://fory.apache.org/docs/specification/row_format_spec)          |
| Cross-language mapping | [xlang_type_mapping.md](docs/specification/xlang_type_mapping.md)             | [View](https://fory.apache.org/docs/specification/xlang_type_mapping)       |

## Community

- [Slack workspace](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)
- [@ApacheFory on X](https://x.com/ApacheFory)
- [GitHub issues](https://github.com/apache/fory/issues)
- Apache Fory mailing lists for development discussion

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) before sending pull requests. Bug
reports, docs fixes, tests, benchmarks, and implementation improvements are welcome.

## License

Apache Fory™ is licensed under the [Apache License 2.0](LICENSE).
