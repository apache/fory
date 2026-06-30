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
use crate::context::{ContextCache, ReadContext, WriteContext};
use crate::ensure;
use crate::error::Error;
use crate::resolver::RefMode;
use crate::resolver::TypeResolver;
use crate::serializer::ForyDefault;
use crate::serializer::{Serializer, StructSerializer};
use crate::type_id::config_flags::{IS_CROSS_LANGUAGE_FLAG, IS_OUT_OF_BAND_FLAG};
use crate::type_id::SIZE_OF_REF_AND_TYPE;
use std::cell::UnsafeCell;
use std::mem;
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

/// Builder for configuring a [`Fory`] instance before first use.
///
/// `ForyBuilder` owns the configuration phase. Call [`build`](Self::build) to create the
/// instance, then use [`Fory`] for registration and serialization operations.
///
/// ```rust
/// use fory_core::Fory;
///
/// let fory = Fory::builder()
///     .compress_string(true)
///     .max_dyn_depth(10)
///     .build();
/// ```
#[derive(Default)]
pub struct ForyBuilder {
    config: Config,
    compatible_set: bool,
}

impl ForyBuilder {
    /// Sets the serialization compatible mode for this Fory builder.
    ///
    /// # Arguments
    ///
    /// * `compatible` - The serialization compatible mode to use. Options are:
    ///   - `false`: Every reader and writer must use the same schema.
    ///     Use only for smaller, faster same-schema payloads.
    ///   - `true`: Supports schema evolution and type metadata sharing for better
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
    /// - `true` → `share_meta = true`
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// // Same-schema optimization.
    /// let fory = Fory::builder().compatible(false).build();
    /// ```
    pub fn compatible(mut self, compatible: bool) -> Self {
        self.compatible_set = true;
        // Setting share_meta individually is not supported currently
        self.config.share_meta = compatible;
        self.config.compatible = compatible;
        if compatible {
            self.config.check_struct_version = false;
        } else if self.config.xlang {
            self.config.check_struct_version = true;
        }
        self
    }

