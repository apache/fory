# Apache Fury™ Rust

[![Crates.io](https://img.shields.io/crates/v/fury.svg)](https://crates.io/crates/fury)
[![Documentation](https://docs.rs/fury/badge.svg)](https://docs.rs/fury)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

**Apache Fury™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

The Rust implementation provides versatile and high-performance serialization with automatic memory management and compile-time type safety.

## 🚀 Why Apache Fury™ Rust?

- **🔥 Blazingly Fast**: Zero-copy deserialization and optimized binary protocols
- **🌍 Cross-Language**: Seamlessly serialize/deserialize data across Java, Python, C++, Go, JavaScript, and Rust
- **🎯 Type-Safe**: Compile-time type checking with derive macros
- **🔄 Circular References**: Automatic tracking of shared and circular references with `Rc`/`Arc` and weak pointers
- **🧬 Polymorphic**: Serialize trait objects with `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`
- **📦 Schema Evolution**: Compatible mode for independent schema changes
- **⚡ Two Modes**: Object graph serialization and zero-copy row-based format

## 📦 Crates

| Crate                         | Description                       | Version                                                                                               |
| ----------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------- |
| [`fury`](fury/)               | High-level API with derive macros | [![crates.io](https://img.shields.io/crates/v/fury.svg)](https://crates.io/crates/fury)               |
| [`fury-core`](fury-core/)     | Core serialization engine         | [![crates.io](https://img.shields.io/crates/v/fury-core.svg)](https://crates.io/crates/fury-core)     |
| [`fury-derive`](fury-derive/) | Procedural macros                 | [![crates.io](https://img.shields.io/crates/v/fury-derive.svg)](https://crates.io/crates/fury-derive) |

## 🏃 Quick Start

Add Apache Fury™ to your `Cargo.toml`:

```toml
[dependencies]
fury = "0.13"
fury-derive = "0.13"
```

### Basic Example

```rust
use fury::{Fury, Error};
use fury_derive::FuryObject;

#[derive(FuryObject, Debug, PartialEq)]
struct User {
    name: String,
    age: i32,
    email: String,
}

fn main() -> Result<(), Error> {
    let mut fury = Fury::default();
    fury.register::<User>(1);

    let user = User {
        name: "Alice".to_string(),
        age: 30,
        email: "alice@example.com".to_string(),
    };

    // Serialize
    let bytes = fury.serialize(&user);

    // Deserialize
    let decoded: User = fury.deserialize(&bytes)?;
    assert_eq!(user, decoded);

    Ok(())
}
```

## 📚 Core Features

### 1. Object Graph Serialization

Apache Fury™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `#[derive(FuryObject)]` macro generates efficient serialization code at compile time, eliminating runtime overhead.

**Key capabilities:**

- Nested struct serialization with arbitrary depth
- Collection types (Vec, HashMap, HashSet, BTreeMap)
- Optional fields with `Option<T>`
- Automatic handling of primitive types and strings
- Efficient binary encoding with variable-length integers

```rust
use fury::{Fury, Error};
use fury_derive::FuryObject;
use std::collections::HashMap;

#[derive(FuryObject, Debug, PartialEq, Default)]
struct Person {
    name: String,
    age: i32,
    address: Address,
    hobbies: Vec<String>,
    metadata: HashMap<String, String>,
}

#[derive(FuryObject, Debug, PartialEq, Default)]
struct Address {
    street: String,
    city: String,
    country: String,
}

let mut fury = Fury::default();
fury.register::<Address>(100);
fury.register::<Person>(200);

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
        ("role".to_string(), "developer".to_string()),
    ]),
};

let bytes = fury.serialize(&person);
let decoded: Person = fury.deserialize(&bytes)?;
assert_eq!(person, decoded);
```

### 2. Shared and Circular References

Apache Fury™ automatically tracks and preserves reference identity for shared objects using `Rc<T>` and `Arc<T>`. When the same object is referenced multiple times, Fury serializes it only once and uses reference IDs for subsequent occurrences. This ensures:

- **Space efficiency**: No data duplication in serialized output
- **Reference identity preservation**: Deserialized objects maintain the same sharing relationships
- **Circular reference support**: Use `RcWeak<T>` and `ArcWeak<T>` to break cycles

#### Shared References with Rc/Arc

```rust
use fury::Fury;
use std::rc::Rc;

let fury = Fury::default();

// Create a shared value
let shared = Rc::new(String::from("shared_value"));

// Reference it multiple times
let data = vec![shared.clone(), shared.clone(), shared.clone()];

// The shared value is serialized only once
let bytes = fury.serialize(&data);
let decoded: Vec<Rc<String>> = fury.deserialize(&bytes)?;

// Verify reference identity is preserved
assert_eq!(decoded.len(), 3);
assert_eq!(*decoded[0], "shared_value");

// All three Rc pointers point to the same object
assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
assert!(Rc::ptr_eq(&decoded[1], &decoded[2]));
```

For thread-safe shared references, use `Arc<T>`:

```rust
use std::sync::Arc;

let shared = Arc::new(String::from("shared_value"));
let data = vec![shared.clone(), shared.clone(), shared.clone()];

let bytes = fury.serialize(&data);
let decoded: Vec<Arc<String>> = fury.deserialize(&bytes)?;

// Reference identity is preserved with Arc too
assert!(Arc::ptr_eq(&decoded[0], &decoded[1]));
```

#### Circular References with Weak Pointers

To serialize circular references like parent-child relationships or doubly-linked structures, use `RcWeak<T>` or `ArcWeak<T>` to break the cycle. These weak pointers are serialized as references to their strong counterparts, preserving the graph structure without causing memory leaks or infinite recursion.

**How it works:**

- Weak pointers serialize as references to their target objects
- If the strong pointer has been dropped, weak serializes as `Null`
- Forward references (weak appearing before target) are resolved via callbacks
- All clones of a weak pointer share the same internal cell for automatic updates

```rust
use fury::{Fury, Error};
use fury_derive::FuryObject;
use fury_core::serializer::weak::RcWeak;
use std::rc::Rc;
use std::cell::RefCell;

#[derive(FuryObject, Debug)]
struct Node {
    value: i32,
    parent: RcWeak<RefCell<Node>>,
    children: Vec<Rc<RefCell<Node>>>,
}

let mut fury = Fury::default();
fury.register::<Node>(2000);

// Build a parent-child tree
let parent = Rc::new(RefCell::new(Node {
    value: 1,
    parent: RcWeak::new(),
    children: vec![],
}));

let child1 = Rc::new(RefCell::new(Node {
    value: 2,
    parent: RcWeak::from(&parent),
    children: vec![],
}));

let child2 = Rc::new(RefCell::new(Node {
    value: 3,
    parent: RcWeak::from(&parent),
    children: vec![],
}));

parent.borrow_mut().children.push(child1.clone());
parent.borrow_mut().children.push(child2.clone());

// Serialize and deserialize the circular structure
let bytes = fury.serialize(&parent);
let decoded: Rc<RefCell<Node>> = fury.deserialize(&bytes)?;

// Verify the circular relationship
assert_eq!(decoded.borrow().children.len(), 2);
for child in &decoded.borrow().children {
    let upgraded_parent = child.borrow().parent.upgrade().unwrap();
    assert!(Rc::ptr_eq(&decoded, &upgraded_parent));
}
```

**Thread-Safe Circular Graphs with Arc:**

```rust
use fury::{Fury, Error};
use fury_derive::FuryObject;
use fury_core::serializer::weak::ArcWeak;
use std::sync::{Arc, Mutex};

#[derive(FuryObject)]
struct Node {
    val: i32,
    parent: ArcWeak<Mutex<Node>>,
    children: Vec<Arc<Mutex<Node>>>,
}

let mut fury = Fury::default();
fury.register::<Node>(6000);

let parent = Arc::new(Mutex::new(Node {
    val: 10,
    parent: ArcWeak::new(),
    children: vec![],
}));

let child1 = Arc::new(Mutex::new(Node {
    val: 20,
    parent: ArcWeak::from(&parent),
    children: vec![],
}));

let child2 = Arc::new(Mutex::new(Node {
    val: 30,
    parent: ArcWeak::from(&parent),
    children: vec![],
}));

parent.lock().unwrap().children.push(child1.clone());
parent.lock().unwrap().children.push(child2.clone());

let bytes = fury.serialize(&parent);
let decoded: Arc<Mutex<Node>> = fury.deserialize(&bytes)?;

assert_eq!(decoded.lock().unwrap().children.len(), 2);
for child in &decoded.lock().unwrap().children {
    let upgraded_parent = child.lock().unwrap().parent.upgrade().unwrap();
    assert!(Arc::ptr_eq(&decoded, &upgraded_parent));
}
```

### 3. Trait Object Serialization

Apache Fury™ supports polymorphic serialization through trait objects, enabling dynamic dispatch and type flexibility. This is essential for plugin systems, heterogeneous collections, and extensible architectures.

**Supported trait object types:**

- `Box<dyn Trait>` - Owned trait objects
- `Rc<dyn Trait>` - Reference-counted trait objects
- `Arc<dyn Trait>` - Thread-safe reference-counted trait objects
- `Vec<Box<dyn Trait>>`, `HashMap<K, Box<dyn Trait>>` - Collections of trait objects

#### Basic Trait Object Serialization

```rust
use fury_core::{Fury, register_trait_type};
use fury_core::serializer::Serializer;
use fury_derive::FuryObject;
use fury_core::types::Mode;

trait Animal: Serializer {
    fn speak(&self) -> String;
    fn name(&self) -> &str;
}

#[derive(FuryObject)]
struct Dog { name: String, breed: String }

impl Animal for Dog {
    fn speak(&self) -> String { "Woof!".to_string() }
    fn name(&self) -> &str { &self.name }
}

#[derive(FuryObject)]
struct Cat { name: String, color: String }

impl Animal for Cat {
    fn speak(&self) -> String { "Meow!".to_string() }
    fn name(&self) -> &str { &self.name }
}

// Register trait implementations
register_trait_type!(Animal, Dog, Cat);

#[derive(FuryObject)]
struct Zoo {
    star_animal: Box<dyn Animal>,
}

let mut fury = Fury::default().mode(Mode::Compatible);
fury.register::<Dog>(100);
fury.register::<Cat>(101);
fury.register::<Zoo>(102);

let zoo = Zoo {
    star_animal: Box::new(Dog {
        name: "Buddy".to_string(),
        breed: "Labrador".to_string(),
    }),
};

let bytes = fury.serialize(&zoo);
let decoded: Zoo = fury.deserialize(&bytes)?;

assert_eq!(decoded.star_animal.name(), "Buddy");
assert_eq!(decoded.star_animal.speak(), "Woof!");
```

#### Serializing `dyn Any` Trait Objects

Apache Fury™ supports serializing `Rc<dyn Any>` and `Arc<dyn Any>` for runtime type dispatch. This is useful when you need maximum flexibility and don't want to define a custom trait.

**Key points:**

- Works with any type that implements `Serializer`
- Requires downcasting after deserialization to access the concrete type
- Type information is preserved during serialization
- Useful for plugin systems and dynamic type handling

```rust
use std::rc::Rc;
use std::any::Any;

let dog_rc: Rc<dyn Animal> = Rc::new(Dog {
    name: "Rex".to_string(),
    breed: "Golden".to_string()
});

// Convert to Rc<dyn Any> for serialization
let dog_any: Rc<dyn Any> = dog_rc.clone();

// Serialize the Any wrapper
let bytes = fury.serialize(&dog_any);
let decoded: Rc<dyn Any> = fury.deserialize(&bytes)?;

// Downcast back to the concrete type
let unwrapped = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(unwrapped.name, "Rex");
```

For thread-safe scenarios, use `Arc<dyn Any>`:

```rust
use std::sync::Arc;
use std::any::Any;

let dog_arc: Arc<dyn Animal> = Arc::new(Dog {
    name: "Buddy".to_string(),
    breed: "Labrador".to_string()
});

// Convert to Arc<dyn Any>
let dog_any: Arc<dyn Any> = dog_arc.clone();

let bytes = fury.serialize(&dog_any);
let decoded: Arc<dyn Any> = fury.deserialize(&bytes)?;

// Downcast to concrete type
let unwrapped = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(unwrapped.name, "Buddy");
```

#### Rc/Arc-Based Trait Objects in Structs

For fields with `Rc<dyn Trait>` or `Arc<dyn Trait>`, Fury automatically handles the conversion:

```rust
use std::sync::Arc;
use std::rc::Rc;
use std::collections::HashMap;

#[derive(FuryObject)]
struct AnimalShelter {
    animals_rc: Vec<Rc<dyn Animal>>,
    animals_arc: Vec<Arc<dyn Animal>>,
    registry: HashMap<String, Arc<dyn Animal>>,
}

let mut fury = Fury::default().mode(Mode::Compatible);
fury.register::<Dog>(100);
fury.register::<Cat>(101);
fury.register::<AnimalShelter>(102);

let shelter = AnimalShelter {
    animals_rc: vec![
        Rc::new(Dog { name: "Rex".to_string(), breed: "Golden".to_string() }),
        Rc::new(Cat { name: "Mittens".to_string(), color: "Gray".to_string() }),
    ],
    animals_arc: vec![
        Arc::new(Dog { name: "Buddy".to_string(), breed: "Labrador".to_string() }),
    ],
    registry: HashMap::from([
        ("pet1".to_string(), Arc::new(Dog {
            name: "Max".to_string(),
            breed: "Shepherd".to_string()
        }) as Arc<dyn Animal>),
    ]),
};

let bytes = fury.serialize(&shelter);
let decoded: AnimalShelter = fury.deserialize(&bytes)?;

assert_eq!(decoded.animals_rc[0].name(), "Rex");
assert_eq!(decoded.animals_arc[0].speak(), "Woof!");
```

#### Standalone Trait Object Serialization

Due to Rust's orphan rule, `Rc<dyn Trait>` and `Arc<dyn Trait>` cannot implement `Serializer` directly. For standalone serialization (not inside struct fields), the `register_trait_type!` macro generates wrapper types.

**Note:** If you don't want to use wrapper types, you can serialize as `Rc<dyn Any>` or `Arc<dyn Any>` instead (see the `dyn Any` section above).

The `register_trait_type!` macro generates `AnimalRc` and `AnimalArc` wrapper types:

```rust
// For Rc<dyn Trait>
let dog_rc: Rc<dyn Animal> = Rc::new(Dog {
    name: "Rex".to_string(),
    breed: "Golden".to_string()
});
let wrapper = AnimalRc::from(dog_rc);

let bytes = fury.serialize(&wrapper);
let decoded: AnimalRc = fury.deserialize(&bytes)?;

// Unwrap back to Rc<dyn Animal>
let unwrapped: Rc<dyn Animal> = decoded.unwrap();
assert_eq!(unwrapped.name(), "Rex");

// For Arc<dyn Trait>
let dog_arc: Arc<dyn Animal> = Arc::new(Dog {
    name: "Buddy".to_string(),
    breed: "Labrador".to_string()
});
let wrapper = AnimalArc::from(dog_arc);

let bytes = fury.serialize(&wrapper);
let decoded: AnimalArc = fury.deserialize(&bytes)?;

let unwrapped: Arc<dyn Animal> = decoded.unwrap();
assert_eq!(unwrapped.name(), "Buddy");
```

### 4. Schema Evolution

Apache Fury™ supports schema evolution in **Compatible mode**, allowing serialization and deserialization peers to have different type definitions. This enables independent evolution of services in distributed systems without breaking compatibility.

**Features:**

- Add new fields with default values
- Remove obsolete fields (skipped during deserialization)
- Change field nullability (`T` ↔ `Option<T>`)
- Reorder fields (matched by name, not position)
- Type-safe fallback to default values for missing fields

**Compatibility rules:**

- Field names must match (case-sensitive)
- Type changes are not supported (except nullable/non-nullable)
- Nested struct types must be registered on both sides

```rust
use fury::Fury;
use fury_core::types::Mode;
use fury_derive::FuryObject;
use std::collections::HashMap;

#[derive(FuryObject, Debug)]
struct PersonV1 {
    name: String,
    age: i32,
    address: String,
}

#[derive(FuryObject, Debug)]
struct PersonV2 {
    name: String,
    age: i32,
    // address removed
    // phone added
    phone: Option<String>,
    metadata: HashMap<String, String>,
}

let mut fury1 = Fury::default().mode(Mode::Compatible);
fury1.register::<PersonV1>(1);

let mut fury2 = Fury::default().mode(Mode::Compatible);
fury2.register::<PersonV2>(1);

let person_v1 = PersonV1 {
    name: "Alice".to_string(),
    age: 30,
    address: "123 Main St".to_string(),
};

// Serialize with V1
let bytes = fury1.serialize(&person_v1);

// Deserialize with V2 - missing fields get default values
let person_v2: PersonV2 = fury2.deserialize(&bytes)?;
assert_eq!(person_v2.name, "Alice");
assert_eq!(person_v2.age, 30);
assert_eq!(person_v2.phone, None);
```

### 5. Enum Support

Apache Fury™ supports enums without data payloads (C-style enums). Each variant is assigned an ordinal value (0, 1, 2, ...) during serialization.

**Features:**

- Efficient varint encoding for ordinals
- Schema evolution support in Compatible mode
- Type-safe variant matching
- Default variant support with `#[default]`

```rust
use fury_derive::FuryObject;

#[derive(FuryObject, Debug, PartialEq, Default)]
enum Status {
    #[default]
    Pending,
    Active,
    Inactive,
    Deleted,
}

let mut fury = Fury::default();
fury.register::<Status>(1);

let status = Status::Active;
let bytes = fury.serialize(&status);
let decoded: Status = fury.deserialize(&bytes)?;
assert_eq!(status, decoded);
```

### 6. Custom Serializers

For types that don't support `#[derive(FuryObject)]`, implement the `Serializer` trait manually. This is useful for:

- External types from other crates
- Types with special serialization requirements
- Legacy data format compatibility
- Performance-critical custom encoding

```rust
use fury_core::fury::{Fury, read_data, write_data};
use fury_core::resolver::context::{ReadContext, WriteContext};
use fury_core::serializer::{Serializer, FuryDefault};
use fury_core::error::Error;
use std::any::Any;

#[derive(Debug, PartialEq, Default)]
struct CustomType {
    value: i32,
}

impl Serializer for CustomType {
    fn fury_write_data(&self, context: &mut WriteContext, is_field: bool) {
        write_data(&self.value, context, is_field);
    }

    fn fury_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        Ok(Self {
            value: read_data(context, is_field)?,
        })
    }

    fn fury_type_id_dyn(&self, fury: &Fury) -> u32 {
        Self::fury_get_type_id(fury)
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

impl FuryDefault for CustomType {
    fn fury_default() -> Self {
        Self::default()
    }
}

let mut fury = Fury::default();
fury.register_serializer::<CustomType>(100);

let custom = CustomType { value: 42 };
let bytes = fury.serialize(&custom);
let decoded: CustomType = fury.deserialize(&bytes)?;
assert_eq!(custom, decoded);
```

### 7. Row-Based Serialization

Apache Fury™ provides a high-performance **row format** for zero-copy deserialization. Unlike traditional object serialization that reconstructs entire objects in memory, row format enables **random access** to fields directly from binary data without full deserialization.

**Key benefits:**

- **Zero-copy access**: Read fields without allocating or copying data
- **Partial deserialization**: Access only the fields you need
- **Memory-mapped files**: Work with data larger than RAM
- **Cache-friendly**: Sequential memory layout for better CPU cache utilization
- **Lazy evaluation**: Defer expensive operations until field access

**When to use row format:**

- Analytics workloads with selective field access
- Large datasets where only a subset of fields is needed
- Memory-constrained environments
- High-throughput data pipelines
- Reading from memory-mapped files or shared memory

**How it works:**

- Fields are encoded in a binary row with fixed offsets for primitives
- Variable-length data (strings, collections) stored with offset pointers
- Null bitmap tracks which fields are present
- Nested structures supported through recursive row encoding

```rust
use fury::{to_row, from_row};
use fury_derive::FuryRow;
use std::collections::BTreeMap;

#[derive(FuryRow)]
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

// Zero-copy deserialization - no object allocation!
let row = from_row::<UserProfile>(&row_data);

// Access fields directly from binary data
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

**Performance comparison:**

| Operation            | Object Format                 | Row Format                      |
| -------------------- | ----------------------------- | ------------------------------- |
| Full deserialization | Allocates all objects         | Zero allocation                 |
| Single field access  | Full deserialization required | Direct offset read              |
| Memory usage         | Full object graph in memory   | Only accessed fields in memory  |
| Suitable for         | Small objects, full access    | Large objects, selective access |

## 🔧 Supported Types

### Primitive Types

| Rust Type                 | Description     |
| ------------------------- | --------------- |
| `bool`                    | Boolean         |
| `i8`, `i16`, `i32`, `i64` | Signed integers |
| `f32`, `f64`              | Floating point  |
| `String`                  | UTF-8 string    |

### Collections

| Rust Type        | Description    |
| ---------------- | -------------- |
| `Vec<T>`         | Dynamic array  |
| `HashMap<K, V>`  | Hash map       |
| `BTreeMap<K, V>` | Ordered map    |
| `HashSet<T>`     | Hash set       |
| `Option<T>`      | Optional value |

### Smart Pointers

| Rust Type    | Description                                          |
| ------------ | ---------------------------------------------------- |
| `Box<T>`     | Heap allocation                                      |
| `Rc<T>`      | Reference counting (shared refs tracked)             |
| `Arc<T>`     | Thread-safe reference counting (shared refs tracked) |
| `RcWeak<T>`  | Weak reference to `Rc<T>` (breaks circular refs)     |
| `ArcWeak<T>` | Weak reference to `Arc<T>` (breaks circular refs)    |
| `RefCell<T>` | Interior mutability (runtime borrow checking)        |
| `Mutex<T>`   | Thread-safe interior mutability                      |

### Date and Time

| Rust Type               | Description                |
| ----------------------- | -------------------------- |
| `chrono::NaiveDate`     | Date without timezone      |
| `chrono::NaiveDateTime` | Timestamp without timezone |

### Custom Types

| Macro                   | Description                |
| ----------------------- | -------------------------- |
| `#[derive(FuryObject)]` | Object graph serialization |
| `#[derive(FuryRow)]`    | Row-based serialization    |

## 🌍 Cross-Language Serialization

Apache Fury™ supports seamless data exchange across multiple languages:

```rust
use fury::Fury;
use fury_core::types::Mode;

// Enable cross-language mode
let mut fury = Fury::default()
    .mode(Mode::Compatible)
    .xlang(true);

// Register types with consistent IDs across languages
fury.register::<MyStruct>(100);

// Or use namespace-based registration
fury.register_by_namespace::<MyStruct>("com.example", "MyStruct");
```

See [xlang_type_mapping.md](../docs/guide/xlang_type_mapping.md) for type mapping across languages.

## ⚡ Performance

Apache Fury™ Rust is designed for maximum performance:

- **Zero-Copy Deserialization**: Row format enables direct memory access without copying
- **Buffer Pre-allocation**: Minimizes memory allocations during serialization
- **Compact Encoding**: Variable-length encoding for space efficiency
- **Little-Endian**: Optimized for modern CPU architectures
- **Reference Deduplication**: Shared objects serialized only once

Run benchmarks:

```bash
cd rust
cargo bench --package fury-benchmarks
```

## 📖 Documentation

- **[API Documentation](https://docs.rs/fury)** - Complete API reference
- **[Protocol Specification](../docs/specification/xlang_serialization_spec.md)** - Serialization protocol details
- **[Row Format Specification](../docs/specification/row_format_spec.md)** - Row format details
- **[Type Mapping](../docs/guide/xlang_type_mapping.md)** - Cross-language type mappings

## 🎯 Use Cases

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

## 🏗️ Architecture

The Rust implementation consists of three main crates:

```
fury/                   # High-level API
├── src/lib.rs         # Public API exports

fury-core/             # Core serialization engine
├── src/
│   ├── fury.rs       # Main serialization entry point
│   ├── buffer.rs     # Binary buffer management
│   ├── serializer/   # Type-specific serializers
│   ├── resolver/     # Type resolution and metadata
│   ├── meta/         # Meta string compression
│   ├── row/          # Row format implementation
│   └── types.rs      # Type definitions

fury-derive/           # Procedural macros
├── src/
│   ├── object/       # FuryObject macro
│   └── fury_row.rs  # FuryRow macro
```

## 🔄 Serialization Modes

Apache Fury™ supports two serialization modes:

### SchemaConsistent Mode (Default)

Type declarations must match exactly between peers:

```rust
let fury = Fury::default(); // SchemaConsistent by default
```

### Compatible Mode

Allows independent schema evolution:

```rust
use fury_core::types::Mode;

let fury = Fury::default().mode(Mode::Compatible);
```

## 🛠️ Development

### Building

```bash
cd rust
cargo build
```

### Testing

```bash
# Run all tests
cargo test --features tests

# Run specific test
cargo test -p fury-tests --test test_complex_struct
```

### Code Quality

```bash
# Format code
cargo fmt

# Check formatting
cargo fmt --check

# Run linter
cargo clippy --all-targets --all-features -- -D warnings
```

## 🗺️ Roadmap

- [x] Static codegen based on rust macro
- [x] Row format serialization
- [x] Cross-language object graph serialization
- [x] Shared and circular reference tracking
- [x] Weak pointer support
- [x] Trait object serialization with polymorphism
- [x] Schema evolution in compatible mode
- [x] SIMD optimizations for string encoding
- [ ] Performance optimizations
- [ ] More comprehensive benchmarks

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](../LICENSE) for details.

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](../CONTRIBUTING.md) for details.

## 📞 Support

- **Documentation**: [docs.rs/fury](https://docs.rs/fury)
- **Issues**: [GitHub Issues](https://github.com/apache/fury/issues)
- **Discussions**: [GitHub Discussions](https://github.com/apache/fury/discussions)
- **Slack**: [Apache Fury Slack](https://join.slack.com/t/fury-project/shared_invite/zt-1u8soj4qc-ieYEu7ciHOqA2mo47llS8A)

---

**Apache Fury™** - Blazingly fast multi-language serialization framework.
