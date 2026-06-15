# Apache Fory™ Rust

[![Crates.io](https://img.shields.io/crates/v/fory.svg)](https://crates.io/crates/fory)
[![Documentation](https://docs.rs/fory/badge.svg)](https://docs.rs/fory)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

The Rust implementation provides versatile and high-performance serialization with automatic memory management and compile-time type safety. It defaults to xlang mode for cross-language payloads; use native mode with `.xlang(false)` for Rust-only traffic when you need Rust-specific object features such as trait objects and shared-reference patterns.

## Why Apache Fory™ Rust?

- **Blazingly Fast**: Zero-copy deserialization and optimized binary protocols
- **Cross-Language**: Seamlessly serialize/deserialize data across Java, Python, C++, Go, JavaScript, and Rust
- **Type-Safe**: Compile-time type checking with derive macros
- **Circular References**: Automatic tracking of shared and circular references with `Rc`/`Arc` and weak pointers
- **Polymorphic**: Serialize trait objects with `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`
- **Schema Evolution**: Compatible mode for independent schema changes
- **Reduced-Precision Types**: `Float16` and `BFloat16` scalars with `Vec<Float16>` / `Vec<BFloat16>` arrays
- **Two Formats**: Object graph serialization and zero-copy row-based format

## Crates

| Crate                                                                       | Description                                               | Version                                       |
| --------------------------------------------------------------------------- | --------------------------------------------------------- | --------------------------------------------- |
| [`fory`](https://github.com/apache/fory/blob/main/rust/fory)                | User-facing API, runtime types, and derive macros         | [1.1.0](https://crates.io/crates/fory)        |
| [`fory-core`](https://github.com/apache/fory/blob/main/rust/fory-core/)     | Lower-level runtime crate for advanced integrations       | [1.1.0](https://crates.io/crates/fory-core)   |
| [`fory-derive`](https://github.com/apache/fory/blob/main/rust/fory-derive/) | Lower-level procedural macro crate for direct runtime use | [1.1.0](https://crates.io/crates/fory-derive) |

Most applications should depend on `fory` only. It re-exports the derive
macros and the public runtime types needed by generated code. Use `fory-core`
or `fory-derive` directly only when intentionally building on the lower-level
runtime crates.

## Quick Start

Add Apache Fory™ to your `Cargo.toml`:

```toml
[dependencies]
fory = "1.1.0"
```

### Basic Example

```rust
use fory::{Fory, Error, Reader};
use fory::{ForyEnum, ForyStruct, ForyUnion};

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

## Core Features

### 1. Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `#[derive(ForyStruct)]` macro generates efficient serialization code at compile time, eliminating runtime overhead.

**Key capabilities:**

- Nested struct serialization with arbitrary depth
- Collection types (Vec, HashMap, HashSet, BTreeMap)
- Optional fields with `Option<T>`
- Automatic handling of primitive types and strings
- Efficient binary encoding with variable-length integers

```rust
use fory::{Fory, Error};
use fory::{ForyEnum, ForyStruct, ForyUnion};
use std::collections::HashMap;

#[derive(ForyStruct, Debug, PartialEq)]
struct Person {
    name: String,
    age: i32,
    address: Address,
    hobbies: Vec<String>,
    metadata: HashMap<String, String>,
}

#[derive(ForyStruct, Debug, PartialEq)]
struct Address {
    street: String,
    city: String,
    country: String,
}

let mut fory = Fory::builder().xlang(true).build();
fory.register_by_name::<Address>("example.Address").unwrap();
fory.register_by_name::<Person>("example.Person").unwrap();

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

let bytes = fory.serialize(&person).unwrap();
let decoded: Person = fory.deserialize(&bytes)?;
assert_eq!(person, decoded);
```

### 2. Native-Mode Shared and Circular References

Apache Fory™ automatically tracks and preserves reference identity for shared objects using `Rc<T>` and `Arc<T>`. When the same object is referenced multiple times, Fory serializes it only once and uses reference IDs for subsequent occurrences. This ensures:

- **Space efficiency**: No data duplication in serialized output
- **Reference identity preservation**: Deserialized objects maintain the same sharing relationships
- **Circular reference support**: Use `RcWeak<T>` and `ArcWeak<T>` to break cycles

The examples in this section use native mode because `Rc`, `Arc`, and weak-pointer identity are
Rust object-graph features. Native mode stays on Rust's native type system instead of limiting the
payload to portable xlang mappings.

#### Shared References with Rc/Arc

```rust
use fory::Fory;
use std::rc::Rc;

let fory = Fory::builder().xlang(false).build();

// Create a shared value
let shared = Rc::new(String::from("shared_value"));

// Reference it multiple times
let data = vec![shared.clone(), shared.clone(), shared.clone()];

// The shared value is serialized only once
let bytes = fory.serialize(&data)?;
let decoded: Vec<Rc<String>> = fory.deserialize(&bytes)?;

// Verify reference identity is preserved
assert_eq!(decoded.len(), 3);
assert_eq!(*decoded[0], "shared_value");

// All three Rc pointers point to the same object
assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
assert!(Rc::ptr_eq(&decoded[1], &decoded[2]));
```

For thread-safe shared references, use `Arc<T>`.

#### Circular References with Weak Pointers

To serialize circular references like parent-child relationships or doubly-linked structures, use `RcWeak<T>` or `ArcWeak<T>` to break the cycle. These weak pointers are serialized as references to their strong counterparts, preserving the graph structure without causing memory leaks or infinite recursion.

**How it works:**

- Weak pointers serialize as references to their target objects
- If the strong pointer has been dropped, weak serializes as `Null`
- Forward references (weak appearing before target) are resolved via callbacks
- All clones of a weak pointer share the same internal cell for automatic updates

```rust
use fory::{Fory, Error};
use fory::{ForyEnum, ForyStruct, ForyUnion};
use fory::RcWeak;
use std::rc::Rc;
use std::cell::RefCell;

#[derive(ForyStruct, Debug)]
struct Node {
    value: i32,
    parent: RcWeak<RefCell<Node>>,
    children: Vec<Rc<RefCell<Node>>>,
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<Node>(2000)?;

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
let bytes = fory.serialize(&parent)?;
let decoded: Rc<RefCell<Node>> = fory.deserialize(&bytes)?;

// Verify the circular relationship
assert_eq!(decoded.borrow().children.len(), 2);
for child in &decoded.borrow().children {
    let upgraded_parent = child.borrow().parent.upgrade().unwrap();
    assert!(Rc::ptr_eq(&decoded, &upgraded_parent));
}
```

### 3. Native-Mode Trait Object Serialization

Apache Fory™ supports polymorphic serialization through trait objects, enabling dynamic dispatch and type flexibility. This is essential for plugin systems, heterogeneous collections, and extensible architectures.

The examples in this section use native mode because Rust trait objects and `dyn Any` dispatch are Rust runtime features.

**Supported trait object types:**

- `Box<dyn Trait>` - Owned trait objects
- `Rc<dyn Trait>` - Reference-counted trait objects
- `Arc<dyn Trait>` - Thread-safe reference-counted trait objects
- `Box<dyn Any>`/`Rc<dyn Any>`/`Arc<dyn Any + Send + Sync>` - Any trait type objects
- `Vec<Box<dyn Trait>>`, `HashMap<K, Box<dyn Trait>>` - Collections of trait objects

`Box<dyn Any>`, `Rc<dyn Any>`, and `Arc<dyn Any + Send + Sync>` are supported
erased `Any` carriers for registered concrete non-container payloads.
Use `Arc<dyn Any + Send + Sync>` when the erased payload must be shareable
across threads; the concrete payload type must also satisfy `Send + Sync`.
Registered structs, enums, and unions that satisfy those bounds can be used as
the erased payload.
Generic containers such as `Vec<T>`, `HashMap<K, V>`, `HashSet<T>`, and
`LinkedList<T>` are not supported directly as top-level erased `Any` payloads
behind any of those carriers. This also includes primitive vector encodings such
as `Vec<u8>`. Wrap the container in a registered derived type when it needs to
travel behind an erased `Any` carrier.

**Basic Trait Object Serialization Example:**

```rust
use fory::{Fory, register_trait_type};
use fory::Serializer;
use fory::{ForyEnum, ForyStruct, ForyUnion};

trait Animal: Serializer {
    fn speak(&self) -> String;
    fn name(&self) -> &str;
}

#[derive(ForyStruct)]
struct Dog { name: String, breed: String }

impl Animal for Dog {
    fn speak(&self) -> String { "Woof!".to_string() }
    fn name(&self) -> &str { &self.name }
}

#[derive(ForyStruct)]
struct Cat { name: String, color: String }

impl Animal for Cat {
    fn speak(&self) -> String { "Meow!".to_string() }
    fn name(&self) -> &str { &self.name }
}

// Register trait implementations
register_trait_type!(Animal, Dog, Cat);

#[derive(ForyStruct)]
struct Zoo {
    star_animal: Box<dyn Animal>,
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<Dog>(100)?;
fory.register::<Cat>(101)?;
fory.register::<Zoo>(102)?;

let zoo = Zoo {
    star_animal: Box::new(Dog {
        name: "Buddy".to_string(),
        breed: "Labrador".to_string(),
    }),
};

let bytes = fory.serialize(&zoo)?;
let decoded: Zoo = fory.deserialize(&bytes)?;

assert_eq!(decoded.star_animal.name(), "Buddy");
assert_eq!(decoded.star_animal.speak(), "Woof!");
```

### 4. Schema Evolution

Apache Fory™ supports schema evolution in **Compatible mode**, allowing serialization and deserialization peers to have different type definitions. Compatible mode is the default for both xlang and native mode. Set `.compatible(false)` only when every reader and writer always uses the same schema and you want faster serialization and smaller size. For xlang payloads, use `.compatible(false)` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

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
use fory::Fory;
use fory::{ForyEnum, ForyStruct, ForyUnion};
use std::collections::HashMap;

#[derive(ForyStruct, Debug)]
struct PersonV1 {
    name: String,
    age: i32,
    address: String,
}

#[derive(ForyStruct, Debug)]
struct PersonV2 {
    name: String,
    age: i32,
    // address removed
    // phone added
    phone: Option<String>,
    metadata: HashMap<String, String>,
}

let mut fory1 = Fory::builder().xlang(true).build();
fory1.register_by_name::<PersonV1>("example.Person").unwrap();

let mut fory2 = Fory::builder().xlang(true).build();
fory2.register_by_name::<PersonV2>("example.Person").unwrap();

let person_v1 = PersonV1 {
    name: "Alice".to_string(),
    age: 30,
    address: "123 Main St".to_string(),
};

// Serialize with V1
let bytes = fory1.serialize(&person_v1).unwrap();

// Deserialize with V2 - missing fields get default values
let person_v2: PersonV2 = fory2.deserialize(&bytes)?;
assert_eq!(person_v2.name, "Alice");
assert_eq!(person_v2.age, 30);
assert_eq!(person_v2.phone, None);
```

### 5. Native-Mode Enum Support

Apache Fory™ supports three types of enum variants with full schema evolution in Compatible mode:

**Variant Types:**

- **Unit**: C-style enums (`Status::Active`)
- **Unnamed**: Tuple-like variants (`Message::Pair(String, i32)`)
- **Named**: Struct-like variants (`Event::Click { x: i32, y: i32 }`)

**Features:**

- Efficient varint encoding for variant ordinals
- Schema evolution support (add/remove variants, add/remove fields)
- Default variant support with `#[default]`
- Automatic type mismatch handling

```rust
use fory::{Fory, ForyEnum, ForyStruct, ForyUnion};

#[derive(Default, ForyStruct, Debug, PartialEq)]
enum Value {
    #[default]
    Null,
    Bool(bool),
    Number(f64),
    Text(String),
    Object { name: String, value: i32 },
}

let mut fory = Fory::builder().xlang(false).build();
fory.register::<Value>(1)?;

let value = Value::Object { name: "score".to_string(), value: 100 };
let bytes = fory.serialize(&value)?;
let decoded: Value = fory.deserialize(&bytes)?;
assert_eq!(value, decoded);
```

**Evolution capabilities:**

- **Unknown variants** → Falls back to default variant
- **Named variant fields** → Add/remove fields (missing fields use defaults)
- **Unnamed variant elements** → Add/remove elements (extras skipped, missing use defaults)
- **Variant type mismatches** → Automatically uses default value for current variant

**Best practices:**

- Always mark a default variant with `#[default]`
- Named variants provide better evolution than unnamed
- Use compatible mode for cross-version communication

### 6. Native-Mode Tuple Support

Apache Fory™ supports tuples up to 22 elements out of the box with efficient serialization in both compatible mode and same-schema mode.

**Features:**

- Automatic serialization for tuples from 1 to 22 elements
- Heterogeneous type support (each element can be a different type)
- Schema evolution in Compatible mode (handles missing/extra elements)

**Schema modes:**

1. **Same-schema mode**: Serializes elements sequentially without collection headers for minimal overhead
2. **Compatible mode**: Uses collection protocol with type metadata for schema evolution

```rust
use fory::{Fory, Error};

let mut fory = Fory::builder().xlang(false).build();

// Tuple with heterogeneous types
let data: (i32, String, bool, Vec<i32>) = (
    42,
    "hello".to_string(),
    true,
    vec![1, 2, 3],
);

let bytes = fory.serialize(&data)?;
let decoded: (i32, String, bool, Vec<i32>) = fory.deserialize(&bytes)?;
assert_eq!(data, decoded);
```

### 7. Native-Mode Custom Serializers

For types that don't support `#[derive(ForyStruct)]`, implement the `Serializer` trait manually. This is useful for:

- External types from other crates
- Types with special serialization requirements
- Existing data format compatibility
- Performance-critical custom encoding

```rust
use fory::{Fory, ReadContext, WriteContext, Serializer, ForyDefault, Error};
use std::any::Any;

#[derive(Debug, PartialEq, Default)]
struct CustomType {
    value: i32,
    name: String,
}

impl Serializer for CustomType {
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        context.writer.write_i32(self.value);
        context.writer.write_var_u32(self.name.len() as u32);
        context.writer.write_utf8_string(&self.name);
    }

    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        let value = context.reader.read_i32();
        let len = context.reader.read_var_u32() as usize;
        let name = context.reader.read_utf8_string(len);
        Ok(Self { value, name })
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> u32 {
        Self::fory_get_type_id(type_resolver)
    }

    fn as_any(&self) -> &dyn Any {
        self
    }
}

impl ForyDefault for CustomType {
    fn fory_default() -> Self {
        Self::default()
    }
}

let mut fory = Fory::builder().xlang(false).build();
fory.register_serializer::<CustomType>(100)?;

let custom = CustomType {
    value: 42,
    name: "test".to_string(),
};
let bytes = fory.serialize(&custom)?;
let decoded: CustomType = fory.deserialize(&bytes)?;
assert_eq!(custom, decoded);
```

### 7. Row-Based Serialization

Apache Fory™ provides a high-performance **row format** for zero-copy deserialization. Unlike traditional object serialization that reconstructs entire objects in memory, row format enables **random access** to fields directly from binary data without full deserialization.

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
use fory::{to_row, from_row};
use fory::ForyRow;
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
let row_data = to_row(&profile).unwrap();

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
assert_eq!(scores.get(0).unwrap(), 95);
assert_eq!(scores.get(1).unwrap(), 87);

let prefs = row.preferences();
assert_eq!(prefs.keys().size(), 2);
assert_eq!(prefs.keys().get(0).unwrap(), "language");
assert_eq!(prefs.values().get(0).unwrap(), "en");
```

**Performance comparison:**

| Operation            | Object Format                 | Row Format                      |
| -------------------- | ----------------------------- | ------------------------------- |
| Full deserialization | Allocates all objects         | Zero allocation                 |
| Single field access  | Full deserialization required | Direct offset read              |
| Memory usage         | Full object graph in memory   | Only accessed fields in memory  |
| Suitable for         | Small objects, full access    | Large objects, selective access |

## Cross-Language Serialization

Apache Fory™ supports seamless data exchange across multiple languages:

```rust
use fory::Fory;

// Use xlang mode, the Rust default.
let mut fory = Fory::builder().xlang(true).build();

// Register types with consistent IDs across languages
fory.register::<MyStruct>(100)?;

// Or use name-based registration
fory.register_by_name::<MyStruct>("com.example.MyStruct")?;
```

See [xlang_type_mapping.md](https://fory.apache.org/docs/specification/xlang_type_mapping) for type mapping across languages.

## Performance

Apache Fory™ Rust is designed for maximum performance:

- **Zero-Copy Deserialization**: Row format enables direct memory access without copying
- **Buffer Pre-allocation**: Minimizes memory allocations during serialization
- **Compact Encoding**: Variable-length encoding for space efficiency
- **Little-Endian**: Optimized for modern CPU architectures
- **Reference Deduplication**: Shared objects serialized only once

Run benchmarks:

```bash
cd benchmarks/rust
cargo bench
```

## Documentation

- **[User Guide](https://fory.apache.org/docs/guide/rust/)** - Comprehensive user documentation
- **[API Documentation](https://docs.rs/fory)** - Complete API reference
- **[Protocol Specification](https://fory.apache.org/docs/specification/xlang_serialization_spec)** - Serialization protocol details
- **[Type Mapping](https://fory.apache.org/docs/specification/xlang_type_mapping)** - Cross-language type mappings
- **[Source](https://github.com/apache/fory/tree/main/docs/guide/rust)** - Source code for doc

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

## Development

### Building

```bash
cd rust
cargo build
```

### Testing

```bash
# Run all tests
cargo test --workspace

# Run specific test
cargo test -p tests --test test_complex_struct
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

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.

## Contributing

We welcome contributions! Please see our [Contributing Guide](https://github.com/apache/fory/blob/main/CONTRIBUTING.md) for details.

## Support

- **Documentation**: [docs.rs/fory](https://docs.rs/fory)
- **Issues**: [GitHub Issues](https://github.com/apache/fory/issues)
- **Discussions**: [GitHub Discussions](https://github.com/apache/fory/discussions)
- **Slack**: [Apache Fory Slack](https://join.slack.com/t/fory-project/shared_invite/zt-1u8soj4qc-ieYEu7ciHOqA2mo47llS8A)

---

**Apache Fory™** - Blazingly fast multi-language serialization framework.