    /// Enables or disables xlang mode.
    ///
    /// # Arguments
    ///
    /// * `xlang` - If `true`, uses the xlang wire format compatible with other Fory
    ///   implementations (Java, Python, C++, etc.). If `false`, uses Rust native mode.
    ///
    /// # Returns
    ///
    /// Returns `self` for method chaining.
    ///
    /// # Default
    ///
    /// The default value is `true`.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use fory_core::Fory;
    ///
    /// // Xlang mode, the default cross-language wire format
    /// let fory = Fory::builder().xlang(true).build();
    ///
    /// // Native mode for Rust-only traffic
    /// let fory = Fory::builder().xlang(false).build();
    /// ```
    pub fn xlang(mut self, xlang: bool) -> Self {
        self.config.xlang = xlang;
        if !self.compatible_set {
            self.config.share_meta = true;
            self.config.compatible = true;
            self.config.check_struct_version = false;
            return self;
        }
        if !self.config.check_struct_version {
            self.config.check_struct_version = !self.config.compatible;
        }
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
    /// let fory = Fory::builder().compress_string(true).build();
    /// ```
    pub fn compress_string(mut self, compress_string: bool) -> Self {
        self.config.compress_string = compress_string;
        self
    }

    /// Enables or disables checked UTF-8 string reads.
    ///
    /// Checked reads validate UTF-8 payload bytes before constructing Rust `String` values.
    /// Disabling this keeps the faster unchecked construction path and must only be used when
    /// serialized bytes are trusted to contain valid UTF-8 strings.
    ///
    /// # Default
    ///
    /// The default value is `true`.
    pub fn check_string_read(mut self, check_string_read: bool) -> Self {
        self.config.check_string_read = check_string_read;
        self
    }

    /// Enables or disables schema hash checking for same-schema payloads.
    ///
    /// # Arguments
    ///
    /// * `check_struct_version` - If `true`, enables schema hash checking for same-schema
    ///   serialization and deserialization. When enabled,
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
    /// let fory = Fory::builder()
    ///     .compatible(false)
    ///     .check_struct_version(true)
    ///     .build();
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
    /// let fory = Fory::builder().track_ref(true).build();
    /// ```
    pub fn track_ref(mut self, track_ref: bool) -> Self {
        self.config.track_ref = track_ref;
        self
    }

    /// Sets the maximum estimated graph memory accepted during one root deserialization.
    ///
    /// Use `-1` for the automatic input-shaped limit. Positive values are explicit byte limits.
    pub fn max_graph_memory_bytes(mut self, max_bytes: i64) -> Self {
        assert!(
            max_bytes == -1 || max_bytes > 0,
            "max_graph_memory_bytes must be positive or -1 for auto"
        );
        self.config.max_graph_memory_bytes = max_bytes;
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
    /// let fory = Fory::builder().max_dyn_depth(10).build();
    ///
    /// // Restrict nesting for safer deserialization
    /// let fory = Fory::builder().max_dyn_depth(3).build();
    /// ```
    pub fn max_dyn_depth(mut self, max_dyn_depth: u32) -> Self {
        self.config.max_dyn_depth = max_dyn_depth;
        self
    }

    /// Sets the maximum field count accepted in one received struct TypeMeta.
    pub fn max_type_fields(mut self, max_fields: usize) -> Self {
        assert!(max_fields > 0, "max_type_fields must be positive");
        assert!(
            u32::try_from(max_fields).is_ok(),
            "max_type_fields is too large"
        );
        self.config.max_type_fields = max_fields as u32;
        self
    }

    /// Sets the maximum body size accepted for one received TypeMeta.
    pub fn max_type_meta_bytes(mut self, max_bytes: usize) -> Self {
        assert!(max_bytes > 0, "max_type_meta_bytes must be positive");
        assert!(
            u32::try_from(max_bytes).is_ok(),
            "max_type_meta_bytes is too large"
        );
        self.config.max_type_meta_bytes = max_bytes as u32;
        self
    }

    /// Sets the maximum accepted remote metadata versions for one logical type.
    pub fn max_schema_versions_per_type(mut self, max_versions: usize) -> Self {
        assert!(
            max_versions > 0,
            "max_schema_versions_per_type must be positive"
        );
        assert!(
            u32::try_from(max_versions).is_ok(),
            "max_schema_versions_per_type is too large"
        );
        self.config.max_schema_versions_per_type = max_versions as u32;
        self
    }

    /// Sets the maximum accepted average remote metadata versions across logical types.
    pub fn max_average_schema_versions_per_type(mut self, max_versions: usize) -> Self {
        assert!(
            max_versions > 0,
            "max_average_schema_versions_per_type must be positive"
        );
        assert!(
            u32::try_from(max_versions).is_ok(),
            "max_average_schema_versions_per_type is too large"
        );
        self.config.max_average_schema_versions_per_type = max_versions as u32;
        self
    }

    fn finish_config(self) -> Config {
        let mut config = self.config;
        if !self.compatible_set {
            config.share_meta = true;
            config.compatible = true;
            config.check_struct_version = false;
        }
        config
    }

    /// Builds a [`Fory`] instance with the current builder configuration.
    pub fn build(self) -> Fory {
        let config = self.finish_config();
        Fory::from_config(config)
    }
}

/// The main Fory serialization framework instance.
///
/// `Fory` provides high-performance serialization and deserialization with xlang mode,
/// native mode, reference tracking, and trait object serialization.
///
/// # Features
///
/// - **Xlang mode**: Default wire format for cross-language payloads
/// - **Native mode**: Rust-only wire format selected with `.xlang(false)`
/// - **Schema evolution**: Compatible mode by default, with a same-schema optimization available
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
/// use fory::{ForyEnum, ForyStruct, ForyUnion};
///
/// #[derive(ForyStruct)]
/// struct User {
///     name: String,
///     age: u32,
/// }
///
/// let mut fory = Fory::builder().xlang(true).build();
/// fory.register_by_name::<User>("example.User").unwrap();
/// let user = User { name: "Alice".to_string(), age: 30 };
/// let bytes = fory.serialize(&user).unwrap();
/// let deserialized: User = fory.deserialize(&bytes).unwrap();
/// ```
///
/// Custom configuration:
///
/// ```rust
/// use fory_core::Fory;
///
/// let fory = Fory::builder()
///     .compress_string(true)
///     .max_dyn_depth(10)
///     .build();
/// ```
pub struct Fory {
    /// Unique identifier for this Fory instance, used as key in thread-local context maps.
    id: u64,
    type_resolver: TypeResolver,
    /// Lazy-initialized final type resolver (thread-safe, one-time initialization).
    final_type_resolver: OnceLock<Result<TypeResolver, Error>>,
    /// Configuration for serialization behavior.
    ///
    /// Keep this cold field after the resolver/cache fields. Remote metadata
    /// limits make Config larger, but serialize hot paths repeatedly access
    /// the instance id and resolver snapshot, not the cold limit values.
    config: Config,
}

impl Default for Fory {
    fn default() -> Self {
        Self::builder().build()
    }
}

impl Fory {
    /// Creates a builder for configuring a [`Fory`] instance.
    pub fn builder() -> ForyBuilder {
        ForyBuilder::default()
    }

    fn from_config(config: Config) -> Self {
        let mut type_resolver = TypeResolver::default();
        type_resolver.set_compatible(config.compatible);
        type_resolver.set_xlang(config.xlang);
        Self {
            id: FORY_ID_COUNTER.fetch_add(1, Ordering::Relaxed),
            config,
            type_resolver,
            final_type_resolver: OnceLock::new(),
        }
    }

    /// Returns whether xlang mode is enabled.
    pub fn is_xlang(&self) -> bool {
        self.config.xlang
    }

    /// Returns whether compatible schema evolution is enabled.
    ///
    /// # Returns
    ///
    /// `true` if compatible schema evolution is enabled, `false` otherwise.
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

    /// Returns whether UTF-8 string payload validation is enabled.
    pub fn is_check_string_read(&self) -> bool {
        self.config.check_string_read
    }

    /// Returns whether metadata sharing is enabled.
    ///
    /// # Returns
    ///
    /// `true` if metadata sharing is enabled, `false` otherwise.
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

    /// Checks whether the final type resolver has already been initialized.
    ///
    /// If it has, further type registrations would be silently ignored (the frozen
    /// snapshot is what serialize/deserialize actually use),so we fail fast with
    /// a clear error instead.
    ///
    /// # errors
    ///
    /// returns [`Error::NotAllowed`] when the resolver snapshot has already been
    /// built (i.e after the first `serialize` / `deserialize` call).
    fn check_registration_allowed(&self) -> Result<(), Error> {
        if self.final_type_resolver.get().is_some() {
            return Err(Error::not_allowed(
                "Type registration is not allowed after the first serialize/deserialize call. \
                 The type resolver snapshot has already been finalized. \
                 Please complete all type registrations before performing any serialization or deserialization.",
            ));
        }
        Ok(())
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
    /// use fory::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct Point { x: i32, y: i32 }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<Point>("example.Point").unwrap();
    /// let point = Point { x: 10, y: 20 };
    /// let bytes = fory.serialize(&point).unwrap();
    /// ```
    pub fn serialize<T: Serializer>(&self, record: &T) -> Result<Vec<u8>, Error> {
        self.with_write_context(
            |context| match self.serialize_with_context(record, context) {
                Ok(_) => {
                    let result = context.writer.dump();
                    context.writer.reset();
                    Ok(result)
                }
                Err(err) => {
                    context.writer.reset();
                    Err(err)
                }
            },
        )
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
    /// use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct Point {
    ///     x: i32,
    ///     y: i32,
    /// }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<Point>("example.Point").unwrap();
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
    /// use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct, PartialEq, Debug)]
    /// struct Point {
    ///     x: i32,
    ///     y: i32,
    /// }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<Point>("example.Point").unwrap();
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
    /// use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct Point {
    ///     x: i32,
    ///     y: i32,
    /// }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<Point>("example.Point").unwrap();
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
            // Context from thread-local would be 'static. but context hold the buffer through `writer` field,
            // so we should make buffer live longer.
            // After serializing, `detach_writer` will be called, the writer in context will be set to dangling pointer.
            // So it's safe to make buf live to the end of this method.
            let outlive_buffer = unsafe { mem::transmute::<&mut Vec<u8>, &mut Vec<u8>>(buf) };
            context.attach_writer(Writer::from_buffer(outlive_buffer));
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

            let context = cache.get_or_insert_result(id, || {
                // Only fetch type resolver when creating a new context
                let type_resolver = self.get_final_type_resolver()?;
                Ok(Box::new(WriteContext::new(
                    type_resolver.clone(),
                    self.config.clone(),
                )))
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
        let result = self.serialize_with_context_inner::<T>(record, context);
        context.reset();
        result
    }

    #[inline(always)]
    fn serialize_with_context_inner<T: Serializer>(
        &self,
        record: &T,
        context: &mut WriteContext,
    ) -> Result<(), Error> {
        self.write_head::<T>(&mut context.writer);
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
    /// use fory::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct User { name: String, age: u32 }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register::<User>(100).unwrap();
    /// ```
    pub fn register<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.check_registration_allowed()?;
        self.type_resolver.register::<T>(id)
    }

    /// Register a union type with a numeric type ID.
    ///
    /// This is intended for union-compatible enums generated by the compiler.
    pub fn register_union<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.check_registration_allowed()?;
        self.type_resolver.register_union::<T>(id)
    }

    /// Registers a struct type with a qualified type name for xlang serialization.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The struct type to register. Must implement `StructSerializer`, `Serializer`, and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `name` - The type name, optionally prefixed with a namespace separated by `.`.
    ///   For example, `"com.example.User"` uses namespace `"com.example"` and type name `"User"`.
    ///   Use `"User"` for the default namespace.
    ///
    /// # Notes
    ///
    /// This registration method is preferred for xlang serialization because it uses
    /// human-readable type identifiers instead of numeric IDs, which improves compatibility
    /// across different language implementations.
    ///
    /// # Examples
    ///
    /// The example uses xlang mode because name-based registration is the preferred
    /// registration style for cross-language payloads.
    ///
    /// ```rust, ignore
    /// use fory::Fory;
    /// use fory::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct User { name: String, age: u32 }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<User>("com.example.User").unwrap();
    /// ```
    pub fn register_by_name<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        name: &str,
    ) -> Result<(), Error> {
        self.check_registration_allowed()?;
        self.type_resolver.register_by_name::<T>(name)
    }

    /// Register a union type with a qualified type name.
    ///
    /// This is intended for union-compatible enums generated by the compiler.
    pub fn register_union_by_name<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        name: &str,
    ) -> Result<(), Error> {
        self.check_registration_allowed()?;
        self.type_resolver.register_union_by_name::<T>(name)
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
    /// let mut fory = Fory::builder().xlang(false).build();
    /// fory.register_serializer::<MyCustomType>(200).unwrap();
    /// ```
    pub fn register_serializer<T: Serializer + ForyDefault>(
        &mut self,
        id: u32,
    ) -> Result<(), Error> {
        self.check_registration_allowed()?;
        self.type_resolver.register_serializer::<T>(id)
    }

    /// Registers a custom serializer type with a qualified type name.
    ///
    /// # Type Parameters
    ///
    /// * `T` - The type to register. Must implement `Serializer` and `ForyDefault`.
    ///
    /// # Arguments
    ///
    /// * `name` - The type name, optionally prefixed with a namespace separated by `.`.
    ///
    /// # Notes
    ///
    /// This is the named equivalent of `register_serializer()`, preferred for
    /// xlang serialization scenarios.
    ///
    pub fn register_serializer_by_name<T: Serializer + ForyDefault>(
        &mut self,
        name: &str,
    ) -> Result<(), Error> {
        self.check_registration_allowed()?;
        self.type_resolver.register_serializer_by_name::<T>(name)
    }

    /// Writes the serialization header to the writer.
    #[inline(always)]
    pub fn write_head<T: Serializer>(&self, writer: &mut Writer) {
        const HEAD_SIZE: usize = 10;
        writer.reserve(T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE + HEAD_SIZE);
        let bitmap = if self.config.xlang {
            IS_CROSS_LANGUAGE_FLAG
        } else {
            0
        };
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
    /// use fory::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct Point { x: i32, y: i32 }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<Point>("example.Point").unwrap();
    /// let point = Point { x: 10, y: 20 };
    /// let bytes = fory.serialize(&point).unwrap();
    /// let deserialized: Point = fory.deserialize(&bytes).unwrap();
    /// ```
    pub fn deserialize<T: Serializer + ForyDefault>(&self, bf: &[u8]) -> Result<T, Error> {
        self.with_read_context(|context| {
            let outlive_buffer = unsafe { mem::transmute::<&[u8], &[u8]>(bf) };
            context.attach_reader(Reader::new(outlive_buffer));
            let result = match context.init_graph_memory_budget(bf.len()) {
                Ok(()) => self.deserialize_with_context(context),
                Err(err) => {
                    context.reset();
                    Err(err)
                }
            };
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
    /// use fory_derive::{ForyEnum, ForyStruct, ForyUnion};
    ///
    /// #[derive(ForyStruct)]
    /// struct Point { x: i32, y: i32 }
    ///
    /// let mut fory = Fory::builder().xlang(true).build();
    /// fory.register_by_name::<Point>("example.Point").unwrap();
    /// let point = Point { x: 10, y: 20 };
    ///
    /// let mut buf = Vec::new();
    /// fory.serialize_to(&mut buf, &point).unwrap();
    ///
    /// let mut reader = Reader::new(&buf);
    /// let deserialized: Point = fory.deserialize_from(&mut reader).unwrap();
    /// ```
    pub fn deserialize_from<T: Serializer + ForyDefault>(
        &self,
        reader: &mut Reader,
    ) -> Result<T, Error> {
        self.with_read_context(|context| {
            let outlive_buffer = unsafe { mem::transmute::<&[u8], &[u8]>(reader.bf) };
            let mut new_reader = Reader::new(outlive_buffer);
            new_reader.set_cursor(reader.cursor);
            let root_input_bytes = reader.bf.len().saturating_sub(reader.cursor);
            context.attach_reader(new_reader);
            let result = match context.init_graph_memory_budget(root_input_bytes) {
                Ok(()) => self.deserialize_with_context(context),
                Err(err) => {
                    context.reset();
                    Err(err)
                }
            };
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

            let context = cache.get_or_insert_result(id, || {
                // Only fetch type resolver when creating a new context
                let type_resolver = self.get_final_type_resolver()?;
                Ok(Box::new(ReadContext::new(
                    type_resolver.clone(),
                    self.config.clone(),
                )))
            })?;
            f(context)
        })
    }

    #[inline(always)]
    fn deserialize_with_context<T: Serializer + ForyDefault>(
        &self,
        context: &mut ReadContext,
    ) -> Result<T, Error> {
        let result = self.deserialize_with_context_inner::<T>(context);
        context.reset();
        result
    }

    #[inline(always)]
    fn deserialize_with_context_inner<T: Serializer + ForyDefault>(
        &self,
        context: &mut ReadContext,
    ) -> Result<T, Error> {
        self.read_head(&mut context.reader)?;
        // Use RefMode based on config:
        // - If track_ref is enabled, use RefMode::Tracking for the root object
        // - Otherwise, use RefMode::NullOnly
        let ref_mode = if self.config.track_ref {
            RefMode::Tracking
        } else {
            RefMode::NullOnly
        };
        // TypeMeta is read inline during deserialization (streaming protocol)
        let root_graph_self_size = T::fory_graph_self_size();
        if root_graph_self_size != 0 {
            context.reserve_graph_memory(root_graph_self_size)?;
        }
        let result = <T as Serializer>::fory_read(context, ref_mode, true);
        context.ref_reader.resolve_callbacks();
        result
    }

    #[inline(always)]
    fn read_head(&self, reader: &mut Reader) -> Result<(), Error> {
        let bitmap = reader.read_u8()?;
        let expected = if self.config.xlang {
            IS_CROSS_LANGUAGE_FLAG
        } else {
            0
        };
        if bitmap != expected {
            return self.read_head_slow(bitmap, expected);
        }
        Ok(())
    }

    #[cold]
    #[inline(never)]
    fn read_head_slow(&self, bitmap: u8, expected: u8) -> Result<(), Error> {
        const KNOWN_FLAGS: u8 = IS_CROSS_LANGUAGE_FLAG | IS_OUT_OF_BAND_FLAG;
        ensure!(
            (bitmap & !KNOWN_FLAGS) == 0 && (bitmap & IS_OUT_OF_BAND_FLAG) == 0,
            Error::invalid_data("unsupported root header bitmap")
        );
        ensure!(
            (bitmap & IS_CROSS_LANGUAGE_FLAG) == (expected & IS_CROSS_LANGUAGE_FLAG),
            Error::invalid_data("header bitmap mismatch at xlang bit")
        );
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::Fory;

    #[test]
    fn compatible_defaults_and_overrides() {
        let default_xlang = Fory::builder().xlang(true).finish_config();
        let default_native = Fory::builder().xlang(false).finish_config();
        let explicit_same_schema = Fory::builder()
            .compatible(false)
            .xlang(true)
            .finish_config();
        let explicit_same_schema_reverse_order = Fory::builder()
            .xlang(true)
            .compatible(false)
            .finish_config();

        assert!(default_xlang.compatible);
        assert!(default_xlang.share_meta);
        assert!(!default_xlang.check_struct_version);
        assert!(default_native.compatible);
        assert!(default_native.share_meta);
        assert!(!default_native.check_struct_version);

        assert!(!explicit_same_schema.compatible);
        assert!(!explicit_same_schema.share_meta);
        assert!(explicit_same_schema.check_struct_version);
        assert!(!explicit_same_schema_reverse_order.compatible);
        assert!(!explicit_same_schema_reverse_order.share_meta);
        assert!(explicit_same_schema_reverse_order.check_struct_version);
    }
}
