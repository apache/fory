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

//! # Fory
//!
//! Fory is a blazingly fast multi-language serialization framework powered by
//! just-in-time compilation and zero-copy techniques. It provides two main
//! serialization approaches: object serialization for complex data structures
//! and row-based serialization for high-performance scenarios.
//!
//! ## Key Features
//!
//! - **High Performance**: Optimized for speed with zero-copy deserialization
//! - **Cross-Language**: Designed for multi-language environments
//! - **Two Serialization Modes**: Object serialization and row-based serialization
//! - **Type Safety**: Compile-time type checking with derive macros
//! - **Schema Evolution**: Support for compatible mode with field additions/deletions
//!
//! ## Serialization Modes
//!
//! Fory provides two distinct serialization approaches:
//!
//! ### 1. Object Serialization
//!
//! Object serialization is designed for complex data structures and provides
//! full object graph serialization with reference handling. This mode is
//! ideal for general-purpose serialization needs.
//!
//! ```rust
//! use fory::{Fory, Error};
//! use fory_derive::ForyObject;
//! use std::collections::HashMap;
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
//!         ("department".to_string(), "engineering".to_string()),
//!         ("level".to_string(), "senior".to_string()),
//!     ]),
//! };
//!
//! // Create a Fory instance and register types
//! let mut fory = Fory::default();
//! fory.register::<Address>(100);
//! fory.register::<Person>(200);
//!
//! // Serialize the object
//! let serialized = fory.serialize(&person);
//!
//! // Deserialize back to the original type
//! let deserialized: Person = fory.deserialize(&serialized)?;
//!
//! assert_eq!(person, deserialized);
//! # Ok(())
//! # }
//! ```
//!
//! ### 2. Row-Based Serialization
//!
//! Row-based serialization provides zero-copy deserialization for maximum
//! performance. This mode is ideal for high-throughput scenarios where you
//! need to process large amounts of data efficiently.
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
//! // Serialize to row format
//! let row_data = to_row(&profile);
//!
//! // Deserialize with zero-copy access
//! let row = from_row::<UserProfile>(&row_data);
//!
//! // Access fields directly from the row data
//! assert_eq!(row.id(), 12345);
//! assert_eq!(row.username(), "alice");
//! assert_eq!(row.email(), "alice@example.com");
//! assert_eq!(row.is_active(), true);
//!
//! // Access collections efficiently
//! let scores = row.scores();
//! assert_eq!(scores.size(), 4);
//! assert_eq!(scores.get(0), 95);
//! assert_eq!(scores.get(1), 87);
//!
//! let prefs = row.preferences();
//! assert_eq!(prefs.keys().size(), 2);
//! assert_eq!(prefs.keys().get(0), "language");
//! assert_eq!(prefs.values().get(0), "en");
//! ```
//!
//! ## Supported Types
//!
//! Fory supports a wide range of Rust types:
//!
//! ### Primitive Types
//! - `bool`, `i8`, `i16`, `i32`, `i64`, `f32`, `f64`
//! - `String`, `&str` (in row format)
//! - `Vec<u8>` for binary data
//!
//! ### Collections
//! - `Vec<T>` for arrays/lists
//! - `HashMap<K, V>` and `BTreeMap<K, V>` for maps
//! - `Option<T>` for nullable values
//!
//! ### Date and Time
//! - `chrono::NaiveDate` for dates
//! - `chrono::NaiveDateTime` for timestamps
//!
//! ### Custom Types
//! - Structs with `#[derive(ForyObject)]` or `#[derive(ForyRow)]`
//! - Enums with `#[derive(ForyObject)]`
//!
//! ## Serialization Modes
//!
//! Fory supports two serialization modes:
//!
//! - **SchemaConsistent**: Type declarations must be consistent between
//!   serialization and deserialization peers (default)
//! - **Compatible**: Type declarations can differ between peers, allowing
//!   independent field additions/deletions
//!
//! ```rust
//! use fory::Fory;
//! use fory_core::types::Mode;
//! use fory_derive::ForyObject;
//!
//! #[derive(ForyObject, Debug)]
//! struct Config {
//!     name: String,
//!     value: i32,
//! }
//!
//! let mut fory = Fory::default().mode(Mode::Compatible);
//! fory.register::<Config>(100);
//! // ... use fory for serialization
//! ```
//!
//! ## Error Handling
//!
//! Fory provides comprehensive error handling through the `Error` type:
//!
//! ```rust
//! use fory::{Fory, Error};
//! use fory_derive::ForyObject;
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
//!     // This can fail if the data is corrupted or type mismatches
//!     let data: Data = fory.deserialize(bytes)?;
//!     Ok(data)
//! }
//! ```
//!
//! ## Performance Considerations
//!
//! - **Object Serialization**: Best for complex object graphs with references
//! - **Row Serialization**: Best for high-throughput, zero-copy scenarios
//! - **Type Registration**: Register all types before serialization for optimal performance
//! - **Buffer Pre-allocation**: Fory automatically reserves space to minimize allocations
//!
//! ## Cross-Language Compatibility
//!
//! Fory is designed to work across multiple programming languages, making it
//! ideal for microservices architectures and distributed systems where different
//! services may be implemented in different languages.
//!
//! ## Getting Started
//!
//! Add Fory to your `Cargo.toml`:
//!
//! ```toml
//! [dependencies]
//! fory = "0.1"
//! fory-derive = "0.1"
//! chrono = "0.4"
//! ```
//!
//! Then use the derive macros to make your types serializable:
//!
//! ```rust
//! use fory_derive::{ForyObject, ForyRow};
//!
//! #[derive(ForyObject)]        // For object serialization
//! #[derive(ForyRow)]     // For row-based serialization
//! struct MyData {
//!     field1: String,
//!     field2: i32,
//! }
//! ```

pub use fory_core::{
    error::Error, fory::Fory, register_trait_type, row::from_row, row::to_row, types::Mode,
    types::TypeId, ArcWeak, RcWeak,
};
pub use fory_derive::{ForyObject, ForyRow};
