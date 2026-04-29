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

The Rust implementation provides versatile and high-performance serialization with automatic memory management and compile-time type safety.

## Why Apache Fory™ Rust?

- **🔥 Blazingly Fast**: Zero-copy deserialization and optimized binary protocols
- **🌍 Cross-Language**: Seamlessly serialize/deserialize data across Java, Python, C++, Go, JavaScript, and Rust
- **🎯 Type-Safe**: Compile-time type checking with derive macros
- **🔄 Circular References**: Automatic tracking of shared and circular references with `Rc`/`Arc` and weak pointers
- **🧬 Polymorphic**: Serialize trait objects with `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`
- **📦 Schema Evolution**: Compatible mode for independent schema changes
- **⚡ Two Formats**: Object graph serialization and zero-copy row-based format

## Crates

| Crate                                                                       | Description                       | Version                                                                                               |
| --------------------------------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------- |
| [`fory`](https://github.com/apache/fory/blob/main/rust/fory)                | High-level API with derive macros | [![crates.io](https://img.shields.io/crates/v/fory.svg)](https://crates.io/crates/fory)               |
| [`fory-core`](https://github.com/apache/fory/blob/main/rust/fory-core/)     | Core serialization engine         | [![crates.io](https://img.shields.io/crates/v/fory-core.svg)](https://crates.io/crates/fory-core)     |
| [`fory-derive`](https://github.com/apache/fory/blob/main/rust/fory-derive/) | Procedural macros                 | [![crates.io](https://img.shields.io/crates/v/fory-derive.svg)](https://crates.io/crates/fory-derive) |

## Quick Start

Add Apache Fory™ to your `Cargo.toml`:

```toml
[dependencies]
fory = "0.13"
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
    let mut fory = Fory::default();
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
    let mut fory = Fory::default();
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
- [References](references.md) - Shared and circular references
- [Polymorphism](polymorphism.md) - Trait object serialization
- [Cross-Language](cross-language.md) - XLANG mode
- [Row Format](row-format.md) - Zero-copy row-based format
