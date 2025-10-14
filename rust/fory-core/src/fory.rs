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
use crate::ensure;
use crate::error::Error;
use crate::resolver::context::WriteContext;
use crate::resolver::context::{Pool, ReadContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::ForyDefault;
use crate::serializer::{Serializer, StructSerializer};
use crate::types::config_flags::IS_NULL_FLAG;
use crate::types::{
    config_flags::{IS_CROSS_LANGUAGE_FLAG, IS_LITTLE_ENDIAN_FLAG},
    Language, MAGIC_NUMBER, SIZE_OF_REF_AND_TYPE,
};
use crate::util::get_ext_actual_type_id;

static EMPTY_STRING: String = String::new();

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
    compatible: bool,
    xlang: bool,
    share_meta: bool,
    type_resolver: TypeResolver,
    compress_string: bool,
    max_dyn_depth: u32,
    write_context_pool: Pool<WriteContext>,
    read_context_pool: Pool<ReadContext>,
}

impl Default for Fory {
    fn default() -> Self {
        let write_context_constructor = || {
            let writer = Writer::default();
            WriteContext::new(writer)
        };
        let read_context_constructor = || {
            let reader = Reader::new(&[]);
            // when context is popped out, max_dyn_depth will be assigned a valid value
            ReadContext::new(reader, 0)
        };
        Fory {
            compatible: false,
            xlang: true,
            share_meta: false,
            type_resolver: TypeResolver::default(),
            compress_string: false,
            max_dyn_depth: 5,
            write_context_pool: Pool::new(write_context_constructor),
            read_context_pool: Pool::new(read_context_constructor),
        }
    }
}

