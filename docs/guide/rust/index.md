---
title: Rust Serialization Guide
sidebar_position: 0
id: serialization_index
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

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

The Rust implementation provides versatile and high-performance serialization with automatic memory management and compile-time type safety. It supports both xlang mode for cross-language payloads and native mode for Rust-only payloads.

## Why Apache Fory™ Rust?

- **Fast binary encoding**: Zero-copy deserialization and optimized binary protocols
- **Xlang**: Seamlessly serialize/deserialize data across Java, Python, C++,
  Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, and Kotlin
- **Type-safe**: Compile-time type checking with derive macros
- **Circular references**: Automatic tracking of shared and circular references with `Rc`/`Arc` and weak pointers
- **Polymorphic**: Serialize trait objects with `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`
- **Schema evolution**: Compatible mode for independent schema changes
- **Two formats**: Object graph serialization and zero-copy row-based format

## Crates

| Crate                                                                       | Description                                               | Version                                       |
| --------------------------------------------------------------------------- | --------------------------------------------------------- | --------------------------------------------- |
| [`fory`](https://github.com/apache/fory/blob/main/rust/fory)                | User-facing API, runtime types, and derive macros         | [1.3.0](https://crates.io/crates/fory)        |
| [`fory-core`](https://github.com/apache/fory/blob/main/rust/fory-core/)     | Lower-level runtime crate for advanced integrations       | [1.3.0](https://crates.io/crates/fory-core)   |
| [`fory-derive`](https://github.com/apache/fory/blob/main/rust/fory-derive/) | Lower-level procedural macro crate for direct runtime use | [1.3.0](https://crates.io/crates/fory-derive) |

Most applications should depend on `fory` only. It re-exports the derive
macros and the public runtime types needed by generated code. Use `fory-core`
or `fory-derive` directly only when intentionally building on the lower-level
runtime crates.

## Quick Start

Add Apache Fory™ to your `Cargo.toml`:

```toml
[dependencies]
fory = "1.3.0"
```

### Basic Example

```rust
use fory::{Fory, Error, Reader};
use fory::ForyStruct;

#[derive(ForyStruct, Debug, PartialEq)]
struct User {
    name: String,
    age: i32,
    email: String,
}

fn main() -> Result<(), Error> {
    let mut fory = Fory::builder().xlang(true).build();
    fory.register::<User>(1)?;

    let user = User {
        name: "Alice".to_string(),
        age: 30,
        email: "alice@example.com".to_string(),
    };

    // Serialize
    let bytes = fory.serialize(&user)?;
    // Deserialize
    let decoded: User = fory.deserialize(&bytes)?;
    assert_eq!(user, decoded);

    // Serialize to specified buffer
    let mut buf: Vec<u8> = vec![];
    fory.serialize_to(&mut buf, &user)?;
    // Deserialize from specified buffer
    let mut reader = Reader::new(&buf);
    let decoded: User = fory.deserialize_from(&mut reader)?;
    assert_eq!(user, decoded);
    Ok(())
}
```

## Xlang Mode And Native Mode

Use xlang mode for cross-language payloads and schemas shared with other Fory implementations. Xlang mode is the default Rust wire mode, and Rust examples that use it set `.xlang(true)` explicitly so the mode choice is visible.

Use native mode for Rust-only traffic. Native mode is selected with `.xlang(false)` and keeps Rust object serialization in Rust-native form. It is optimized for Rust's type system and covers Rust-specific object features such as trait objects and shared-reference patterns that are not portable xlang payloads. Compatible mode is enabled by default. Set `.compatible(false)` only when every reader and writer uses the same Rust schema and you want faster serialization and smaller size.

See [Xlang Serialization](xlang-serialization.md) for Rust xlang registration and interoperability rules, and [Native Serialization](native-serialization.md) for Rust-only payloads.

## Thread Safety

Apache Fory™ Rust is fully thread-safe: `Fory` implements both `Send` and `Sync`, so one configured instance can be shared across threads for concurrent work. The internal read/write context pools are lazily initialized with thread-safe primitives, letting worker threads reuse buffers without coordination.

```rust
use fory::{Fory, Error};
use fory::ForyStruct;
use std::sync::Arc;
use std::thread;

#[derive(ForyStruct, Clone, Copy, Debug, PartialEq)]
struct Item {
    value: i32,
}

fn main() -> Result<(), Error> {
    let mut fory = Fory::builder().xlang(true).build();
    fory.register::<Item>(1000)?;

    let fory = Arc::new(fory);
    let handles: Vec<_> = (0..8)
        .map(|i| {
            let shared = Arc::clone(&fory);
            thread::spawn(move || {
                let item = Item { value: i };
                shared.serialize(&item)
            })
        })
        .collect();

    for handle in handles {
        let bytes = handle.join().unwrap()?;
        let item: Item = fory.deserialize(&bytes)?;
        assert!(item.value >= 0);
    }

    Ok(())
}
```

**Tip:** Perform registrations (such as `fory.register::<T>(id)`) before spawning threads so every worker sees the same metadata. Once configured, wrapping the instance in `Arc` is enough to fan out serialization and deserialization tasks safely.

## Architecture

The Rust implementation consists of three main crates:

```
fory/                   # High-level API
├── src/lib.rs         # Public API exports

fory-core/             # Core serialization engine
├── src/
│   ├── fory.rs       # Main serialization entry point
│   ├── buffer.rs     # Binary buffer management
│   ├── serializer/   # Type-specific serializers
│   ├── resolver/     # Type resolution and metadata
│   ├── meta/         # Meta string compression
│   ├── row/          # Row format implementation
│   └── types.rs      # Type definitions

fory-derive/           # Procedural macros
├── src/
│   ├── object/       # ForyStruct macro
│   └── fory_row.rs  # ForyRow macro
```

## Use Cases

### Object Serialization

- Complex data structures with nested objects and references
- Cross-language communication in microservices
- General-purpose serialization with full type safety
- Schema evolution with compatible mode
- Graph-like data structures with circular references

### Row-Based Serialization

- High-throughput data processing
- Analytics workloads requiring fast field access
- Memory-constrained environments
- Real-time data streaming applications
- Zero-copy scenarios

## Next Steps

- [Configuration](configuration.md) - Fory builder options and modes
- [Basic Serialization](basic-serialization.md) - Object graph serialization
- [Xlang Serialization](xlang-serialization.md) - xlang mode
- [Native Serialization](native-serialization.md) - Rust-only serialization
- [References](references.md) - Shared and circular references
- [Polymorphism](polymorphism.md) - Trait object serialization
- [Custom Serializers](custom-serializers.md) - Extend serialization behavior
- [Row Format](row-format.md) - Zero-copy row-based format
- [gRPC Support](grpc-support.md) - Fory payloads over tonic
