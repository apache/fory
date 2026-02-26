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

use crate::buffer::{Reader, Writer};
use crate::config::Config;
use crate::ensure;
use crate::error::Error;
use crate::resolver::context::{ContextCache, ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::ForyDefault;
use crate::serializer::{Serializer, StructSerializer};
use crate::types::config_flags::{IS_CROSS_LANGUAGE_FLAG, IS_NULL_FLAG};
use crate::types::{RefMode, SIZE_OF_REF_AND_TYPE};
use std::cell::UnsafeCell;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::OnceLock;

/// Global counter to assign unique IDs to each Fory instance.
static FORY_ID_COUNTER: AtomicU64 = AtomicU64::new(0);

thread_local! {
    /// Thread-local storage for WriteContext instances with fast path caching.
    static WRITE_CONTEXTS: UnsafeCell<ContextCache<WriteContext<'static>>> =
        UnsafeCell::new(ContextCache::new());

    /// Thread-local storage for ReadContext instances with fast path caching.
    static READ_CONTEXTS: UnsafeCell<ContextCache<ReadContext<'static>>> =
        UnsafeCell::new(ContextCache::new());
}

/// The main Fory serialization framework instance.
///
/// `Fory` provides high-performance cross-language serialization and deserialization
/// capabilities with support for multiple modes, reference tracking, and trait object serialization.
///
/// # Features
///
/// - **Cross-language serialization**: Serialize data in Rust and deserialize in other languages
/// - **Multiple modes**: Schema-consistent and compatible serialization modes
/// - **Reference tracking**: Handles shared and circular references
/// - **Trait object serialization**: Supports serializing polymorphic trait objects
/// - **Dynamic depth limiting**: Configurable limit for nested dynamic object serialization
///
/// # Examples
///
/// Basic usage:
///
/// ```rust, ignore
/// use fory::Fory;
/// use fory::ForyObject;
///
/// #[derive(ForyObject)]
/// struct User {
///     name: String,
///     age: u32,
/// }
///
/// let fory = Fory::default();
/// let user = User { name: "Alice".to_string(), age: 30 };
/// let bytes = fory.serialize(&user);
/// let deserialized: User = fory.deserialize(&bytes).unwrap();
/// ```
///
/// Custom configuration:
///
/// ```rust
/// use fory_core::Fory;
///
/// let fory = Fory::default()
///     .compatible(true)
///     .compress_string(true)
///     .max_dyn_depth(10);
/// ```
pub struct Fory {
    /// Unique identifier for this Fory instance, used as key in thread-local context maps.
    id: u64,
    /// Configuration for serialization behavior.
    config: Config,
    type_resolver: TypeResolver,
    /// Lazy-initialized final type resolver (thread-safe, one-time initialization).
    final_type_resolver: OnceLock<Result<TypeResolver, Error>>,
}

impl Default for Fory {
    fn default() -> Self {
        Fory {
            id: FORY_ID_COUNTER.fetch_add(1, Ordering::Relaxed),
            config: Config::default(),
            type_resolver: TypeResolver::default(),
            final_type_resolver: OnceLock::new(),
        }
    }
}

impl Fory {
    #[inline(always)]
    unsafe fn writer_from_raw_buffer(buffer: *mut Vec<u8>) -> Writer<'static> {
        // SAFETY: Caller must ensure `buffer` remains valid while the returned writer is in use.
        Writer::from_buffer(unsafe { &mut *buffer })
    }

    #[inline(always)]
    unsafe fn reader_from_raw_parts(ptr: *const u8, len: usize) -> Reader<'static> {
        // SAFETY: Caller must ensure `[ptr, ptr + len)` remains valid while the returned reader is in use.
        Reader::new(unsafe { std::slice::from_raw_parts(ptr, len) })
    }

    /// Sets the serialization compatible mode for this Fory instance.
    ///
    /// # Arguments
    ///
    /// * `compatible` - The serialization compatible mode to use. Options are:
    ///   - `false`: Schema must be consistent between serialization and deserialization.
    ///     No metadata is shared. This is the fastest mode.
    ///   - true`: Supports schema evolution and type metadata sharing for better
    ///     cross-version compatibility.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Note
    ///
    /// Setting the compatible mode also automatically configures the `share_meta` flag:
    /// - `false` → `share_meta = false`
    /// - true` → `share_meta = true`
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// let fory = Fory::default().compatible(true);
    /// ```
    pub fn compatible(mut self, compatible: bool) -> Self {
        // Setting share_meta individually is not supported currently
        self.config.share_meta = compatible;
        self.config.compatible = compatible;
        self.type_resolver.set_compatible(compatible);
        if compatible {
            self.config.check_struct_version = false;
        }
        self
    }

    /// Enables or disables cross-language serialization protocol.
    ///
    /// # Arguments
    ///
    /// * `xlang` - If `true`, uses the cross-language serialization format compatible with
    ///   other Fory implementations (Java, Python, C++, etc.). If `false`, uses a Rust-only
    ///   optimized format.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Default
    ///
    /// The default value is `false`.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// // For cross-language use (default)
    /// let fory = Fory::default().xlang(true);
    ///
    /// // For Rust-only optimization, this mode is faster and more compact since it avoids
    /// // cross-language metadata and type system costs.
    /// let fory = Fory::default().xlang(false);
    /// ```
    pub fn xlang(mut self, xlang: bool) -> Self {
        self.config.xlang = xlang;
        if !self.config.check_struct_version {
            self.config.check_struct_version = !self.config.compatible;
        }
        self.type_resolver.set_xlang(xlang);
        self
    }

    /// Enables or disables meta string compression.
    ///
    /// # Arguments
    ///
    /// * `compress_string` - If `true`, enables meta string compression to reduce serialized
    ///   payload size by deduplicating and encoding frequently used strings (such as type names
    ///   and field names). If `false`, strings are serialized without compression.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Default
    ///
    /// The default value is `false`.
    ///
    /// # Trade-offs
    ///
    /// - **Enabled**: Smaller payload size, slightly higher CPU overhead
    /// - **Disabled**: Larger payload size, faster serialization/deserialization
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// let fory = Fory::default().compress_string(true);
    /// ```
    pub fn compress_string(mut self, compress_string: bool) -> Self {
        self.config.compress_string = compress_string;
        self
    }

    /// Enables or disables class version checking for schema consistency.
    ///
    /// # Arguments
    ///
    /// * `check_struct_version` - If `true`, enables class version checking to ensure
    ///   schema consistency between serialization and deserialization. When enabled,
    ///   a version hash computed from field types is written/read to detect schema mismatches.
    ///   If `false`, no version checking is performed.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Default
    ///
    /// The default value is `false`.
    ///
    /// # Note
    ///
    /// This feature is only effective when `compatible` mode is `false`. In compatible mode,
    /// schema evolution is supported and version checking is not needed.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// let fory = Fory::default()
    ///     .compatible(false)
    ///     .check_struct_version(true);
    /// ```
    pub fn check_struct_version(mut self, check_struct_version: bool) -> Self {
        if self.config.compatible && check_struct_version {
            // ignore setting if compatible mode is on
            return self;
        }
        self.config.check_struct_version = check_struct_version;
        self
    }

    /// Enables or disables reference tracking for shared and circular references.
    ///
    /// # Arguments
    ///
    /// * `track_ref` - If `true`, enables reference tracking which allows
    ///   preserving shared object references and circular references during
    ///   serialization/deserialization.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Default
    ///
    /// The default value is `false`.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// let fory = Fory::default().track_ref(true);
    /// ```
    pub fn track_ref(mut self, track_ref: bool) -> Self {
        self.config.track_ref = track_ref;
        self
    }

    /// Sets the maximum depth for nested dynamic object serialization.
    ///
    /// # Arguments
    ///
    /// * `max_dyn_depth` - The maximum nesting depth allowed for dynamically-typed objects
    ///   (e.g., trait objects, boxed types). This prevents stack overflow from deeply nested
    ///   structures in dynamic serialization scenarios.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Default
    ///
    /// The default value is `5`.
    ///
    /// # Behavior
    ///
    /// When the depth limit is exceeded during deserialization, an error is returned to prevent
    /// potential stack overflow or infinite recursion.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// // Allow deeper nesting for complex object graphs
    /// let fory = Fory::default().max_dyn_depth(10);
    ///
    /// // Restrict nesting for safer deserialization
    /// let fory = Fory::default().max_dyn_depth(3);
    /// ```
    pub fn max_dyn_depth(mut self, max_dyn_depth: u32) -> Self {
        self.config.max_dyn_depth = max_dyn_depth;
        self
    }

    /// Returns whether cross-language serialization is enabled.
    pub fn is_xlang(&self) -> bool {
        self.config.xlang
    }

    /// Returns the current serialization mode.
    ///
    /// # Returns
    ///
    /// `true` if the serialization mode is compatible, `false` otherwise`.
    pub fn is_compatible(&self) -> bool {
        self.config.compatible
    }

    /// Returns whether string compression is enabled.
    ///
    /// # Returns
    ///
    /// `true` if meta string compression is enabled, `false` otherwise.
    pub fn is_compress_string(&self) -> bool {
        self.config.compress_string
    }

    /// Returns whether metadata sharing is enabled.
    ///
    /// # Returns
    ///
    /// `true` if metadata sharing is enabled (automatically set based on mode), `false` otherwise.
    pub fn is_share_meta(&self) -> bool {
        self.config.share_meta
    }

    /// Returns the maximum depth for nested dynamic object serialization.
    pub fn get_max_dyn_depth(&self) -> u32 {
        self.config.max_dyn_depth
    }

    /// Returns whether class version checking is enabled.
    ///
    /// # Returns
    ///
    /// `true` if class version checking is enabled, `false` otherwise.
    pub fn is_check_struct_version(&self) -> bool {
        self.config.check_struct_version
    }

    /// Returns a reference to the configuration.
    pub fn config(&self) -> &Config {
        &self.config
    }

    /// Serializes a value of type `T` into a byte vector.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The type of the value to serialize. Must implement `Serializer`.
    ///
    /// # Arguments
    ///
    /// * `record` - A reference to the value to serialize.
    ///
    /// # Returns
    ///
    /// A `Vec<u8>` containing the serialized data.
    ///
    /// # Examples
    ///
    /// ```rust, ignore
    /// use fory::Fory;
    /// use fory::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct Point { x: i32, y: i32 }
    ///
    /// let fory = Fory::default();
    /// let point = Point { x: 10, y: 20 };
    /// let bytes = fory.serialize(&point);
    /// ```
    pub fn serialize<T: Serializer>(&self, record: &T) -> Result<Vec<u8>, Error> {
        self.with_write_context(|context| {
            context.detach_writer_if_attached();
            match self.serialize_with_context(record, context) {
                Ok(_) => {
                    let result = context.writer.dump();
                    context.writer.reset();
                    Ok(result)
                }
                Err(err) => {
                    context.writer.reset();
                    Err(err)
                }
            }
        })
    }

    /// Serializes a value of type `T` into the provided byte buffer.
    ///
    /// The serialized data is appended to the end of the buffer by default.
    /// To write from a specific position, resize the buffer before calling this method.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The type of the value to serialize. Must implement `Serializer`.
    ///
    /// # Arguments
    ///
    /// * `buf` - A mutable reference to the byte buffer to append the serialized data to.
    ///   The buffer will be resized as needed during serialization.
    /// * `record` - A reference to the value to serialize.
    ///
    /// # Returns
    ///
    /// The number of bytes written to the buffer on success, or an error if serialization fails.
    ///
    /// # Notes
    ///
    /// - Multiple `serialize_to` calls to the same buffer will append data sequentially.
    ///
    /// # Examples
    ///
    /// Basic usage - appending to a buffer:
    ///
    /// ```rust, ignore
    /// use fory_core::Fory;
    /// use fory_derive::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct Point {
    ///     x: i32,
    ///     y: i32,
    /// }
    ///
    /// let fory = Fory::default();
    /// let point = Point { x: 1, y: 2 };
    ///
    /// let mut buf = Vec::new();
    /// let bytes_written = fory.serialize_to(&mut buf, &point).unwrap();
    /// assert_eq!(bytes_written, buf.len());
    /// ```
    ///
    /// Multiple serializations to the same buffer:
    ///
    /// ```rust, ignore
    /// use fory_core::Fory;
    /// use fory_derive::ForyObject;
    ///
    /// #[derive(ForyObject, PartialEq, Debug)]
    /// struct Point {
    ///     x: i32,
    ///     y: i32,
    /// }
    ///
    /// let fory = Fory::default();
    /// let p1 = Point { x: 1, y: 2 };
    /// let p2 = Point { x: -3, y: 4 };
    ///
    /// let mut buf = Vec::new();
    ///
    /// // First serialization
    /// let len1 = fory.serialize_to(&mut buf, &p1).unwrap();
    /// let offset1 = buf.len();
    ///
    /// // Second serialization - appends to existing data
    /// let len2 = fory.serialize_to(&mut buf, &p2).unwrap();
    /// let offset2 = buf.len();
    ///
    /// assert_eq!(offset1, len1);
    /// assert_eq!(offset2, len1 + len2);
    ///
    /// // Deserialize both objects
    /// let deserialized1: Point = fory.deserialize(&buf[0..offset1]).unwrap();
    /// let deserialized2: Point = fory.deserialize(&buf[offset1..offset2]).unwrap();
    /// assert_eq!(deserialized1, p1);
    /// assert_eq!(deserialized2, p2);
    /// ```
    ///
    /// Writing to a specific position using `resize`:
    /// # Notes on `vec.resize()`
    ///
    /// When calling `vec.resize(n, 0)`, note that if `n` is smaller than the current length,
    /// the buffer will be truncated (not shrunk in capacity). The capacity remains unchanged,
    /// making subsequent writes efficient for buffer reuse patterns:
    ///
    /// ```rust, ignore
    /// use fory_core::Fory;
    /// use fory_derive::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct Point {
    ///     x: i32,
    ///     y: i32,
    /// }
    ///
    /// let fory = Fory::default();
    /// let point = Point { x: 1, y: 2 };
    ///
    /// let mut buf = Vec::with_capacity(1024);
    /// buf.resize(16, 0);  // Set length to 16 to append the write, capacity stays 1024
    ///
    /// let initial_capacity = buf.capacity();
    /// fory.serialize_to(&mut buf, &point).unwrap();
    ///
    /// // Reset to smaller size to append the write - capacity unchanged
    /// buf.resize(16, 0);
    /// assert_eq!(buf.capacity(), initial_capacity);  // Capacity not shrunk
    ///
    /// // Reuse buffer efficiently without reallocation
    /// fory.serialize_to(&mut buf, &point).unwrap();
    /// assert_eq!(buf.capacity(), initial_capacity);  // Still no reallocation
    /// ```
    pub fn serialize_to<T: Serializer>(
        &self,
        buf: &mut Vec<u8>,
        record: &T,
    ) -> Result<usize, Error> {
        let start = buf.len();
        self.with_write_context(|context| {
            let writer = unsafe { Self::writer_from_raw_buffer(buf as *mut Vec<u8>) };
            context.attach_writer(writer);
            let result = self.serialize_with_context(record, context);
            let written_size = context.writer.len() - start;
            context.detach_writer();
            match result {
                Ok(_) => Ok(written_size),
                Err(err) => Err(err),
            }
        })
    }

    /// Gets the final type resolver, building it lazily on first access.
    #[inline(always)]
    fn get_final_type_resolver(&self) -> Result<&TypeResolver, Error> {
        let result = self
            .final_type_resolver
            .get_or_init(|| self.type_resolver.build_final_type_resolver());
        result
            .as_ref()
            .map_err(|e| Error::type_error(format!("Failed to build type resolver: {}", e)))
    }

    /// Executes a closure with mutable access to a WriteContext for this Fory instance.
    /// The context is stored in thread-local storage, eliminating all lock contention.
    /// Uses fast path caching for O(1) access when using the same Fory instance repeatedly.
    #[inline(always)]
    fn with_write_context<R>(
        &self,
        f: impl FnOnce(&mut WriteContext) -> Result<R, Error>,
    ) -> Result<R, Error> {
        // SAFETY: Thread-local storage is only accessed from the current thread.
        // We use UnsafeCell to avoid RefCell's runtime borrow checking overhead.
        // The closure `f` does not recursively call with_write_context, so there's no aliasing.
        WRITE_CONTEXTS.with(|cache| {
            let cache = unsafe { &mut *cache.get() };
            let id = self.id;
            let config = self.config.clone();

            let context = cache.get_or_insert_result(id, || {
                // Only fetch type resolver when creating a new context
                let type_resolver = self.get_final_type_resolver()?;
                Ok(Box::new(WriteContext::new(type_resolver.clone(), config)))
            })?;
            f(context)
        })
    }

    /// Serializes a value of type `T` into a byte vector.
    #[inline(always)]
    fn serialize_with_context<T: Serializer>(
        &self,
        record: &T,
        context: &mut WriteContext,
    ) -> Result<(), Error> {
        context.reset();
        self.serialize_with_context_inner::<T>(record, context)
    }

    #[inline(always)]
    fn serialize_with_context_inner<T: Serializer>(
        &self,
        record: &T,
        context: &mut WriteContext,
    ) -> Result<(), Error> {
        let is_none = record.fory_is_none();
        self.write_head::<T>(is_none, &mut context.writer);
        if !is_none {
            // Use RefMode based on config:
            // - If track_ref is enabled, use RefMode::Tracking for the root object
            // - Otherwise, use RefMode::NullOnly which writes NOT_NULL_VALUE_FLAG
            let ref_mode = if self.config.track_ref {
                RefMode::Tracking
            } else {
                RefMode::NullOnly
            };
            // TypeMeta is written inline during serialization (streaming protocol)
            <T as Serializer>::fory_write(record, context, ref_mode, true, false)?;
        }
        Ok(())
    }

    /// Registers a struct type with a numeric type ID for serialization.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The struct type to register. Must implement `StructSerializer`, `Serializer`, and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `id` - A unique numeric identifier for the type. This ID is used in the serialized format
    ///   to identify the type during deserialization.
    ///
    /// # Panics
    ///
    /// May panic if the type ID conflicts with an already registered type.
    ///
    /// # Examples
    ///
    /// ```rust, ignore
    /// use fory::Fory;
    /// use fory::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct User { name: String, age: u32 }
    ///
    /// let mut fory = Fory::default();
    /// fory.register::<User>(100);
    /// ```
    pub fn register<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.type_resolver.register_by_id::<T>(id)
    }

    /// Register a union type with a numeric type ID.
    ///
    /// This is intended for union-compatible enums generated by the compiler.
    pub fn register_union<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.type_resolver.register_union_by_id::<T>(id)
    }

    /// Registers a struct type with a namespace and type name for cross-language serialization.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The struct type to register. Must implement `StructSerializer`, `Serializer`, and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `namespace` - The namespace or package name for the type (e.g., "com.example.types").
    ///   Use an empty string for the default namespace.
    /// * `type_name` - The name of the type (e.g., "User").
    ///
    /// # Notes
    ///
    /// This registration method is preferred for cross-language serialization as it uses
    /// human-readable type identifiers instead of numeric IDs, which improves compatibility
    /// across different language implementations.
    ///
    /// # Examples
    ///
    /// ```rust, ignore
    /// use fory::Fory;
    /// use fory::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct User { name: String, age: u32 }
    ///
    /// let mut fory = Fory::default();
    /// fory.register_by_namespace::<User>("com.example", "User");
    /// ```
    pub fn register_by_namespace<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        self.type_resolver
            .register_by_namespace::<T>(namespace, type_name)
    }

    /// Register a union type with namespace and type name.
    ///
    /// This is intended for union-compatible enums generated by the compiler.
    pub fn register_union_by_namespace<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        self.type_resolver
            .register_union_by_namespace::<T>(namespace, type_name)
    }

    /// Registers a struct type with a type name (using the default namespace).
    ///
    /// # Type Parameters
    ///
    /// * `T` - The struct type to register. Must implement `StructSerializer`, `Serializer`, and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `type_name` - The name of the type (e.g., "User").
    ///
    /// # Notes
    ///
    /// This is a convenience method that calls `register_by_namespace` with an empty namespace string.
    ///
    /// # Examples
    ///
    /// ```rust, ignore
    /// use fory::Fory;
    /// use fory::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct User { name: String, age: u32 }
    ///
    /// let mut fory = Fory::default();
    /// fory.register_by_name::<User>("User");
    /// ```
    pub fn register_by_name<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        type_name: &str,
    ) -> Result<(), Error> {
        self.register_by_namespace::<T>("", type_name)
    }

    /// Register a union type with type name only (no namespace).
    pub fn register_union_by_name<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        type_name: &str,
    ) -> Result<(), Error> {
        self.register_union_by_namespace::<T>("", type_name)
    }

    /// Registers a custom serializer type with a numeric type ID.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The type to register. Must implement `Serializer` and `ForyDefault`.
    ///   Unlike `register()`, this does not require `StructSerializer`, making it suitable
    ///   for non-struct types or types with custom serialization logic.
    ///
    /// # Arguments
    ///
    /// * `id` - A unique numeric identifier for the type.
    ///
    /// # Use Cases
    ///
    /// Use this method to register:
    /// - Enum types with custom serialization
    /// - Wrapper types
    /// - Types with hand-written `Serializer` implementations
    ///
    /// # Examples
    ///
    /// ```rust, ignore
    /// use fory_core::Fory;
    ///
    /// let mut fory = Fory::default();
    /// fory.register_serializer::<MyCustomType>(200);
    /// ```
    pub fn register_serializer<T: Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.type_resolver.register_serializer_by_id::<T>(id)
    }

    /// Registers a custom serializer type with a namespace and type name.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The type to register. Must implement `Serializer` and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `namespace` - The namespace or package name for the type.
    /// * `type_name` - The name of the type.
    ///
    /// # Notes
    ///
    /// This is the namespace-based equivalent of `register_serializer()`, preferred for
    /// cross-language serialization scenarios.
    ///
    pub fn register_serializer_by_namespace<T: Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) -> Result<(), Error> {
        self.type_resolver
            .register_serializer_by_namespace::<T>(namespace, type_name)
    }

    /// Registers a custom serializer type with a type name (using the default namespace).
    ///
    /// # Type Parameters
    ///
    /// * `T` - The type to register. Must implement `Serializer` and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `type_name` - The name of the type.
    ///
    /// # Notes
    ///
    /// This is a convenience method that calls `register_serializer_by_namespace` with an empty namespace.
    pub fn register_serializer_by_name<T: Serializer + ForyDefault>(
        &mut self,
        type_name: &str,
    ) -> Result<(), Error> {
        self.register_serializer_by_namespace::<T>("", type_name)
    }

    /// Registers a generic trait object type for serialization.
    /// This method should be used to register collection types such as `Vec<T>`, `HashMap<K, V>`, etc.
    /// Don't register concrete struct types with this method. Use `register()` instead.
    pub fn register_generic_trait<T: 'static + Serializer + ForyDefault>(
        &mut self,
    ) -> Result<(), Error> {
        self.type_resolver.register_generic_trait::<T>()
    }

    /// Writes the serialization header to the writer.
    #[inline(always)]
    pub fn write_head<T: Serializer>(&self, is_none: bool, writer: &mut Writer) {
        const HEAD_SIZE: usize = 10;
        writer.reserve(T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE + HEAD_SIZE);
        let mut bitmap: u8 = 0;
        if self.config.xlang {
            bitmap |= IS_CROSS_LANGUAGE_FLAG;
        }
        if is_none {
            bitmap |= IS_NULL_FLAG;
        }
        writer.write_u8(bitmap);
    }

    /// Deserializes data from a byte slice into a value of type `T`.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The target type to deserialize into. Must implement `Serializer` and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `bf` - The byte slice containing the serialized data.
    ///
    /// # Returns
    ///
    /// * `Ok(T)` - The deserialized value on success.
    /// * `Err(Error)` - An error if deserialization fails (e.g., invalid format, type mismatch).
    ///
    /// # Panics
    ///
    /// Panics in debug mode if there are unread bytes remaining after successful deserialization,
    /// indicating a potential protocol violation.
    ///
    /// # Examples
    ///
    /// ```rust, ignore
    /// use fory::Fory;
    /// use fory::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct Point { x: i32, y: i32 }
    ///
    /// let fory = Fory::default();
    /// let point = Point { x: 10, y: 20 };
    /// let bytes = fory.serialize(&point);
    /// let deserialized: Point = fory.deserialize(&bytes).unwrap();
    /// ```
    pub fn deserialize<T: Serializer + ForyDefault>(&self, bf: &[u8]) -> Result<T, Error> {
        self.with_read_context(|context| {
            let reader = unsafe { Self::reader_from_raw_parts(bf.as_ptr(), bf.len()) };
            context.attach_reader(reader);
            let result = self.deserialize_with_context(context);
            context.detach_reader();
            result
        })
    }

    /// Deserializes data from a `Reader` into a value of type `T`.
    ///
    /// This method is the paired read operation for [`serialize_to`](Self::serialize_to).
    /// It reads serialized data from the current position of the reader and automatically
    /// advances the cursor to the end of the read data, making it suitable for reading
    /// multiple objects sequentially from the same buffer.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The target type to deserialize into. Must implement `Serializer` and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `reader` - A mutable reference to the `Reader` containing the serialized data.
    ///   The reader's cursor will be advanced to the end of the deserialized data.
    ///
    /// # Returns
    ///
    /// * `Ok(T)` - The deserialized value on success.
    /// * `Err(Error)` - An error if deserialization fails (e.g., invalid format, type mismatch).
    ///
    /// # Notes
    ///
    /// - The reader's cursor is automatically updated after each successful read.
    /// - This method is ideal for reading multiple objects from the same buffer sequentially.
    /// - See [`serialize_to`](Self::serialize_to) for complete usage examples.
    ///
    /// # Examples
    ///
    /// Basic usage:
    ///
    /// ```rust, ignore
    /// use fory_core::{Fory, Reader};
    /// use fory_derive::ForyObject;
    ///
    /// #[derive(ForyObject)]
    /// struct Point { x: i32, y: i32 }
    ///
    /// let fory = Fory::default();
    /// let point = Point { x: 10, y: 20 };
    ///
    /// let mut buf = Vec::new();
    /// fory.serialize_to(&point, &mut buf).unwrap();
    ///
    /// let mut reader = Reader::new(&buf);
    /// let deserialized: Point = fory.deserialize_from(&mut reader).unwrap();
    /// ```
    pub fn deserialize_from<T: Serializer + ForyDefault>(
        &self,
        reader: &mut Reader,
    ) -> Result<T, Error> {
        self.with_read_context(|context| {
            let mut new_reader =
                unsafe { Self::reader_from_raw_parts(reader.bf.as_ptr(), reader.bf.len()) };
            new_reader.set_cursor(reader.cursor);
            context.attach_reader(new_reader);
            let result = self.deserialize_with_context(context);
            let end = context.detach_reader().get_cursor();
            reader.set_cursor(end);
            result
        })
    }

    /// Executes a closure with mutable access to a ReadContext for this Fory instance.
    /// The context is stored in thread-local storage, eliminating all lock contention.
    /// Uses fast path caching for O(1) access when using the same Fory instance repeatedly.
    #[inline(always)]
    fn with_read_context<R>(
        &self,
        f: impl FnOnce(&mut ReadContext) -> Result<R, Error>,
    ) -> Result<R, Error> {
        // SAFETY: Thread-local storage is only accessed from the current thread.
        // We use UnsafeCell to avoid RefCell's runtime borrow checking overhead.
        // The closure `f` does not recursively call with_read_context, so there's no aliasing.
        READ_CONTEXTS.with(|cache| {
            let cache = unsafe { &mut *cache.get() };
            let id = self.id;
            let config = self.config.clone();

            let context = cache.get_or_insert_result(id, || {
                // Only fetch type resolver when creating a new context
                let type_resolver = self.get_final_type_resolver()?;
                Ok(Box::new(ReadContext::new(type_resolver.clone(), config)))
            })?;
            f(context)
        })
    }

    #[inline(always)]
    fn deserialize_with_context<T: Serializer + ForyDefault>(
        &self,
        context: &mut ReadContext,
    ) -> Result<T, Error> {
        context.reset();
        self.deserialize_with_context_inner::<T>(context)
    }

    #[inline(always)]
    fn deserialize_with_context_inner<T: Serializer + ForyDefault>(
        &self,
        context: &mut ReadContext,
    ) -> Result<T, Error> {
        let is_none = self.read_head(&mut context.reader)?;
        if is_none {
            return Ok(T::fory_default());
        }
        // Use RefMode based on config:
        // - If track_ref is enabled, use RefMode::Tracking for the root object
        // - Otherwise, use RefMode::NullOnly
        let ref_mode = if self.config.track_ref {
            RefMode::Tracking
        } else {
            RefMode::NullOnly
        };
        // TypeMeta is read inline during deserialization (streaming protocol)
        let result = <T as Serializer>::fory_read(context, ref_mode, true);
        context.ref_reader.resolve_callbacks();
        result
    }

    #[inline(always)]
    fn read_head(&self, reader: &mut Reader) -> Result<bool, Error> {
        let bitmap = reader.read_u8()?;
        let peer_is_xlang = (bitmap & IS_CROSS_LANGUAGE_FLAG) != 0;
        ensure!(
            self.config.xlang == peer_is_xlang,
            Error::invalid_data("header bitmap mismatch at xlang bit")
        );
        let is_none = (bitmap & IS_NULL_FLAG) != 0;
        if is_none {
            return Ok(true);
        }
        Ok(false)
    }
}
