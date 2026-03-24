// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

//! # Apache Fory™ Rust
//!
//! **Apache Fory™** is a blazingly fast multi-language serialization framework powered by
//! **JIT compilation** and **zero-copy** techniques, providing up to ultra-fast performance
//! while maintaining ease of use and safety.
//!
//! The Rust implementation provides versatile and high-performance serialization with
//! automatic memory management and compile-time type safety.
//!
//! **GitHub**: <https://github.com/apache/fory>
//!
//! ## Why Apache Fory™ Rust?
//!
//! Apache Fory™ Rust solves the fundamental serialization dilemma: **you shouldn't have to
//! choose between performance and developer experience**. Traditional frameworks force you
//! to pick between fast but fragile binary formats, flexible but slow text-based protocols,
//! or complex solutions that don't support your language's advanced features.
//!
//! **Key differentiators:**
//!
//! - **🔥 Blazingly Fast**: Zero-copy deserialization and optimized binary protocols
//! - **🌍 Cross-Language**: Seamlessly serialize/deserialize data across Java, Python, C++, Go, JavaScript, and Rust
//! - **🎯 Type-Safe**: Compile-time type checking with derive macros
//! - **🔄 Circular References**: Automatic tracking of shared and circular references with `Rc`/`Arc` and weak pointers
//! - **🧬 Polymorphic**: Serialize trait objects with `Box<dyn Trait>`, `Rc<dyn Trait>`, and `Arc<dyn Trait>`
//! - **📦 Schema Evolution**: Compatible mode for independent schema changes
//! - **⚡ Two Formats**: Object graph serialization and zero-copy row-based format
//!
//! ## Quick Start
//!
//! Add Apache Fory™ to your `Cargo.toml`:
//!
//! ```toml
//! [dependencies]
//! fory = "0.13"
//! fory-derive = "0.13"
//! ```
//!
//! ### Basic Example
//!
//! ```rust
//! use fory::{Fory, Error, Reader};
//! use fory::ForyObject;
//!
//! #[derive(ForyObject, Debug, PartialEq)]
//! struct User {
//!     name: String,
//!     age: i32,
//!     email: String,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//! fory.register::<User>(1)?;
//!
//! let user = User {
//!     name: "Alice".to_string(),
//!     age: 30,
//!     email: "alice@example.com".to_string(),
//! };
//!
//! // Serialize and deserialize
//! let bytes = fory.serialize(&user)?;
//! let decoded: User = fory.deserialize(&bytes)?;
//! assert_eq!(user, decoded);
//!
//! // Serialize to specified buffer and deserialize from it
//! let mut buf: Vec<u8> = vec![];
//! fory.serialize_to(&mut buf, &user)?;
//! let mut reader = Reader::new(&buf);
//! let decoded: User = fory.deserialize_from(&mut reader)?;
//! assert_eq!(user, decoded);
//! # Ok(())
//! # }
//! ```
//!
//! ## Core Features
//!
//! Apache Fory™ Rust provides seven major feature categories, each designed to solve
//! specific serialization challenges in modern applications.
//!
//! ### 1. Object Graph Serialization
//!
//! **What it does:** Automatically serializes complex nested data structures while
//! preserving relationships and hierarchies.
//!
//! **Why it matters:** Most real-world applications deal with complex domain models,
//! not just flat data structures. Apache Fory™ handles arbitrary nesting depth,
//! collections, and optional fields without manual mapping code.
//!
//! **Technical approach:** The `#[derive(ForyObject)]` macro generates efficient
//! serialization code at compile time using procedural macros. This eliminates runtime
//! reflection overhead while maintaining type safety.
//!
//! **Key capabilities:**
//!
//! - Nested struct serialization with arbitrary depth
//! - Collection types (`Vec`, `HashMap`, `HashSet`, `BTreeMap`)
//! - Optional fields with `Option<T>`
//! - Automatic handling of primitive types and strings
//! - Efficient binary encoding with variable-length integers
//!
//! ```rust
//! use fory::{Fory, Error};
//! use fory::ForyObject;
//! use std::collections::HashMap;
//!
//! #[derive(ForyObject, Debug, PartialEq)]
//! struct Person {
//!     name: String,
//!     age: i32,
//!     address: Address,
//!     hobbies: Vec<String>,
//!     metadata: HashMap<String, String>,
//! }
//!
//! #[derive(ForyObject, Debug, PartialEq)]
//! struct Address {
//!     street: String,
//!     city: String,
//!     country: String,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//! fory.register::<Address>(100);
//! fory.register::<Person>(200);
//!
//! let person = Person {
//!     name: "John Doe".to_string(),
//!     age: 30,
//!     address: Address {
//!         street: "123 Main St".to_string(),
//!         city: "New York".to_string(),
//!         country: "USA".to_string(),
//!     },
//!     hobbies: vec!["reading".to_string(), "coding".to_string()],
//!     metadata: HashMap::from([
//!         ("role".to_string(), "developer".to_string()),
//!     ]),
//! };
//!
//! let bytes = fory.serialize(&person)?;
//! let decoded: Person = fory.deserialize(&bytes)?;
//! assert_eq!(person, decoded);
//! # Ok(())
//! # }
//! ```
//!
//! ### 2. Shared and Circular References
//!
//! **What it does:** Automatically tracks and preserves reference identity for shared
//! objects using `Rc<T>` and `Arc<T>`, and handles circular references using weak pointers.
//!
//! **Why it matters:** Graph-like data structures (trees, linked lists, object-relational
//! models) are common in real applications but notoriously difficult to serialize. Most
//! frameworks either panic on circular references or require extensive manual handling.
//!
//! **Technical approach:** Apache Fory™ maintains a reference tracking map during
//! serialization. When the same object is encountered multiple times, it's serialized
//! only once and subsequent references use IDs. Weak pointers (`RcWeak<T>`, `ArcWeak<T>`)
//! break cycles by serializing as references without strong ownership.
//!
//! **Benefits:**
//!
//! - **Space efficiency**: No data duplication in serialized output
//! - **Reference identity preservation**: Deserialized objects maintain the same sharing relationships
//! - **Circular reference support**: Use `RcWeak<T>` and `ArcWeak<T>` to break cycles
//! - **Forward reference resolution**: Callbacks handle weak pointers appearing before targets
//!
//! #### Shared References with Rc/Arc
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//! use std::rc::Rc;
//!
//! # fn main() -> Result<(), Error> {
//! let fory = Fory::default();
//!
//! let shared = Rc::new(String::from("shared_value"));
//! let data = vec![shared.clone(), shared.clone(), shared.clone()];
//!
//! let bytes = fory.serialize(&data)?;
//! let decoded: Vec<Rc<String>> = fory.deserialize(&bytes)?;
//!
//! assert_eq!(decoded.len(), 3);
//! assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
//! assert!(Rc::ptr_eq(&decoded[1], &decoded[2]));
//! # Ok(())
//! # }
//! ```
//!
//! For thread-safe shared references, use `Arc<T>`:
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//! use std::sync::Arc;
//!
//! # fn main() -> Result<(), Error> {
//! let fory = Fory::default();
//! let shared = Arc::new(String::from("shared_value"));
//! let data = vec![shared.clone(), shared.clone()];
//!
//! let bytes = fory.serialize(&data)?;
//! let decoded: Vec<Arc<String>> = fory.deserialize(&bytes)?;
//!
//! assert!(Arc::ptr_eq(&decoded[0], &decoded[1]));
//! # Ok(())
//! # }
//! ```
//!
//! #### Circular References with Weak Pointers
//!
//! **How it works:**
//!
//! - Weak pointers serialize as references to their target objects
//! - If the strong pointer has been dropped, weak serializes as `Null`
//! - Forward references (weak appearing before target) are resolved via callbacks
//! - All clones of a weak pointer share the same internal cell for automatic updates
//!
//! ```rust
//! use fory::{Fory, Error, RcWeak};
//! use fory::ForyObject;
//! use std::rc::Rc;
//! use std::cell::RefCell;
//!
//! #[derive(ForyObject, Debug)]
//! struct Node {
//!     value: i32,
//!     parent: RcWeak<RefCell<Node>>,
//!     children: Vec<Rc<RefCell<Node>>>,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default().track_ref(true);
//! fory.register::<Node>(2000);
//!
//! let parent = Rc::new(RefCell::new(Node {
//!     value: 1,
//!     parent: RcWeak::new(),
//!     children: vec![],
//! }));
//!
//! let child1 = Rc::new(RefCell::new(Node {
//!     value: 2,
//!     parent: RcWeak::from(&parent),
//!     children: vec![],
//! }));
//!
//! parent.borrow_mut().children.push(child1.clone());
//!
//! let bytes = fory.serialize(&parent)?;
//! let decoded: Rc<RefCell<Node>> = fory.deserialize(&bytes)?;
//!
//! assert_eq!(decoded.borrow().children.len(), 1);
//! let upgraded_parent = decoded.borrow().children[0].borrow().parent.upgrade().unwrap();
//! assert!(Rc::ptr_eq(&decoded, &upgraded_parent));
//! # Ok(())
//! # }
//! ```
//!
//! **Thread-Safe Circular Graphs with Arc:**
//!
//! ```rust
//! use fory::{Fory, Error, ArcWeak};
//! use fory::ForyObject;
//! use std::sync::{Arc, Mutex};
//!
//! #[derive(ForyObject)]
//! struct Node {
//!     val: i32,
//!     parent: ArcWeak<Mutex<Node>>,
//!     children: Vec<Arc<Mutex<Node>>>,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default().track_ref(true);
//! fory.register::<Node>(6000);
//!
//! let parent = Arc::new(Mutex::new(Node {
//!     val: 10,
//!     parent: ArcWeak::new(),
//!     children: vec![],
//! }));
//!
//! let child = Arc::new(Mutex::new(Node {
//!     val: 20,
//!     parent: ArcWeak::from(&parent),
//!     children: vec![],
//! }));
//!
//! parent.lock().unwrap().children.push(child.clone());
//!
//! let bytes = fory.serialize(&parent)?;
//! let decoded: Arc<Mutex<Node>> = fory.deserialize(&bytes)?;
//!
//! assert_eq!(decoded.lock().unwrap().children.len(), 1);
//! # Ok(())
//! # }
//! ```
//!
//! ### 3. Trait Object Serialization
//!
//! **What it does:** Enables polymorphic serialization through trait objects, supporting
//! dynamic dispatch and type flexibility.
//!
//! **Why it matters:** Rust's trait system is powerful for abstraction, but serializing
//! `Box<dyn Trait>` is notoriously difficult. This feature is essential for plugin systems,
//! heterogeneous collections, and extensible architectures.
//!
//! **Technical approach:** The `register_trait_type!` macro generates type registration
//! and dispatch code for trait implementations. During serialization, type IDs are written
//! alongside data, enabling correct deserialization to the concrete type.
//!
//! **Supported trait object types:**
//!
//! - `Box<dyn Trait>` - Owned trait objects
//! - `Rc<dyn Trait>` - Reference-counted trait objects
//! - `Arc<dyn Trait>` - Thread-safe reference-counted trait objects
//! - `Rc<dyn Any>` / `Arc<dyn Any>` - Runtime type dispatch without custom traits
//! - Collections: `Vec<Box<dyn Trait>>`, `HashMap<K, Box<dyn Trait>>`
//!
//! #### Basic Trait Object Serialization
//!
//! ```rust
//! use fory::{Fory, register_trait_type, Serializer, Error};
//! use fory::ForyObject;
//!
//! trait Animal: Serializer {
//!     fn speak(&self) -> String;
//!     fn name(&self) -> &str;
//! }
//!
//! #[derive(ForyObject, Debug)]
//! struct Dog { name: String, breed: String }
//!
//! impl Animal for Dog {
//!     fn speak(&self) -> String { "Woof!".to_string() }
//!     fn name(&self) -> &str { &self.name }
//! }
//!
//! #[derive(ForyObject, Debug)]
//! struct Cat { name: String, color: String }
//!
//! impl Animal for Cat {
//!     fn speak(&self) -> String { "Meow!".to_string() }
//!     fn name(&self) -> &str { &self.name }
//! }
//!
//! register_trait_type!(Animal, Dog, Cat);
//!
//! #[derive(ForyObject)]
//! struct Zoo {
//!     star_animal: Box<dyn Animal>,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default().compatible(true);
//! fory.register::<Dog>(100);
//! fory.register::<Cat>(101);
//! fory.register::<Zoo>(102);
//!
//! let zoo = Zoo {
//!     star_animal: Box::new(Dog {
//!         name: "Buddy".to_string(),
//!         breed: "Labrador".to_string(),
//!     }),
//! };
//!
//! let bytes = fory.serialize(&zoo)?;
//! let decoded: Zoo = fory.deserialize(&bytes)?;
//!
//! assert_eq!(decoded.star_animal.name(), "Buddy");
//! assert_eq!(decoded.star_animal.speak(), "Woof!");
//! # Ok(())
//! # }
//! ```
//!
//! #### Serializing `dyn Any` Trait Objects
//!
//! **What it does:** Supports serializing `Rc<dyn Any>` and `Arc<dyn Any>` for maximum
//! runtime type flexibility without defining custom traits.
//!
//! **When to use:** Plugin systems, dynamic type handling, or when you need runtime type
//! dispatch without compile-time trait definitions.
//!
//! **Key points:**
//!
//! - Works with any type that implements `Serializer`
//! - Requires downcasting after deserialization to access the concrete type
//! - Type information is preserved during serialization
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//! use std::rc::Rc;
//! use std::any::Any;
//! use fory::ForyObject;
//!
//! #[derive(ForyObject)]
//! struct Dog { name: String }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//! fory.register::<Dog>(100);
//!
//! let dog: Rc<dyn Any> = Rc::new(Dog {
//!     name: "Rex".to_string()
//! });
//!
//! let bytes = fory.serialize(&dog)?;
//! let decoded: Rc<dyn Any> = fory.deserialize(&bytes)?;
//!
//! let unwrapped = decoded.downcast_ref::<Dog>().unwrap();
//! assert_eq!(unwrapped.name, "Rex");
//! # Ok(())
//! # }
//! ```
//!
//! For thread-safe scenarios, use `Arc<dyn Any>`:
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//! use std::sync::Arc;
//! use std::any::Any;
//! use fory::ForyObject;
//!
//! #[derive(ForyObject)]
//! struct Cat { name: String }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//! fory.register::<Cat>(101);
//!
//! let cat: Arc<dyn Any> = Arc::new(Cat {
//!     name: "Whiskers".to_string()
//! });
//!
//! let bytes = fory.serialize(&cat)?;
//! let decoded: Arc<dyn Any> = fory.deserialize(&bytes)?;
//!
//! let unwrapped = decoded.downcast_ref::<Cat>().unwrap();
//! assert_eq!(unwrapped.name, "Whiskers");
//! # Ok(())
//! # }
//! ```
//!
//! #### Rc/Arc-Based Trait Objects in Structs
//!
//! For struct fields containing `Rc<dyn Trait>` or `Arc<dyn Trait>`, Apache Fory™
//! automatically handles the conversion without needing wrappers:
//!
//! ```rust
//! use fory::{Fory, register_trait_type, Serializer, Error};
//! use fory::ForyObject;
//! use std::sync::Arc;
//! use std::rc::Rc;
//!
//! trait Animal: Serializer {
//!     fn name(&self) -> &str;
//! }
//!
//! #[derive(ForyObject, Debug)]
//! struct Dog { name: String }
//! impl Animal for Dog {
//!     fn name(&self) -> &str { &self.name }
//! }
//!
//! #[derive(ForyObject, Debug)]
//! struct Cat { name: String }
//! impl Animal for Cat {
//!     fn name(&self) -> &str { &self.name }
//! }
//!
//! register_trait_type!(Animal, Dog, Cat);
//!
//! #[derive(ForyObject)]
//! struct AnimalShelter {
//!     animals_rc: Vec<Rc<dyn Animal>>,
//!     animals_arc: Vec<Arc<dyn Animal>>,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default().compatible(true);
//! fory.register::<Dog>(100);
//! fory.register::<Cat>(101);
//! fory.register::<AnimalShelter>(102);
//!
//! let shelter = AnimalShelter {
//!     animals_rc: vec![
//!         Rc::new(Dog { name: "Rex".to_string() }),
//!         Rc::new(Cat { name: "Mittens".to_string() }),
//!     ],
//!     animals_arc: vec![
//!         Arc::new(Dog { name: "Buddy".to_string() }),
//!     ],
//! };
//!
//! let bytes = fory.serialize(&shelter)?;
//! let decoded: AnimalShelter = fory.deserialize(&bytes)?;
//!
//! assert_eq!(decoded.animals_rc[0].name(), "Rex");
//! assert_eq!(decoded.animals_arc[0].name(), "Buddy");
//! # Ok(())
//! # }
//! ```
//!
//! #### Standalone Trait Object Serialization with Wrappers
//!
//! Due to Rust's orphan rule, `Rc<dyn Trait>` and `Arc<dyn Trait>` cannot implement
//! `Serializer` directly. For standalone serialization (not inside struct fields),
//! the `register_trait_type!` macro generates wrapper types.
//!
//! **Note:** If you don't want to use wrapper types, you can serialize as `Rc<dyn Any>`
//! or `Arc<dyn Any>` instead (see the `dyn Any` section above).
//!
//! The `register_trait_type!` macro generates `AnimalRc` and `AnimalArc` wrapper types:
//!
//! ```rust
//! use fory::{Fory, Error, register_trait_type, Serializer};
//! use fory::ForyObject;
//! use std::sync::Arc;
//! use std::rc::Rc;
//!
//! trait Animal: Serializer {
//!     fn name(&self) -> &str;
//! }
//!
//! #[derive(ForyObject, Debug)]
//! struct Dog { name: String }
//! impl Animal for Dog {
//!     fn name(&self) -> &str { &self.name }
//! }
//!
//! register_trait_type!(Animal, Dog);
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default().compatible(true);
//! fory.register::<Dog>(100);
//!
//! // For Rc<dyn Trait>
//! let dog_rc: Rc<dyn Animal> = Rc::new(Dog { name: "Rex".to_string() });
//! let wrapper = AnimalRc::from(dog_rc);
//!
//! let bytes = fory.serialize(&wrapper)?;
//! let decoded: AnimalRc = fory.deserialize(&bytes)?;
//!
//! // Unwrap back to Rc<dyn Animal>
//! let unwrapped: Rc<dyn Animal> = decoded.unwrap();
//! assert_eq!(unwrapped.name(), "Rex");
//!
//! // For Arc<dyn Trait>
//! let dog_arc: Arc<dyn Animal> = Arc::new(Dog { name: "Buddy".to_string() });
//! let wrapper = AnimalArc::from(dog_arc);
//!
//! let bytes = fory.serialize(&wrapper)?;
//! let decoded: AnimalArc = fory.deserialize(&bytes)?;
//!
//! let unwrapped: Arc<dyn Animal> = decoded.unwrap();
//! assert_eq!(unwrapped.name(), "Buddy");
//! # Ok(())
//! # }
//! ```
//!
//! ### 4. Schema Evolution
//!
//! **What it does:** Supports schema evolution in **Compatible mode**, allowing
//! serialization and deserialization peers to have different type definitions.
//!
//! **Why it matters:** In distributed systems and microservices, different services
//! evolve independently. Schema evolution enables zero-downtime deployments where
//! services can be updated gradually without breaking communication.
//!
//! **Technical approach:** In Compatible mode, Apache Fory™ includes field names and
//! type metadata in the serialized data. During deserialization, fields are matched by
//! name, allowing for additions, deletions, and reordering.
//!
//! **Features:**
//!
//! - Add new fields with default values
//! - Remove obsolete fields (skipped during deserialization)
//! - Change field nullability (`T` ↔ `Option<T>`)
//! - Reorder fields (matched by name, not position)
//! - Type-safe fallback to default values for missing fields
//!
//! **Compatibility rules:**
//!
//! - Field names must match (case-sensitive)
//! - Type changes are not supported (except nullable/non-nullable)
//! - Nested struct types must be registered on both sides
//!
//! ```rust
//! use fory::{Fory, Error};
//! use fory::ForyObject;
//! use std::collections::HashMap;
//!
//! #[derive(ForyObject, Debug)]
//! struct PersonV1 {
//!     name: String,
//!     age: i32,
//!     address: String,
//! }
//!
//! #[derive(ForyObject, Debug)]
//! struct PersonV2 {
//!     name: String,
//!     age: i32,
//!     phone: Option<String>,
//!     metadata: HashMap<String, String>,
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory1 = Fory::default().compatible(true);
//! fory1.register::<PersonV1>(1);
//!
//! let mut fory2 = Fory::default().compatible(true);
//! fory2.register::<PersonV2>(1);
//!
//! let person_v1 = PersonV1 {
//!     name: "Alice".to_string(),
//!     age: 30,
//!     address: "123 Main St".to_string(),
//! };
//!
//! let bytes = fory1.serialize(&person_v1)?;
//! let person_v2: PersonV2 = fory2.deserialize(&bytes)?;
//!
//! assert_eq!(person_v2.name, "Alice");
//! assert_eq!(person_v2.age, 30);
//! assert_eq!(person_v2.phone, None);
//! # Ok(())
//! # }
//! ```
//!
//! ### 5. Enum Support
//!
//! **What it does:** Comprehensive enum support with three variant types (unit, unnamed, named)
//! and full schema evolution in Compatible mode.
//!
//! **Why it matters:** Enums are essential for state machines, status codes, type discriminators,
//! and domain modeling. Supporting all variant types with schema evolution enables flexible API
//! evolution without breaking compatibility.
//!
//! **Technical approach:** Each variant is assigned an ordinal value (0, 1, 2, ...). In compatible
//! mode, variants are encoded with both a tag (ordinal) and a type marker (2 bits: 0b0=Unit,
//! 0b1=Unnamed, 0b10=Named). Named variants generate meta types for field-level evolution.
//!
//! **Variant Types:**
//!
//! - **Unit**: C-style enums (`Status::Active`)
//! - **Unnamed**: Tuple-like variants (`Message::Pair(String, i32)`)
//! - **Named**: Struct-like variants (`Event::Click { x: i32, y: i32 }`)
//!
//! **Features:**
//!
//! - Efficient varint encoding for variant ordinals
//! - Schema evolution support (add/remove variants, add/remove fields)
//! - Default variant support with `#[default]`
//! - Automatic type mismatch handling
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//! use fory::ForyObject;
//!
//! #[derive(Default, ForyObject, Debug, PartialEq)]
//! enum Value {
//!     #[default]
//!     Null,
//!     Bool(bool),
//!     Number(f64),
//!     Text(String),
//!     Object { name: String, value: i32 },
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//! fory.register::<Value>(1)?;
//!
//! let value = Value::Object { name: "score".to_string(), value: 100 };
//! let bytes = fory.serialize(&value)?;
//! let decoded: Value = fory.deserialize(&bytes)?;
//! assert_eq!(value, decoded);
//! # Ok(())
//! # }
//! ```
//!
//! **Schema Evolution:**
//!
//! Compatible mode enables robust schema evolution with variant type encoding:
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//! use fory::ForyObject;
//!
//! // Old version with 2 fields
//! #[derive(ForyObject, Debug)]
//! enum OldEvent {
//!     Click { x: i32, y: i32 },
//! }
//!
//! // New version with 3 fields - added timestamp
//! #[derive(ForyObject, Debug)]
//! enum NewEvent {
//!     Click { x: i32, y: i32, timestamp: u64 },
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory_old = Fory::default().compatible(true);
//! fory_old.register::<OldEvent>(5)?;
//!
//! let mut fory_new = Fory::default().compatible(true);
//! fory_new.register::<NewEvent>(5)?;
//!
//! // Serialize with old schema (2 fields)
//! let old_bytes = fory_old.serialize(&OldEvent::Click { x: 100, y: 200 })?;
//!
//! // Deserialize with new schema (3 fields) - timestamp gets default value (0)
//! let new_event: NewEvent = fory_new.deserialize(&old_bytes)?;
//! match new_event {
//!     NewEvent::Click { x, y, timestamp } => {
//!         assert_eq!(x, 100);
//!         assert_eq!(y, 200);
//!         assert_eq!(timestamp, 0); // Default value for missing field
//!     }
//! }
//! # Ok(())
//! # }
//! ```
//!
//! **Evolution capabilities:**
//!
//! - Unknown variants fall back to default variant
//! - Named variant fields: add/remove fields (missing fields use defaults)
//! - Unnamed variant elements: add/remove elements (extras skipped, missing use defaults)
//! - Variant type mismatches automatically use default value of current variant
//!
//! ### 6. Tuple Support
//!
//! **What it does:** Supports tuples up to 22 elements with automatic heterogeneous type
//! handling and schema evolution in compatible mode.
//!
//! **Why it matters:** Tuples provide lightweight aggregation without defining full structs,
//! useful for temporary groupings, function return values, and ad-hoc data structures.
//!
//! **Technical approach:** Each tuple size (1-22) has a specialized `Serializer` implementation.
//! In non-compatible mode, elements are serialized sequentially without overhead. In compatible
//! mode, the tuple is serialized as a heterogeneous collection with type metadata for each element.
//!
//! **Features:**
//!
//! - Automatic serialization for tuples from 1 to 22 elements
//! - Heterogeneous type support (each element can be a different type)
//! - Schema evolution in Compatible mode (handles missing/extra elements)
//! - Default values for missing elements during deserialization
//!
//! ```rust
//! use fory::Fory;
//! use fory::Error;
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//!
//! // Tuple with heterogeneous types
//! let data: (i32, String, bool, Vec<i32>) = (
//!     42,
//!     "hello".to_string(),
//!     true,
//!     vec![1, 2, 3],
//! );
//!
//! let bytes = fory.serialize(&data)?;
//! let decoded: (i32, String, bool, Vec<i32>) = fory.deserialize(&bytes)?;
//! assert_eq!(data, decoded);
//! # Ok(())
//! # }
//! ```
//!
//! ### 7. Custom Serializers
//!
//! **What it does:** Allows manual implementation of the `Serializer` trait for types
//! that don't support `#[derive(ForyObject)]`.
//!
//! **When to use:**
//!
//! - External types from other crates that you can't modify
//! - Types with special serialization requirements
//! - Legacy data format compatibility
//! - Performance-critical custom encoding
//! - Complex types that require special handling
//!
//! **Technical approach:** Implement the `Serializer` trait's `fory_write_data()` and
//! `fory_read_data()` methods to control exactly how data is written to and read from
//! the binary buffer.
//!
//! ```rust
//! use fory::{Fory, TypeResolver, ReadContext, WriteContext, Serializer, ForyDefault, Error};
//! use std::any::Any;
//!
//! #[derive(Debug, PartialEq, Default)]
//! struct CustomType {
//!     value: i32,
//!     name: String,
//! }
//!
//! impl Serializer for CustomType {
//!     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
//!         context.writer.write_i32(self.value);
//!         context.writer.write_var_uint32(self.name.len() as u32);
//!         context.writer.write_utf8_string(&self.name);
//!         Ok(())
//!     }
//!
//!     fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
//!         let value = context.reader.read_i32()?;
//!         let len = context.reader.read_varuint32()? as usize;
//!         let name = context.reader.read_utf8_string(len)?;
//!         Ok(Self { value, name })
//!     }
//!
//!     fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<fory::TypeId, Error> {
//!         Self::fory_get_type_id(type_resolver)
//!     }
//!
//!     fn as_any(&self) -> &dyn Any {
//!         self
//!     }
//! }
//!
//! impl ForyDefault for CustomType {
//!     fn fory_default() -> Self {
//!         Self::default()
//!     }
//! }
//!
//! # fn main() -> Result<(), Error> {
//! let mut fory = Fory::default();
//! fory.register_serializer::<CustomType>(100);
//!
//! let custom = CustomType {
//!     value: 42,
//!     name: "test".to_string(),
//! };
//! let bytes = fory.serialize(&custom)?;
//! let decoded: CustomType = fory.deserialize(&bytes)?;
//! assert_eq!(custom, decoded);
//! # Ok(())
//! # }
//! ```
//!
//! ### 7. Row-Based Serialization
//!
//! **What it does:** Provides a high-performance **row format** for zero-copy
//! deserialization, enabling random access to fields directly from binary data
//! without full object reconstruction.
//!
//! **Why it matters:** Traditional serialization reconstructs entire objects in memory.
//! For analytics workloads or when you only need a few fields from large objects,
//! this is wasteful. Row format provides O(1) field access without deserialization.
//!
//! **Technical approach:** Fields are encoded in a binary row with fixed offsets for
//! primitives. Variable-length data (strings, collections) are stored with offset
//! pointers. A null bitmap tracks which fields are present. The generated code provides
//! accessor methods that read directly from the binary buffer.
//!
//! **Key benefits:**
//!
//! - **Zero-copy access**: Read fields without allocating or copying data
//! - **Partial deserialization**: Access only the fields you need
//! - **Memory-mapped files**: Work with data larger than RAM
//! - **Cache-friendly**: Sequential memory layout for better CPU cache utilization
//! - **Lazy evaluation**: Defer expensive operations until field access
//!
//! **When to use row format:**
//!
//! - Analytics workloads with selective field access
//! - Large datasets where only a subset of fields is needed
//! - Memory-constrained environments
//! - High-throughput data pipelines
//! - Reading from memory-mapped files or shared memory
//!
//! **Performance characteristics:**
//!
//! | Operation            | Object Format                 | Row Format                      |
//! |----------------------|-------------------------------|---------------------------------|
//! | Full deserialization | Allocates all objects         | Zero allocation                 |
//! | Single field access  | Full deserialization required | Direct offset read (O(1))       |
//! | Memory usage         | Full object graph in memory   | Only accessed fields in memory  |
//! | Suitable for         | Small objects, full access    | Large objects, selective access |
//!
//! ```rust
//! use fory::{to_row, from_row};
//! use fory_derive::ForyRow;
//! use std::collections::BTreeMap;
//!
//! #[derive(ForyRow)]
//! struct UserProfile {
//!     id: i64,
//!     username: String,
//!     email: String,
//!     scores: Vec<i32>,
//!     preferences: BTreeMap<String, String>,
//!     is_active: bool,
//! }
//!
//! # fn main() {
//! let profile = UserProfile {
//!     id: 12345,
//!     username: "alice".to_string(),
//!     email: "alice@example.com".to_string(),
//!     scores: vec![95, 87, 92, 88],
//!     preferences: BTreeMap::from([
//!         ("theme".to_string(), "dark".to_string()),
//!         ("language".to_string(), "en".to_string()),
//!     ]),
//!     is_active: true,
//! };
//!
//! let row_data = to_row(&profile).unwrap();
//! let row = from_row::<UserProfile>(&row_data);
//!
//! assert_eq!(row.id(), 12345);
//! assert_eq!(row.username(), "alice");
//! assert_eq!(row.is_active(), true);
//!
//! let scores = row.scores();
//! assert_eq!(scores.size(), 4);
//! assert_eq!(scores.get(0).unwrap(), 95);
//! # }
//! ```
//!
//! ## Supported Types
//!
//! Apache Fory™ supports a comprehensive type system for maximum flexibility.
//!
//! ### Primitive Types
//!
//! - `bool` - Boolean values
//! - `i8`, `i16`, `i32`, `i64` - Signed integers
//! - `f32`, `f64` - Floating point numbers
//! - `String` - UTF-8 encoded strings
//!
//! ### Collections
//!
//! - `Vec<T>` - Dynamic arrays
//! - `HashMap<K, V>` - Hash-based maps
//! - `BTreeMap<K, V>` - Ordered maps
//! - `HashSet<T>` - Hash-based sets
//! - `Option<T>` - Optional values
//!
//! ### Smart Pointers
//!
//! - `Box<T>` - Heap allocation
//! - `Rc<T>` - Reference counting (shared references tracked automatically)
//! - `Arc<T>` - Thread-safe reference counting (shared references tracked)
//! - `RcWeak<T>` - Weak reference to `Rc<T>` (breaks circular references)
//! - `ArcWeak<T>` - Weak reference to `Arc<T>` (breaks circular references)
//! - `RefCell<T>` - Interior mutability with runtime borrow checking
//! - `Mutex<T>` - Thread-safe interior mutability
//!
//! ### Date and Time (requires `chrono` feature)
//!
//! - `chrono::NaiveDate` - Date without timezone
//! - `chrono::NaiveDateTime` - Timestamp without timezone
//!
//! ### Custom Types
//!
//! - Structs with `#[derive(ForyObject)]` - Object graph serialization
//! - Structs with `#[derive(ForyRow)]` - Row-based serialization
//! - C-style enums with `#[derive(ForyObject)]` - Enum support
//! - Manual `Serializer` implementation - Custom serialization logic
//!
//! ### Trait Objects
//!
//! - `Box<dyn Trait>` - Owned trait objects
//! - `Rc<dyn Trait>` - Reference-counted trait objects
//! - `Arc<dyn Trait>` - Thread-safe reference-counted trait objects
//! - `Rc<dyn Any>` - Runtime type dispatch without custom traits
//! - `Arc<dyn Any>` - Thread-safe runtime type dispatch
//!
//! ## Serialization Modes
//!
//! Apache Fory™ supports two serialization modes to balance between performance
//! and flexibility:
//!
//! ### SchemaConsistent Mode (Default)
//!
//! **When to use:** Maximum performance when schemas are guaranteed to match.
//!
//! **Characteristics:**
//! - Type declarations must match exactly between serialization and deserialization
//! - Smaller payload size (no field names or metadata)
//! - Faster serialization and deserialization
//! - Suitable for monolithic applications or tightly coupled services
//!
//! ```rust
//! use fory::Fory;
//!
//! let fory = Fory::default();
//! ```
//!
//! ### Compatible Mode
//!
//! **When to use:** Schema evolution in distributed systems or microservices.
//!
//! **Characteristics:**
//! - Type declarations can differ between peers
//! - Allows field additions, deletions, and reordering
//! - Larger payload size (includes field names and metadata)
//! - Slightly slower due to metadata processing
//! - Essential for zero-downtime deployments
//!
//! ```rust
//! use fory::Fory;
//!
//! let fory = Fory::default().compatible(true);
//! ```
//!
//! ## Cross-Language Serialization
//!
//! **What it enables:** Seamless data exchange across Java, Python, C++, Go,
//! JavaScript, and Rust implementations.
//!
//! **Why it matters:** Microservices architectures often use multiple languages.
//! Apache Fory™ provides a common binary protocol without IDL files or code generation.
//!
//! **How to enable:**
//!
//! ```rust
//! use fory::Fory;
//! use fory::ForyObject;
//!
//! let mut fory = Fory::default()
//!     .compatible(true)
//!     .xlang(true);
//!
//! #[derive(ForyObject)]
//! struct MyStruct {
//!     field1: i32,
//!     field2: String,
//! }
//!
//! fory.register_by_namespace::<MyStruct>("com.example", "MyStruct");
//! ```
//!
//! **Type registration strategies:**
//!
//! - **ID-based registration**: `fory.register::<T>(id)` - Fastest, requires coordination
//! - **Namespace-based registration**: `fory.register_by_namespace::<T>(namespace, name)` - Automatic cross-language mapping
//!
//! ## Performance Characteristics
//!
//! Apache Fory™ Rust is designed for maximum performance through multiple techniques:
//!
//! **Compile-time code generation:**
//! - Procedural macros generate specialized serialization code
//! - Zero runtime overhead, no reflection
//! - Monomorphization for type-specific optimizations
//!
//! **Zero-copy techniques:**
//! - Row format enables direct memory access
//! - No intermediate object allocation
//! - Memory-mapped file support
//!
//! **Space efficiency:**
//! - Variable-length integer encoding
//! - Reference deduplication (shared objects serialized once)
//! - Compact binary format
//!
//! **Buffer management:**
//! - Pre-allocation based on `fory_reserved_space()` hints
//! - Minimized reallocations
//! - Little-endian layout for modern CPUs
//!
//! ## Error Handling
//!
//! Apache Fory™ uses `Result<T, Error>` for all fallible operations, providing
//! comprehensive error handling:
//!
//! ```rust
//! use fory::{Fory, Error};
//! use fory::ForyObject;
//!
//! #[derive(ForyObject)]
//! struct Data {
//!     value: i32,
//! }
//!
//! fn process_data(bytes: &[u8]) -> Result<Data, Error> {
//!     let mut fory = Fory::default();
//!     fory.register::<Data>(100);
//!
//!     let data: Data = fory.deserialize(bytes)?;
//!     Ok(data)
//! }
//! ```
//!
//! ## Thread Safety
//!
//! `Fory` implements `Send` and `Sync`, so a single instance can be shared across threads
//! (for example via `Arc<Fory>`) for concurrent serialization and deserialization. The
//! internal context pools grow lazily and rely on thread-safe primitives, allowing multiple
//! workers to reuse buffers without additional coordination.
//!
//! ```rust
//! use std::sync::Arc;
//! use std::thread;
//! use fory::Fory;
//! use fory::ForyObject;
//!
//! #[derive(ForyObject, Clone, Copy, Debug)]
//! struct Item {
//!     value: i32,
//! }
//!
//! let mut fory = Fory::default();
//! fory.register::<Item>(1000).unwrap();
//! let fory = Arc::new(fory);
//! let handles: Vec<_> = (0..8)
//!     .map(|i| {
//!         let shared = Arc::clone(&fory);
//!         thread::spawn(move || {
//!             let item = Item { value: i };
//!             shared.serialize(&item).unwrap()
//!         })
//!     })
//!     .collect();
//!
//! for handle in handles {
//!     let bytes = handle.join().unwrap();
//!     let item: Item = fory.deserialize(&bytes).unwrap();
//!     assert!(item.value >= 0);
//! }
//! ```
//!
//! **Best practice:** Perform type registration (e.g., `fory.register::<T>(id)`) before
//! spawning worker threads so metadata is ready, then share the configured instance.
//!
//! ## Examples
//!
//! See the `tests/` directory for comprehensive examples:
//!
//! - `tests/tests/test_complex_struct.rs` - Complex nested structures
//! - `tests/tests/test_rc_arc_trait_object.rs` - Trait object serialization
//! - `tests/tests/test_weak.rs` - Circular reference handling
//! - `tests/tests/test_cross_language.rs` - Cross-language compatibility
//!
//! ## Troubleshooting
//!
//! - **Type registry errors**: Errors such as `TypeId ... not found in type_info registry` mean
//!   the type was never registered with the active `Fory` instance. Ensure every serializable
//!   struct, enum, or trait implementation calls `register::<T>(type_id)` before use, and reuse
//!   the same IDs when deserializing.
//! - **Quick error lookup**: Always prefer the static constructors on
//!   [`fory_core::error::Error`]—for example `Error::type_mismatch`, `Error::invalid_data`, or
//!   `Error::unknown`. They keep diagnostics consistent and allow optional panic-on-error
//!   debugging.
//! - **Panic on error for backtraces**: Set `FORY_PANIC_ON_ERROR=1` (or `true`) together with
//!   `RUST_BACKTRACE=1` while running tests or binaries to panic exactly where an error is
//!   constructed. Unset the variable afterwards so production paths keep returning `Result`.
//! - **Struct field tracing**: Add the `#[fory(debug)]` attribute (or `#[fory(debug = true)]`)
//!   next to `#[derive(ForyObject)]` when you need per-field instrumentation. Once compiled with
//!   debug hooks, call `set_before_write_field_func`, `set_after_write_field_func`,
//!   `set_before_read_field_func`, or
//!   `set_after_read_field_func` from `fory_core::serializer::struct_` to install custom
//!   callbacks, and use `reset_struct_debug_hooks()` to restore defaults.
//! - **Lightweight logging**: If custom callbacks are unnecessary, enable
//!   `ENABLE_FORY_DEBUG_OUTPUT=1` to have the default hook handlers print field-level read/write
//!   events. This is useful for spotting cursor misalignment or unexpected buffer growth.
//! - **Test-time hygiene**: Some integration tests expect `FORY_PANIC_ON_ERROR` to stay unset.
//!   Export it only during focused debugging, and rely on targeted commands such as
//!   `cargo test --features tests -p tests --test <case>` when isolating failures.
//!
//! ## Documentation
//!
//! - **[Protocol Specification](https://fory.apache.org/docs/specification/fory_xlang_serialization_spec)** - Binary protocol details
//! - **[Row Format Specification](https://fory.apache.org/docs/specification/fory_row_format_spec)** - Row format internals
//! - **[Type Mapping](https://fory.apache.org/docs/guide/xlang_type_mapping)** - Cross-language type mappings
//! - **[API Documentation](https://docs.rs/fory)** - Complete API reference
//! - **[GitHub Repository](https://github.com/apache/fory)** - Source code and issue tracking

pub use fory_core::{
    error::Error, fory::Fory, register_trait_type, row::from_row, row::to_row, types::TypeId,
    ArcWeak, ForyDefault, RcWeak, ReadContext, Reader, Serializer, TypeResolver, WriteContext,
    Writer,
};
pub use fory_derive::{ForyObject, ForyRow};