impl Fory {
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
        self.share_meta = compatible;
        self.compatible = compatible;
        self
    }

    /// Enables or disables cross-language serialization protocol.
    ///
    /// # Arguments
    ///
    /// * `xlang` - If `true`, uses the cross-language serialization format that includes
    ///   language metadata and magic numbers for compatibility with Fory implementations
    ///   in other languages (Java, Python, C++, etc.). If `false`, uses a Rust-only
    ///   optimized format.
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
    /// // For cross-language use (default)
    /// let fory = Fory::default().xlang(true);
    ///
    /// // For Rust-only optimization
    /// let fory = Fory::default().xlang(false);
    /// ```
    pub fn xlang(mut self, xlang: bool) -> Self {
        self.xlang = xlang;
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
        self.compress_string = compress_string;
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
        self.max_dyn_depth = max_dyn_depth;
        self
    }

    /// Returns the current serialization mode.
    ///
    /// # Returns
    ///
    /// `ture` if the serialization mode is compatible, `false` otherwise`.
    pub fn is_compatible(&self) -> bool {
        self.compatible
    }

    /// Returns whether string compression is enabled.
    ///
    /// # Returns
    ///
    /// `true` if meta string compression is enabled, `false` otherwise.
    pub fn is_compress_string(&self) -> bool {
        self.compress_string
    }

    /// Returns whether metadata sharing is enabled.
    ///
    /// # Returns
    ///
    /// `true` if metadata sharing is enabled (automatically set based on mode), `false` otherwise.
    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    /// Returns a reference to the type resolver.
    ///
    /// # Returns
    ///
    /// A reference to the internal `TypeResolver` used for type registration and lookup.
    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    pub fn write_head<T: Serializer>(&self, is_none: bool, writer: &mut Writer) {
        const HEAD_SIZE: usize = 10;
        writer.reserve(T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE + HEAD_SIZE);
        if self.xlang {
            writer.write_u16(MAGIC_NUMBER);
        }
        #[cfg(target_endian = "big")]
        let mut bitmap = 0;
        #[cfg(target_endian = "little")]
        let mut bitmap = IS_LITTLE_ENDIAN_FLAG;
        if self.xlang {
            bitmap |= IS_CROSS_LANGUAGE_FLAG;
        }
        if is_none {
            bitmap |= IS_NULL_FLAG;
        }
        writer.write_u8(bitmap);
        if is_none {
            return;
        }
        if self.xlang {
            writer.write_u8(Language::Rust as u8);
        }
    }

    fn read_head(&self, reader: &mut Reader) -> Result<bool, Error> {
        if self.xlang {
            let magic_numer = reader.read_u16()?;
            ensure!(
                magic_numer == MAGIC_NUMBER,
                Error::InvalidData(
                    format!(
                        "The fory xlang serialization must start with magic number {:X}. \
                    Please check whether the serialization is based on the xlang protocol \
                    and the data didn't corrupt.",
                        MAGIC_NUMBER
                    )
                    .into()
                )
            )
        }
        let bitmap = reader.read_u8()?;
        let peer_is_xlang = (bitmap & IS_CROSS_LANGUAGE_FLAG) != 0;
        ensure!(
            self.xlang == peer_is_xlang,
            Error::InvalidData("header bitmap mismatch at xlang bit".into())
        );
        let is_little_endian = (bitmap & IS_LITTLE_ENDIAN_FLAG) != 0;
        ensure!(
            is_little_endian,
            Error::InvalidData(
                "Big endian is not supported for now, please ensure peer machine is little endian."
                    .into()
            )
        );
        let is_none = (bitmap & IS_NULL_FLAG) != 0;
        if is_none {
            return Ok(true);
        }
        if peer_is_xlang {
            let _peer_lang = reader.read_u8()?;
        }
        Ok(false)
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
        let mut context = self.read_context_pool.get();
        context.init(bf, self.max_dyn_depth);
        let result = self.deserialize_with_context(&mut context);
        if result.is_ok() {
            assert_eq!(context.reader.slice_after_cursor().len(), 0);
        }
        context.reset();
        self.read_context_pool.put(context);
        result
    }

    pub fn deserialize_with_context<T: Serializer + ForyDefault>(
        &self,
        context: &mut ReadContext,
    ) -> Result<T, Error> {
        let is_none = self.read_head(&mut context.reader)?;
        if is_none {
            return Ok(T::fory_default());
        }
        let mut bytes_to_skip = 0;
        if self.compatible {
            let meta_offset = context.reader.read_i32()?;
            if meta_offset != -1 {
                bytes_to_skip =
                    context.load_meta(self.get_type_resolver(), meta_offset as usize)?;
            }
        }
        let result = <T as Serializer>::fory_read(self, context, false);
        if bytes_to_skip > 0 {
            context.reader.skip(bytes_to_skip)?;
        }
        context.ref_reader.resolve_callbacks();
        result
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
        let mut context = self.write_context_pool.get();
        let result = self.serialize_with_context(record, &mut context)?;
        context.reset();
        self.write_context_pool.put(context);
        Ok(result)
    }

    pub fn serialize_with_context<T: Serializer>(
        &self,
        record: &T,
        context: &mut WriteContext,
    ) -> Result<Vec<u8>, Error> {
        let is_none = record.fory_is_none();
        self.write_head::<T>(is_none, &mut context.writer);
        let meta_start_offset = context.writer.len();
        if !is_none {
            if self.compatible {
                context.writer.write_i32(-1);
            };
            <T as Serializer>::fory_write(record, self, context, false)?;
            if self.compatible && !context.empty() {
                context.write_meta(meta_start_offset);
            }
        }
        Ok(context.writer.dump())
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
        let actual_type_id = T::fory_actual_type_id(id, false, self.compatible);
        let type_info =
            TypeInfo::new::<T>(self, actual_type_id, &EMPTY_STRING, &EMPTY_STRING, false)?;
        self.type_resolver.register::<T>(&type_info)
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
        let actual_type_id = T::fory_actual_type_id(0, true, self.compatible);
        let type_info = TypeInfo::new::<T>(self, actual_type_id, namespace, type_name, true)?;
        self.type_resolver.register::<T>(&type_info)
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
        let actual_type_id = get_ext_actual_type_id(id, false);
        let type_info = TypeInfo::new_with_empty_fields::<T>(
            self,
            actual_type_id,
            &EMPTY_STRING,
            &EMPTY_STRING,
            false,
        )?;
        self.type_resolver.register_serializer::<T>(&type_info)
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
        let actual_type_id = get_ext_actual_type_id(0, true);
        let type_info =
            TypeInfo::new_with_empty_fields::<T>(self, actual_type_id, namespace, type_name, true)?;
        self.type_resolver.register_serializer::<T>(&type_info)
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
}

pub fn write_data<T: Serializer>(
    this: &T,
    fory: &Fory,
    context: &mut WriteContext,
    is_field: bool,
) -> Result<(), Error> {
    T::fory_write_data(this, fory, context, is_field)
}

pub fn read_data<T: Serializer + ForyDefault>(
    fory: &Fory,
    context: &mut ReadContext,
    is_field: bool,
) -> Result<T, Error> {
    T::fory_read_data(fory, context, is_field)
}
