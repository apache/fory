---
title: Overview
sidebar_position: 1
id: index
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

**Apache Fory™** is a blazingly-fast multi-language serialization framework for
idiomatic domain objects, schema IDL, and cross-language data exchange.

Fory is built for compact, high-throughput serialization across languages and
execution platforms. It works directly with application objects, supports shared schemas
when you need a stable contract, and preserves object graph features such as
shared references, circular references, and concrete types for polymorphic values.

## Quick Example

Cross-language serialization — serialize in Rust, deserialize in Python:

**Rust**

```rust
use fory::{Fory, ForyObject};

#[derive(ForyObject, Debug, PartialEq)]
struct User {
    name: String,
    age: i32,
}

fn main() {
    let mut fory = Fory::default().xlang(true);
    fory.register::<User>(1);

    let user = User { name: "Alice".to_string(), age: 30 };
    let bytes = fory.serialize(&user).unwrap();
    let decoded: User = fory.deserialize(&bytes).unwrap();
    println!("{:?}", decoded);  // User { name: "Alice", age: 30 }
}
```

**Python**

```python
import pyfory
from dataclasses import dataclass

@dataclass
class User:
    name: str
    age: pyfory.int32

fory = pyfory.Fory(xlang=True)
fory.register(User, type_id=1)

user = User(name="Alice", age=30)
data = fory.serialize(user)
decoded = fory.deserialize(data)
print(decoded)  # User(name='Alice', age=30)
```

## Key Features

### Efficient Cross-Language Encoding

The **[xlang serialization format](../specification/xlang_serialization_spec.md)**
exchanges compact binary payloads across supported languages:

- **Compact metadata**: Type metadata and field information are packed to keep payloads small.
- **Schema evolution**: Compatible mode supports forward and backward evolution for application schemas.
- **Object graph semantics**: Shared references, circular references, and concrete types for polymorphic values are preserved across languages.
- **Type mapping**: Language-specific values are mapped through the shared [type mapping](../specification/xlang_type_mapping.md).

### Domain Objects First

Fory serializes native domain objects directly instead of forcing applications
through wrapper types:

- Java classes, Scala/Kotlin types, and GraalVM native image workloads.
- Python dataclasses and Python-native object graphs.
- Go structs, Rust structs, C++ structs, C# models, Swift types, Dart models, and JavaScript/TypeScript values.
- Generated or annotated types when a shared contract is preferred.

### Reference-Aware Schema IDL

**[Fory IDL and the compiler](../compiler/index.md)** let teams define schemas
once and generate native domain objects for each target language:

- Model numbers, strings, lists, maps, arrays, enums, structs, and unions.
- Express shared and circular references directly in the schema.
- Generate idiomatic host-language code without introducing transport-specific wrapper types into user code.
- Use schema IDL when services need a stable contract across independently maintained Fory implementations.

### Row-Format Random Access

A cache-friendly **[row format](../specification/row_format_spec.md)** is
optimized for analytics and partial-read workloads:

- **Zero-copy random access**: Read fields, arrays, and nested values without rebuilding whole objects.
- **Partial operations**: Read only the values needed for a query or pipeline stage.
- **Apache Arrow integration**: Convert to columnar data for analytics pipelines.
- **Multi-language support**: Exchange Standard Row Format data across Java, Python, C++, and Rust.

### Optimized Fory Implementations

Fory keeps hot paths fast without making every Fory implementation use the same strategy:

- **Java JIT serializers**: Runtime code generation eliminates reflection overhead and inlines hot paths.
- **Generated and static serializers**: Other Fory implementations use generated or static serializers where appropriate.
- **Zero-copy paths**: Row format and out-of-band buffers avoid unnecessary copies for large values.
- **Metadata sharing**: Repeated type information is shared or packed to reduce serialization overhead.
