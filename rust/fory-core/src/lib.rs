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

//! # Fory Core
//!
//! This is the core implementation of the Fory serialization framework.
//! It provides the fundamental building blocks for high-performance serialization
//! and deserialization in Rust.
//!
//! ## Architecture
//!
//! The core library is organized into several key modules:
//!
//! - **`fory`**: Main serialization engine and public API
//! - **`buffer`**: Efficient binary buffer management with Reader/Writer
//! - **`row`**: Row-based serialization for zero-copy operations
//! - **`serializer`**: Type-specific serialization implementations
//! - **`resolver`**: Type resolution and metadata management
//! - **`meta`**: Metadata handling for schema evolution
//! - **`types`**: Core type definitions and constants
//! - **`error`**: Error handling and result types
//! - **`util`**: Utility functions and helpers
//!
//! ## Key Concepts
//!
//! ### Serialization Modes
//!
//! Fory supports two serialization modes:
//!
//! - **SchemaConsistent**: Requires exact type matching between peers
//! - **Compatible**: Allows schema evolution with field additions/deletions
//!
//! ### Type System
//!
//! The framework uses a comprehensive type system that supports:
//! - Primitive types (bool, integers, floats, strings)
//! - Collections (Vec, HashMap, BTreeMap)
//! - Optional types (`Option<T>`)
//! - Date/time types (chrono integration)
//! - Custom structs and enums
//!
//! ### Performance Optimizations
//!
//! - **Zero-copy deserialization** in row mode
//! - **Buffer pre-allocation** to minimize allocations
//! - **Variable-length encoding** for compact representation
//! - **Little-endian byte order** for cross-platform compatibility
//!
//! ## Usage
//!
//! This crate is typically used through the higher-level `fory` crate,
//! which provides derive macros and a more convenient API. However,
//! you can use the core types directly for advanced use cases.
//!
//! ```rust
//! use fory_core::{fory::Fory, error::Error, types::Mode};
//! use fory_core::row::{to_row, from_row};
//!
//! // Create a Fory instance
//! let mut fory = Fory::default().mode(Mode::Compatible);
//!
//! // Register types for object serialization
//! // fory.register::<MyStruct>(type_id);
//!
//! // Use row-based serialization for zero-copy operations
//! // let row_data = to_row(&my_data);
//! // let row = from_row::<MyStruct>(&row_data);
//! ```

pub mod buffer;
pub mod error;
pub mod fory;
pub mod meta;
pub mod resolver;
pub mod row;
pub mod serializer;
pub mod types;
pub mod util;
