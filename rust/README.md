# Apache Fory™ Rust

[![Crates.io](https://img.shields.io/crates/v/fory.svg)](https://crates.io/crates/fory)
[![Documentation](https://docs.rs/fory/badge.svg)](https://docs.rs/fory)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://github.com/apache/fory/blob/main/LICENSE)

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

The Rust implementation provides versatile and high-performance serialization with automatic memory management and compile-time type safety.

## 🚀 Why Apache Fory™ Rust?

- **🔥 Blazingly Fast**: Zero-copy deserialization and optimized binary protocols
- **🌍 Cross-Language**: Seamlessly serialize/deserialize data across Java, Python, C++, Go, JavaScript, and Rust
- **🎯 Type-Safe**: Compile-time type checking with derive macros
- **🔄 Circular References**: Automatic tracking of shared and circular references with `Rc`/`Arc` and weak pointers
- **🧬 Polymorphic**: Serialize trait objects with `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`
- **📦 Schema Evolution**: Compatible mode for independent schema changes
- **⚡ Two Modes**: Object graph serialization and zero-copy row-based format

## 📦 Crates

| Crate                                                                       | Description                       | Version                                                                                               |
| --------------------------------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------- |
| [`fory`](https://github.com/apache/fory/blob/main/rust/fory)                | High-level API with derive macros | [![crates.io](https://img.shields.io/crates/v/fory.svg)](https://crates.io/crates/fory)               |
| [`fory-core`](https://github.com/apache/fory/blob/main/rust/fory-core/)     | Core serialization engine         | [![crates.io](https://img.shields.io/crates/v/fory-core.svg)](https://crates.io/crates/fory-core)     |
| [`fory-derive`](https://github.com/apache/fory/blob/main/rust/fory-derive/) | Procedural macros                 | [![crates.io](https://img.shields.io/crates/v/fory-derive.svg)](https://crates.io/crates/fory-derive) |

## 🏃 Quick Start

Add Apache Fory™ to your `Cargo.toml`:

```toml
[dependencies]
fory = "0.13"
```

### Basic Example

```rust
use fory::{Fory, Error, Reader};
use fory::ForyObject;

#[derive(ForyObject, Debug, PartialEq)]
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
    fory.serialize_to(&user, &mut buf)?;
    // Deserialize from specified buffer
    let mut reader = Reader::new(&buf);
    let decoded: User = fory.deserialize_from(&mut reader)?;
    assert_eq!(user, decoded);
    Ok(())
}
```

## 📚 Core Features

### 1. Object Graph Serialization

Apache Fory™ provides automatic serialization of complex object graphs, preserving the structure and relationships between objects. The `#[derive(ForyObject)]` macro generates efficient serialization code at compile time, eliminating runtime overhead.

**Key capabilities:**

- Nested struct serialization with arbitrary depth
- Collection types (Vec, HashMap, HashSet, BTreeMap)
- Optional fields with `Option<T>`
- Automatic handling of primitive types and strings
- Efficient binary encoding with variable-length integers

```rust
use fory::{Fory, Error};
use fory::ForyObject;
use std::collections::HashMap;

#[derive(ForyObject, Debug, PartialEq)]
struct Person {
    name: String,
    age: i32,
    address: Address,
    hobbies: Vec<String>,
    metadata: HashMap<String, String>,
}

#[derive(ForyObject, Debug, PartialEq)]
struct Address {
    street: String,
    city: String,
    country: String,
}

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
        ("role".to_string(), "developer".to_string()),
    ]),
};

let bytes = fory.serialize(&person);
let decoded: Person = fory.deserialize(&bytes)?;
assert_eq!(person, decoded);
```

### 2. Shared and Circular References

Apache Fory™ automatically tracks and preserves reference identity for shared objects using `Rc<T>` and `Arc<T>`. When the same object is referenced multiple times, Fory serializes it only once and uses reference IDs for subsequent occurrences. This ensures:

- **Space efficiency**: No data duplication in serialized output
- **Reference identity preservation**: Deserialized objects maintain the same sharing relationships
- **Circular reference support**: Use `RcWeak<T>` and `ArcWeak<T>` to break cycles

#### Shared References with Rc/Arc

```rust
use fory::Fory;
use std::rc::Rc;

let fory = Fory::default();

// Create a shared value
let shared = Rc::new(String::from("shared_value"));

// Reference it multiple times
let data = vec![shared.clone(), shared.clone(), shared.clone()];

// The shared value is serialized only once
let bytes = fory.serialize(&data);
let decoded: Vec<Rc<String>> = fory.deserialize(&bytes)?;

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

let bytes = fory.serialize(&data);
let decoded: Vec<Arc<String>> = fory.deserialize(&bytes)?;

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
use fory::{Fory, Error};
use fory::ForyObject;
use fory::RcWeak;
use std::rc::Rc;
use std::cell::RefCell;

#[derive(ForyObject, Debug)]
struct Node {
    value: i32,
    parent: RcWeak<RefCell<Node>>,
    children: Vec<Rc<RefCell<Node>>>,
}

let mut fory = Fory::default();
fory.register::<Node>(2000);

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
let bytes = fory.serialize(&parent);
let decoded: Rc<RefCell<Node>> = fory.deserialize(&bytes)?;

// Verify the circular relationship
assert_eq!(decoded.borrow().children.len(), 2);
for child in &decoded.borrow().children {
    let upgraded_parent = child.borrow().parent.upgrade().unwrap();
    assert!(Rc::ptr_eq(&decoded, &upgraded_parent));
}
```

**Thread-Safe Circular Graphs with Arc:**

```rust
use fory::{Fory, Error};
use fory::ForyObject;
use fory::ArcWeak;
use std::sync::{Arc, Mutex};

#[derive(ForyObject)]
struct Node {
    val: i32,
    parent: ArcWeak<Mutex<Node>>,
    children: Vec<Arc<Mutex<Node>>>,
}

let mut fory = Fory::default();
fory.register::<Node>(6000);

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

let bytes = fory.serialize(&parent);
let decoded: Arc<Mutex<Node>> = fory.deserialize(&bytes)?;

assert_eq!(decoded.lock().unwrap().children.len(), 2);
for child in &decoded.lock().unwrap().children {
    let upgraded_parent = child.lock().unwrap().parent.upgrade().unwrap();
    assert!(Arc::ptr_eq(&decoded, &upgraded_parent));
}
```

### 3. Trait Object Serialization

Apache Fory™ supports polymorphic serialization through trait objects, enabling dynamic dispatch and type flexibility. This is essential for plugin systems, heterogeneous collections, and extensible architectures.

**Supported trait object types:**

- `Box<dyn Trait>` - Owned trait objects
- `Rc<dyn Trait>` - Reference-counted trait objects
- `Arc<dyn Trait>` - Thread-safe reference-counted trait objects
- `Vec<Box<dyn Trait>>`, `HashMap<K, Box<dyn Trait>>` - Collections of trait objects

#### Basic Trait Object Serialization

```rust
use fory::{Fory, register_trait_type};
use fory::Serializer;
use fory::ForyObject;

trait Animal: Serializer {
    fn speak(&self) -> String;
    fn name(&self) -> &str;
}

#[derive(ForyObject)]
struct Dog { name: String, breed: String }

impl Animal for Dog {
    fn speak(&self) -> String { "Woof!".to_string() }
    fn name(&self) -> &str { &self.name }
}

#[derive(ForyObject)]
struct Cat { name: String, color: String }

impl Animal for Cat {
    fn speak(&self) -> String { "Meow!".to_string() }
    fn name(&self) -> &str { &self.name }
}

// Register trait implementations
register_trait_type!(Animal, Dog, Cat);

#[derive(ForyObject)]
struct Zoo {
    star_animal: Box<dyn Animal>,
}

let mut fory = Fory::default().compatible(true);
fory.register::<Dog>(100);
fory.register::<Cat>(101);
fory.register::<Zoo>(102);

let zoo = Zoo {
    star_animal: Box::new(Dog {
        name: "Buddy".to_string(),
        breed: "Labrador".to_string(),
    }),
};

let bytes = fory.serialize(&zoo);
let decoded: Zoo = fory.deserialize(&bytes)?;

assert_eq!(decoded.star_animal.name(), "Buddy");
assert_eq!(decoded.star_animal.speak(), "Woof!");
```

#### Serializing `dyn Any` Trait Objects

Apache Fory™ supports serializing `Rc<dyn Any>` and `Arc<dyn Any>` for runtime type dispatch. This is useful when you need maximum flexibility and don't want to define a custom trait.

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
let bytes = fory.serialize(&dog_any);
let decoded: Rc<dyn Any> = fory.deserialize(&bytes)?;

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

let bytes = fory.serialize(&dog_any);
let decoded: Arc<dyn Any> = fory.deserialize(&bytes)?;

// Downcast to concrete type
let unwrapped = decoded.downcast_ref::<Dog>().unwrap();
assert_eq!(unwrapped.name, "Buddy");
```

#### Rc/Arc-Based Trait Objects in Structs

For fields with `Rc<dyn Trait>` or `Arc<dyn Trait>`, Fory automatically handles the conversion:

```rust
use std::sync::Arc;
use std::rc::Rc;
use std::collections::HashMap;

#[derive(ForyObject)]
struct AnimalShelter {
    animals_rc: Vec<Rc<dyn Animal>>,
    animals_arc: Vec<Arc<dyn Animal>>,
    registry: HashMap<String, Arc<dyn Animal>>,
}

let mut fory = Fory::default().compatible(true);
fory.register::<Dog>(100);
fory.register::<Cat>(101);
fory.register::<AnimalShelter>(102);

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

let bytes = fory.serialize(&shelter);
let decoded: AnimalShelter = fory.deserialize(&bytes)?;

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

let bytes = fory.serialize(&wrapper);
let decoded: AnimalRc = fory.deserialize(&bytes)?;

// Unwrap back to Rc<dyn Animal>
let unwrapped: Rc<dyn Animal> = decoded.unwrap();
assert_eq!(unwrapped.name(), "Rex");

// For Arc<dyn Trait>
let dog_arc: Arc<dyn Animal> = Arc::new(Dog {
    name: "Buddy".to_string(),
    breed: "Labrador".to_string()
});
let wrapper = AnimalArc::from(dog_arc);

let bytes = fory.serialize(&wrapper);
let decoded: AnimalArc = fory.deserialize(&bytes)?;

let unwrapped: Arc<dyn Animal> = decoded.unwrap();
assert_eq!(unwrapped.name(), "Buddy");
```

### 4. Schema Evolution

Apache Fory™ supports schema evolution in **Compatible mode**, allowing serialization and deserialization peers to have different type definitions. This enables independent evolution of services in distributed systems without breaking compatibility.

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
use fory::ForyObject;
use std::collections::HashMap;

#[derive(ForyObject, Debug)]
struct PersonV1 {
    name: String,
    age: i32,
    address: String,
}

#[derive(ForyObject, Debug)]
struct PersonV2 {
    name: String,
    age: i32,
    // address removed
    // phone added
    phone: Option<String>,
    metadata: HashMap<String, String>,
}

let mut fory1 = Fory::default().compatible(true);
fory1.register::<PersonV1>(1);

let mut fory2 = Fory::default().compatible(true);
fory2.register::<PersonV2>(1);

let person_v1 = PersonV1 {
    name: "Alice".to_string(),
    age: 30,
    address: "123 Main St".to_string(),
};

// Serialize with V1
let bytes = fory1.serialize(&person_v1);

// Deserialize with V2 - missing fields get default values
let person_v2: PersonV2 = fory2.deserialize(&bytes)?;
assert_eq!(person_v2.name, "Alice");
assert_eq!(person_v2.age, 30);
assert_eq!(person_v2.phone, None);
```

### 5. Enum Support

Apache Fory™ supports enums without data payloads (C-style enums). Each variant is assigned an ordinal value (0, 1, 2, ...) during serialization.

**Features:**

- Efficient varint encoding for ordinals
- Schema evolution support in Compatible mode
- Type-safe variant matching
- Default variant support with `#[default]`

```rust
use fory::ForyObject;

#[derive(Default, ForyObject, Debug, PartialEq)]
enum Status {
    #[default]
    Pending,
    Active,
    Inactive,
    Deleted,
}

let mut fory = Fory::default();
fory.register::<Status>(1);

let status = Status::Active;
let bytes = fory.serialize(&status);
let decoded: Status = fory.deserialize(&bytes)?;
assert_eq!(status, decoded);
```

### 6. Tuple Support

Apache Fory™ supports tuples up to 22 elements out of the box with efficient serialization in both compatible and non-compatible modes.

**Features:**

- Automatic serialization for tuples from 1 to 22 elements
- Heterogeneous type support (each element can be a different type)
- Schema evolution in Compatible mode (handles missing/extra elements)

**Serialization modes:**

1. **Non-compatible mode**: Serializes elements sequentially without collection headers for minimal overhead
2. **Compatible mode**: Uses collection protocol with type metadata for schema evolution

```rust
use fory::{Fory, Error};

let mut fory = Fory::default();

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

### 7. Custom Serializers

For types that don't support `#[derive(ForyObject)]`, implement the `Serializer` trait manually. This is useful for:

- External types from other crates
- Types with special serialization requirements
- Legacy data format compatibility
- Performance-critical custom encoding

```rust
use fory::{Fory, ReadContext, WriteContext, Serializer, ForyDefault, Error};
use std::any::Any;

#[derive(Debug, PartialEq)]
struct CustomType {
    value: i32,
    name: String,
}

impl Serializer for CustomType {
    fn fory_write_data(&self, context: &mut WriteContext, is_field: bool) {
        context.writer.write_i32(self.value);
        context.writer.write_varuint32(self.name.len() as u32);
        context.writer.write_utf8_string(&self.name);
    }

    fn fory_read_data(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        let value = context.reader.read_i32();
        let len = context.reader.read_varuint32() as usize;
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

let mut fory = Fory::default();
fory.register_serializer::<CustomType>(100);

let custom = CustomType {
    value: 42,
    name: "test".to_string(),
};
let bytes = fory.serialize(&custom);
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

### 8. Thread-Safe Serialization

Apache Fory™ Rust is fully thread-safe: `Fory` implements both `Send` and `Sync`, so one configured instance can be shared across threads for concurrent work. The internal read/write context pools are lazily initialized with thread-safe primitives, letting worker threads reuse buffers without coordination.

```rust
use fory::{Fory, Error};
use fory::ForyObject;
use std::sync::Arc;
use std::thread;

#[derive(ForyObject, Clone, Copy, Debug, PartialEq)]
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

## 🔧 Supported Types

### Primitive Types

| Rust Type                 | Description     |
| ------------------------- | --------------- |
| `bool`                    | Boolean         |
| `i8`, `i16`, `i32`, `i64` | Signed integers |
| `f32`, `f64`              | Floating point  |
| `String`                  | UTF-8 string    |

### Collections

| Rust Type        | Description        |
| ---------------- | ------------------ |
| `Vec<T>`         | Dynamic array      |
| `VecDeque<T>`    | Double-ended queue |
| `LinkedList<T>`  | Doubly-linked list |
| `HashMap<K, V>`  | Hash map           |
| `BTreeMap<K, V>` | Ordered map        |
| `HashSet<T>`     | Hash set           |
| `BTreeSet<T>`    | Ordered set        |
| `BinaryHeap<T>`  | Binary heap        |
| `Option<T>`      | Optional value     |

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
| `#[derive(ForyObject)]` | Object graph serialization |
| `#[derive(ForyRow)]`    | Row-based serialization    |

## 🌍 Cross-Language Serialization

Apache Fory™ supports seamless data exchange across multiple languages:

```rust
use fory::Fory;

// Enable cross-language mode
let mut fory = Fory::default()
    .compatible(true)
    .xlang(true);

// Register types with consistent IDs across languages
fory.register::<MyStruct>(100);

// Or use namespace-based registration
fory.register_by_namespace::<MyStruct>("com.example", "MyStruct");
```

See [xlang_type_mapping.md](https://fory.apache.org/docs/specification/xlang_type_mapping) for type mapping across languages.

## ⚡ Performance

Apache Fory™ Rust is designed for maximum performance:

- **Zero-Copy Deserialization**: Row format enables direct memory access without copying
- **Buffer Pre-allocation**: Minimizes memory allocations during serialization
- **Compact Encoding**: Variable-length encoding for space efficiency
- **Little-Endian**: Optimized for modern CPU architectures
- **Reference Deduplication**: Shared objects serialized only once

Run benchmarks:

```bash
cd rust
cargo bench --package fory-benchmarks
```

## 📖 Documentation

- **[API Documentation](https://docs.rs/fory)** - Complete API reference
- **[Protocol Specification](https://fory.apache.org/docs/specification/fory_xlang_serialization_spec)** - Serialization protocol details
- **[Type Mapping](https://fory.apache.org/docs/docs/guide/xlang_type_mapping)** - Cross-language type mappings

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
│   ├── object/       # ForyObject macro
│   └── fory_row.rs  # ForyRow macro
```

## 🔄 Serialization Modes

Apache Fory™ supports two serialization modes:

### SchemaConsistent Mode (Default)

Type declarations must match exactly between peers:

```rust
let fory = Fory::default(); // SchemaConsistent by default
```

### Compatible Mode

Allows independent schema evolution:

```rust
let fory = Fory::default().compatible(true);
```

## ⚙️ Configuration

### Maximum Dynamic Object Nesting Depth

Apache Fory™ provides protection against stack overflow from deeply nested dynamic objects during deserialization. By default, the maximum nesting depth is set to 5 levels for trait objects and containers.

**Default configuration:**

```rust
let fory = Fory::default(); // max_dyn_depth = 5
```

**Custom depth limit:**

```rust
let fory = Fory::default().max_dyn_depth(10); // Allow up to 10 levels
```

**When to adjust:**

- **Increase**: For legitimate deeply nested data structures
- **Decrease**: For stricter security requirements or shallow data structures

**Protected types:**

- `Box<dyn Any>`, `Rc<dyn Any>`, `Arc<dyn Any>`
- `Box<dyn Trait>`, `Rc<dyn Trait>`, `Arc<dyn Trait>` (trait objects)
- `RcWeak<T>`, `ArcWeak<T>`
- Collection types (Vec, HashMap, HashSet)
- Nested struct types in Compatible mode

Note: Static data types (non-dynamic types) are secure by nature and not subject to depth limits, as their structure is known at compile time.

## 🧪 Troubleshooting

- **Type registry errors**: An error like `TypeId ... not found in type_info registry` means the type was never registered with the current `Fory` instance. Confirm that every serializable struct or trait implementation calls `fory.register::<T>(type_id)` before serialization and that the same IDs are reused on the deserialize side.
- **Quick error lookup**: Prefer the static constructors on `fory_core::error::Error` (`Error::type_mismatch`, `Error::invalid_data`, `Error::unknown`, etc.) rather than instantiating variants manually. This keeps diagnostics consistent and makes opt-in panics work.
- **Panic on error for backtraces**: Toggle `FORY_PANIC_ON_ERROR=1` (or `true`) alongside `RUST_BACKTRACE=1` when running tests or binaries to panic at the exact site an error is constructed. Reset the variable afterwards to avoid aborting user-facing code paths.
- **Struct field tracing**: Add the `#[fory_debug]` attribute alongside `#[derive(ForyObject)]` to tell the macro to emit hook invocations for that type. Once compiled with debug hooks, call `set_before_write_field_func`, `set_after_write_field_func`, `set_before_read_field_func`, or `set_after_read_field_func` (from `fory-core/src/serializer/struct_.rs`) to plug in custom callbacks, and use `reset_struct_debug_hooks()` when you want the defaults back.
- **Lightweight logging**: Without custom hooks, enable `ENABLE_FORY_DEBUG_OUTPUT=1` to print field-level read/write events emitted by the default hook functions. This is especially useful when investigating alignment or cursor mismatches.
- **Test-time hygiene**: Some integration tests expect `FORY_PANIC_ON_ERROR` to remain unset. Export it only for focused debugging sessions, and prefer `cargo test --features tests -p tests --test <case>` when isolating failing scenarios.

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

## 🗺️ Roadmap

- [x] Static codegen based on rust macro
- [x] Row format serialization
- [x] Cross-language object graph serialization
- [x] Shared and circular reference tracking
- [x] Weak pointer support
- [x] Trait object serialization with polymorphism
- [x] Schema evolution in compatible mode
- [x] SIMD optimizations for string encoding
- [ ] Cross-language support for shared and circular reference tracking
- [ ] Cross-language support for trait objects
- [ ] Performance optimizations
- [ ] More comprehensive benchmarks

## 📄 License

Licensed under the Apache License, Version 2.0. See [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.

## 🤝 Contributing

We welcome contributions! Please see our [Contributing Guide](https://github.com/apache/fory/blob/main/CONTRIBUTING.md) for details.

## 📞 Support

- **Documentation**: [docs.rs/fory](https://docs.rs/fory)
- **Issues**: [GitHub Issues](https://github.com/apache/fory/issues)
- **Discussions**: [GitHub Discussions](https://github.com/apache/fory/discussions)
- **Slack**: [Apache Fory Slack](https://join.slack.com/t/fory-project/shared_invite/zt-1u8soj4qc-ieYEu7ciHOqA2mo47llS8A)

---

**Apache Fory™** - Blazingly fast multi-language serialization framework.
