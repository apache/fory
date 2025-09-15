# Apache Fory™ Rust

[![Crates.io](https://img.shields.io/crates/v/fory.svg)](https://crates.io/crates/fory)
[![Documentation](https://docs.rs/fory/badge.svg)](https://docs.rs/fory)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

Apache Fory™ is a blazingly fast multi-language serialization framework powered by
codegen and zero-copy techniques. It provides high-performance
serialization and deserialization for Rust applications with cross-language
compatibility.

## 🚀 Key Features

- **High Performance**: Optimized for speed with zero-copy deserialization
- **Cross-Language**: Designed for multi-language environments and microservices
- **Two Serialization Modes**: Object serialization with highly-optimized protocol and row-based lazy on-demand serialization
- **Type Safety**: Compile-time type checking with derive macros
- **Schema Evolution**: Support for compatible mode with field additions/deletions
- **Zero Dependencies**: Minimal runtime dependencies for maximum performance

## 📦 Crates

This repository contains three main crates:

- **[`fory`](fory/)**: Main crate with high-level API and derive macros
- **[`fory-core`](fory-core/)**: Core serialization engine and low-level APIs
- **[`fory-derive`](fory-derive/)**: Procedural macros for code generation

## 🏃‍♂️ Quick Start

Add Apache Fory™ to your `Cargo.toml`:

```toml
[dependencies]
fory = "0.1"
fory-derive = "0.1"
chrono = "0.4"
```

### Object Serialization

For complex data structures with full object graph serialization:

```rust
use fory::{Fory, Error};
use fory_derive::Fory;
use std::collections::HashMap;

#[derive(Fory, Debug, PartialEq, Default)]
struct Person {
    name: String,
    age: i32,
    address: Address,
    hobbies: Vec<String>,
    metadata: HashMap<String, String>,
}

#[derive(Fory, Debug, PartialEq, Default)]
struct Address {
    street: String,
    city: String,
    country: String,
}

// Create a Fory instance and register types
let mut fory = Fory::default();
fory.register::<Address>(100);
fory.register::<Person>(200);

let person = Person {
    name: "John Doe".to_string(),
    age: 30,
    address: Address {
        street: "123 Main St".to_string(),
        city: "New York".to_string(),
        country: "USA".to_string(),
    },
    hobbies: vec!["reading".to_string(), "coding".to_string()],
    metadata: HashMap::from([
        ("department".to_string(), "engineering".to_string()),
        ("level".to_string(), "senior".to_string()),
    ]),
};

// Serialize the object
let serialized = fory.serialize(&person);

// Deserialize back to the original type
let deserialized: Person = fory.deserialize(&serialized)?;

assert_eq!(person, deserialized);
```

### Row-Based Serialization

For high-performance, zero-copy scenarios:

```rust
use fory::{to_row, from_row};
use fory_derive::ForyRow;
use std::collections::BTreeMap;

#[derive(ForyRow)]
struct UserProfile {
    id: i64,
    username: String,
    email: String,
    scores: Vec<i32>,
    preferences: BTreeMap<String, String>,
    is_active: bool,
}

let profile = UserProfile {
    id: 12345,
    username: "alice".to_string(),
    email: "alice@example.com".to_string(),
    scores: vec![95, 87, 92, 88],
    preferences: BTreeMap::from([
        ("theme".to_string(), "dark".to_string()),
        ("language".to_string(), "en".to_string()),
    ]),
    is_active: true,
};

// Serialize to row format
let row_data = to_row(&profile);

// Deserialize with zero-copy access
let row = from_row::<UserProfile>(&row_data);

// Access fields directly from the row data
assert_eq!(row.id(), 12345);
assert_eq!(row.username(), "alice");
assert_eq!(row.email(), "alice@example.com");
assert_eq!(row.is_active(), true);

// Access collections efficiently
let scores = row.scores();
assert_eq!(scores.size(), 4);
assert_eq!(scores.get(0), 95);
assert_eq!(scores.get(1), 87);

let prefs = row.preferences();
assert_eq!(prefs.keys().size(), 2);
assert_eq!(prefs.keys().get(0), "language");
assert_eq!(prefs.values().get(0), "en");
```

## 📚 Documentation

- **[API Documentation](https://docs.rs/fory)** - Complete API reference
- **[Performance Guide](docs/performance.md)** - Optimization tips and benchmarks

## 🎯 Use Cases

### Object Serialization

- **Complex data structures** with nested objects and references
- **Cross-language communication** in microservices architectures
- **General-purpose serialization** with full type safety
- **Schema evolution** with compatible mode

### Row-Based Serialization

- **High-throughput data processing** with zero-copy access
- **Analytics workloads** requiring fast field access
- **Memory-constrained environments** with minimal allocations
- **Real-time data streaming** applications

## 🔧 Supported Types

### Primitive Types

- `bool`, `i8`, `i16`, `i32`, `i64`, `f32`, `f64`
- `String`, `&str` (in row format)
- `Vec<u8>` for binary data

### Collections

- `Vec<T>` for arrays/lists
- `HashMap<K, V>` and `BTreeMap<K, V>` for maps
- `Option<T>` for nullable values

### Date and Time

- `chrono::NaiveDate` for dates (requires chrono dependency)
- `chrono::NaiveDateTime` for timestamps (requires chrono dependency)

### Custom Types

- Structs with `#[derive(Fory)]` or `#[derive(ForyRow)]`
- Enums with `#[derive(Fory)]`

## ⚡ Performance

Apache Fory™ is designed for maximum performance:

- **Zero-copy deserialization** in row mode
- **Buffer pre-allocation** to minimize allocations
- **Variable-length encoding** for compact representation
- **Little-endian byte order** for cross-platform compatibility
- **Optimized code generation** with derive macros

## 🌍 Cross-Language Compatibility

Apache Fory™ is designed to work across multiple programming languages, making it ideal for:

- **Microservices architectures** with polyglot services
- **Distributed systems** with heterogeneous components
- **Data pipelines** spanning multiple language ecosystems
- **API communication** between different technology stacks

## Benchmark

```bash
cargo bench --package fory-benchmarks
```

## 🛠️ Development Status

Apache Fory™ Rust implementation roadmap:

- [x] Static codegen based on rust macro
- [x] Row format serialization
- [x] Cross-language object graph serialization
- [x] Static codegen based on fory-derive macro processor
- [ ] Advanced schema evolution features
- [ ] Performance optimizations and benchmarks

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](../LICENSE) for details.

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](../CONTRIBUTING.md) for details.

## 📞 Support

- **Documentation**: [docs.rs/fory](https://docs.rs/fory)
- **Issues**: [GitHub Issues](https://github.com/apache/fory/issues)
- **Discussions**: [GitHub Discussions](https://github.com/apache/fory/discussions)

---

**Apache Fory™** - Blazingly fast serialization.
