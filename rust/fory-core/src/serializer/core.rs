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

use crate::error::Error;
use crate::meta::FieldInfo;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeInfo;
use crate::serializer::{bool, struct_};
use crate::types::{RefFlag, TypeId};
use crate::TypeResolver;
use std::any::Any;
use std::rc::Rc;

/// Trait for creating default values during Fory deserialization.
///
/// `ForyDefault` is similar to Rust's standard `Default` trait but specifically designed
/// for Fory's serialization framework. It provides a way to create "null" or default
/// values when deserializing optional or nullable types.
///
/// # Why not use `Default`?
///
/// We can't add a blanket implementation `impl<T: Default> ForyDefault for T` because
/// it would conflict with potential future implementations in upstream crates. For example,
/// the standard library might add `impl Default for Rc<dyn Any>` in the future, which would
/// cause a trait coherence conflict.
///
/// # When to implement
///
/// You should implement `ForyDefault` when:
/// - Creating custom types that can be serialized with Fory
/// - Your type needs to support nullable/optional representations
/// - You want to define what the "null" or "default" value means for deserialization
///
/// # Examples
///
/// ```
/// use fory_core::ForyDefault;
///
/// #[derive(Debug, PartialEq)]
/// struct Point {
///     x: i32,
///     y: i32,
/// }
///
/// impl ForyDefault for Point {
///     fn fory_default() -> Self {
///         Point { x: 0, y: 0 }
///     }
/// }
///
/// let default_point = Point::fory_default();
/// assert_eq!(default_point, Point { x: 0, y: 0 });
/// ```
///
/// For types that already implement `Default`, you can simply delegate:
///
/// ```
/// use fory_core::ForyDefault;
///
/// #[derive(Default)]
/// struct Config {
///     timeout: u32,
///     retries: u32,
/// }
///
/// impl ForyDefault for Config {
///     fn fory_default() -> Self {
///         Default::default()
///     }
/// }
/// ```
pub trait ForyDefault: Sized {
    /// Creates a default value for this type.
    ///
    /// This is used by Fory when deserializing null values or when a default
    /// instance is needed during the deserialization process.
    fn fory_default() -> Self;
}

// We can't add blanket impl for all T: Default because it conflicts with other impls.
// For example, upstream crates may add a new impl of trait `std::default::Default` for
// type `std::rc::Rc<(dyn std::any::Any + 'static)>` in future versions.
// impl<T: Default + Sized> ForyDefault for T {
//     fn fory_default() -> Self {
//         Default::default()
//     }
// }

/// Core trait for Fory serialization and deserialization.
///
/// `Serializer` is the primary trait that enables types to be serialized and deserialized
/// using Fory's high-performance cross-language protocol. All types that can be serialized
/// with Fory must implement this trait, either manually or via the `#[derive(ForyObject)]` macro.
///
/// # Architecture Overview
///
/// Fory's serialization consists of three main phases:
///
/// 1. **Reference tracking** (optional): Writes/reads null/not-null/ref flags for handling
///    shared references and circular references
/// 2. **Type information** (optional): Writes/reads type metadata for polymorphic types
/// 3. **Data serialization**: Writes/reads the actual data payload
///
/// # Method Categories
///
/// ## Methods Users Must Implement (for custom serialization logic)
///
/// - [`fory_write_data`]: Core method to serialize your type's data
/// - [`fory_read_data`]: Core method to deserialize your type's data
/// - [`fory_type_id_dyn`]: Return runtime type ID for the instance
/// - [`as_any`]: Downcast support for dynamic dispatch
///
/// ## Methods Implemented by Fory (override only if needed)
///
/// Most methods have default implementations suitable for common types:
///
/// - [`fory_write`]: Entry point for serialization (handles ref/type info)
/// - [`fory_read`]: Entry point for deserialization (handles ref/type info)
/// - [`fory_write_type_info`]: Write type metadata
/// - [`fory_read_type_info`]: Read type metadata
/// - [`fory_write_data_generic`]: Write data with generics support (for collections)
/// - [`fory_read_with_type_info`]: Deserialize with pre-read type info
///
/// ## Type Query Methods
///
/// These provide compile-time and runtime type information:
///
/// - [`fory_static_type_id`]: Static type ID (defaults to TypeId::EXT for user types)
/// - [`fory_get_type_id`]: Get registered type ID from TypeResolver
/// - [`fory_concrete_type_id`]: Get Rust's std::any::TypeId
/// - [`fory_is_option`]: Check if type is Option<T>
/// - [`fory_is_none`]: Check if instance is None (for Option types)
/// - [`fory_is_polymorphic`]: Check if type supports polymorphism
/// - [`fory_is_shared_ref`]: Check if type is Rc/Arc
/// - [`fory_reserved_space`]: Hint for buffer pre-allocation
///
/// # Typical Usage Pattern
///
/// For types that need custom serialization logic (EXT types), you only need to implement four methods:
///
/// ```rust,ignore
/// impl Serializer for MyType {
///     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
///         // Write your type's fields
///     }
///
///     fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
///         // Read your type's fields
///     }
///
///     fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
///         Self::fory_get_type_id(type_resolver)
///     }
///
///     fn as_any(&self) -> &dyn Any {
///         self
///     }
/// }
/// ```
///
/// # Derive Macro
///
/// For struct types, you can use the `#[derive(ForyObject)]` macro instead of implementing manually:
///
/// ```rust,ignore
/// #[derive(ForyObject)]
/// struct Point {
///     x: f64,
///     y: f64,
/// }
/// ```
///
/// # See Also
///
/// - [`ForyDefault`]: Trait for creating default values during deserialization
/// - [`StructSerializer`]: Extended trait for struct-specific serialization
///
/// [`fory_write_data`]: Serializer::fory_write_data
/// [`fory_read_data`]: Serializer::fory_read_data
/// [`fory_write`]: Serializer::fory_write
/// [`fory_read`]: Serializer::fory_read
/// [`fory_write_type_info`]: Serializer::fory_write_type_info
/// [`fory_read_type_info`]: Serializer::fory_read_type_info
/// [`fory_write_data_generic`]: Serializer::fory_write_data_generic
/// [`fory_read_with_type_info`]: Serializer::fory_read_with_type_info
/// [`fory_type_id_dyn`]: Serializer::fory_type_id_dyn
/// [`as_any`]: Serializer::as_any
/// [`fory_static_type_id`]: Serializer::fory_static_type_id
/// [`fory_get_type_id`]: Serializer::fory_get_type_id
/// [`fory_concrete_type_id`]: Serializer::fory_concrete_type_id
/// [`fory_is_option`]: Serializer::fory_is_option
/// [`fory_is_none`]: Serializer::fory_is_none
/// [`fory_is_polymorphic`]: Serializer::fory_is_polymorphic
/// [`fory_is_shared_ref`]: Serializer::fory_is_shared_ref
/// [`fory_reserved_space`]: Serializer::fory_reserved_space
pub trait Serializer: 'static {
    /// Entry point for serialization.
    ///
    /// This method orchestrates the complete serialization process, handling reference tracking,
    /// type information, and delegating to [`fory_write_data`] for the actual data serialization.
    ///
    /// # Parameters
    ///
    /// * `write_ref_info` - When `true`, WRITES reference flag (null/not-null/ref). When `false`, SKIPS writing ref flag.
    /// * `write_type_info` - When `true`, WRITES type information. When `false`, SKIPS writing type info.
    /// * `has_generics` - Indicates if the type has generic parameters (used for collection meta).
    ///
    /// # Default Implementation
    ///
    /// The default implementation handles standard non-nullable types:
    ///
    /// 1. If `write_ref_info` is true, writes `RefFlag::NotNullValue`
    /// 2. If `write_type_info` is true, calls [`fory_write_type_info`]
    /// 3. Calls [`fory_write_data_generic`] to write the actual data
    ///
    /// # When to Override
    ///
    /// You should override this method for:
    ///
    /// - **Option types**: Need to write `RefFlag::Null` for None values
    /// - **Reference types** (Rc/Arc/Weak): Need custom reference handling
    /// - **Polymorphic types**: Need to write actual runtime type instead of static type
    ///
    /// For regular types (structs, primitives, collections), the default implementation is sufficient.
    ///
    /// # Implementation Notes
    ///
    /// - This method is implemented by Fory for all built-in types
    /// - User types with custom serialization typically don't need to override this
    /// - Focus on implementing [`fory_write_data`] instead
    ///
    /// [`fory_write_data`]: Serializer::fory_write_data
    /// [`fory_write_type_info`]: Serializer::fory_write_type_info
    /// [`fory_write_data_generic`]: Serializer::fory_write_data_generic
    #[inline(always)]
    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error>
    where
        Self: Sized,
    {
        if write_ref_info {
            // In xlang mode, determine RefFlag based on type characteristics
            let should_write_ref = if context.is_xlang() {
                Self::fory_is_xlang_ref_type()
            } else {
                // In non-xlang mode, use original logic
                true
            };
            
            if should_write_ref {
                // This is a reference type in xlang context - write RefValue
                context.writer.write_i8(RefFlag::RefValue as i8);
            } else {
                // This is a value type - write NotNullValue
                context.writer.write_i8(RefFlag::NotNullValue as i8);
            }
        }
        if write_type_info {
            // Serializer for dynamic types should override `fory_write` to write actual typeinfo.
            Self::fory_write_type_info(context)?;
        }
        self.fory_write_data_generic(context, has_generics)
    }

    /// Write data with generic type parameter support.
    ///
    /// This method is primarily used by collection types (Vec, HashMap, etc.) that need to
    /// write generic type information for their elements. For most types, this simply
    /// delegates to [`fory_write_data`].
    ///
    /// # Parameters
    ///
    /// * `context` - Write context containing the buffer and type resolver
    /// * `has_generics` - Indicates if the type has generic parameters that need metadata
    ///
    /// # Default Implementation
    ///
    /// The default implementation ignores `has_generics` and forwards to [`fory_write_data`]:
    ///
    /// ```rust,ignore
    /// fn fory_write_data_generic(&self, context: &mut WriteContext, has_generics: bool) -> Result<(), Error> {
    ///     self.fory_write_data(context)
    /// }
    /// ```
    ///
    /// # When to Override
    ///
    /// Override this method for:
    ///
    /// - **Collection types** (Vec, HashMap, HashSet, etc.): Need to write element/key/value type metadata
    /// - **Generic containers**: Any type that contains type parameters requiring runtime metadata
    ///
    /// For non-generic types (primitives, simple structs), use the default implementation.
    ///
    /// # Implementation Notes
    ///
    /// - Implemented by Fory for all collection types
    /// - User types with custom serialization for non-generic types don't need to override
    /// - Focus on implementing [`fory_write_data`] for custom types
    ///
    /// [`fory_write_data`]: Serializer::fory_write_data
    #[inline(always)]
    #[allow(unused_variables)]
    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        self.fory_write_data(context)
    }

    /// **[USER IMPLEMENTATION REQUIRED]** Serialize the type's data to the buffer.
    ///
    /// This is the core serialization method that you must implement for custom types.
    /// It should write all the type's fields and data to the buffer using the provided context.
    ///
    /// # Parameters
    ///
    /// * `context` - Write context providing:
    ///   - `context.writer`: Buffer to write binary data
    ///   - `context.type_resolver`: Registry of type information
    ///   - `context.ref_resolver`: Resolver for shared/circular references (if enabled)
    ///
    /// # Writing Data
    ///
    /// Use methods on `context.writer` to write primitive types:
    ///
    /// - `write_i8()`, `write_i16()`, `write_i32()`, `write_i64()`: Signed integers
    /// - `write_u8()`, `write_u16()`, `write_u32()`, `write_u64()`: Unsigned integers
    /// - `write_f32()`, `write_f64()`: Floating point numbers
    /// - `write_bool()`: Boolean values
    /// - `write_bytes()`: Raw byte arrays
    ///
    /// For nested types that implement `Serializer`, call their serialization:
    ///
    /// ```rust,ignore
    /// // For types that implement Serializer
    /// self.nested_field.fory_write(context, false, false, false)?;
    /// ```
    ///
    /// # Examples
    ///
    /// ## Simple struct with primitive fields
    ///
    /// ```rust
    /// use fory_core::{Serializer, ForyDefault};
    /// use fory_core::resolver::context::WriteContext;
    /// use fory_core::error::Error;
    /// use std::any::Any;
    ///
    /// struct Point {
    ///     x: f64,
    ///     y: f64,
    /// }
    ///
    /// impl ForyDefault for Point {
    ///     fn fory_default() -> Self {
    ///         Point { x: 0.0, y: 0.0 }
    ///     }
    /// }
    ///
    /// impl Serializer for Point {
    ///     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
    ///         // Write each field in order
    ///         context.writer.write_f64(self.x);
    ///         context.writer.write_f64(self.y);
    ///         Ok(())
    ///     }
    ///
    ///     fn fory_read_data(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, Error>
    ///     where
    ///         Self: Sized + fory_core::ForyDefault,
    ///     {
    ///         let x = context.reader.read_f64()?;
    ///         let y = context.reader.read_f64()?;
    ///         Ok(Point { x, y })
    ///     }
    ///
    ///     fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, Error> {
    ///         Self::fory_get_type_id(type_resolver)
    ///     }
    ///
    ///     fn as_any(&self) -> &dyn Any {
    ///         self
    ///     }
    /// }
    /// ```
    ///
    /// ## Struct with nested serializable types
    ///
    /// ```rust
    /// use fory_core::{Serializer, ForyDefault};
    /// use fory_core::resolver::context::WriteContext;
    /// use fory_core::error::Error;
    /// use std::any::Any;
    ///
    /// struct Person {
    ///     name: String,
    ///     age: u32,
    ///     scores: Vec<i32>,
    /// }
    ///
    /// impl ForyDefault for Person {
    ///     fn fory_default() -> Self {
    ///         Person {
    ///             name: String::new(),
    ///             age: 0,
    ///             scores: Vec::new(),
    ///         }
    ///     }
    /// }
    ///
    /// impl Serializer for Person {
    ///     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
    ///         // Write nested types using their Serializer implementations
    ///         self.name.fory_write(context, false, false, false)?;
    ///         context.writer.write_u32(self.age);
    ///         self.scores.fory_write(context, false, false, true)?; // has_generics=true for Vec
    ///         Ok(())
    ///     }
    ///
    ///     fn fory_read_data(context: &mut fory_core::resolver::context::ReadContext) -> Result<Self, Error>
    ///     where
    ///         Self: Sized + fory_core::ForyDefault,
    ///     {
    ///         let name = String::fory_read(context, false, false)?;
    ///         let age = context.reader.read_u32()?;
    ///         let scores = Vec::<i32>::fory_read(context, false, false)?;
    ///         Ok(Person { name, age, scores })
    ///     }
    ///
    ///     fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, Error> {
    ///         Self::fory_get_type_id(type_resolver)
    ///     }
    ///
    ///     fn as_any(&self) -> &dyn Any {
    ///         self
    ///     }
    /// }
    /// ```
    ///
    /// # Implementation Guidelines
    ///
    /// 1. **Field order matters**: Write fields in a consistent order; read them in the same order
    /// 2. **No metadata here**: Don't write type info or ref flags; that's handled by [`fory_write`]
    /// 3. **Use Serializer for nested types**: Leverage existing implementations for standard types
    /// 4. **Error handling**: Propagate errors with `?`; don't swallow them
    /// 5. **Performance**: Minimize allocations; write directly to the buffer
    ///
    /// # Common Patterns
    ///
    /// ```rust,ignore
    /// // Writing primitives directly
    /// context.writer.write_i32(self.count);
    ///
    /// // Writing nested Serializer types
    /// self.nested.fory_write(context, false, false, false)?;
    ///
    /// // Writing collections with generics
    /// self.items.fory_write(context, false, false, true)?;
    ///
    /// // Writing optional types
    /// self.optional.fory_write(context, true, false, false)?; // write_ref_info=true
    /// ```
    ///
    /// # See Also
    ///
    /// - [`fory_read_data`]: Corresponding deserialization method
    /// - [`fory_write`]: High-level serialization entry point
    /// - [`WriteContext`]: Context providing buffer and resolver access
    ///
    /// [`fory_write`]: Serializer::fory_write
    /// [`fory_read_data`]: Serializer::fory_read_data
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error>;

    /// Write type metadata to the buffer.
    ///
    /// This method writes type information that allows Fory to identify and deserialize
    /// the type correctly, especially for polymorphic scenarios.
    ///
    /// # Default Implementation
    ///
    /// The default implementation writes:
    ///
    /// 1. Static type ID from [`fory_static_type_id`]
    /// 2. Rust's `std::any::TypeId` for runtime type identification
    ///
    /// ```rust,ignore
    /// fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
    ///     let rs_type_id = std::any::TypeId::of::<Self>();
    ///     context.write_any_typeinfo(Self::fory_static_type_id() as u32, rs_type_id)?;
    ///     Ok(())
    /// }
    /// ```
    ///
    /// # When to Override
    ///
    /// Override this method for:
    ///
    /// - **Built-in types**: Fory's internal types override for optimized performance
    /// - **Custom type ID schemes**: If you need special type identification logic
    ///
    /// For user types with custom serialization, the default implementation is typically sufficient.
    ///
    /// # Implementation Notes
    ///
    /// - Called by [`fory_write`] when `write_type_info` is true
    /// - Implemented by Fory for all built-in types with optimized paths
    /// - User types with custom serialization rarely need to override this
    ///
    /// [`fory_static_type_id`]: Serializer::fory_static_type_id
    /// [`fory_write`]: Serializer::fory_write
    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error>
    where
        Self: Sized,
    {
        // Serializer for internal types should overwrite this method for faster performance.
        let rs_type_id = std::any::TypeId::of::<Self>();
        context.write_any_typeinfo(Self::fory_static_type_id() as u32, rs_type_id)?;
        Ok(())
    }

    /// Entry point for deserialization.
    ///
    /// This method orchestrates the complete deserialization process, handling reference tracking,
    /// type information validation, and delegating to [`fory_read_data`] for the actual data deserialization.
    ///
    /// # Parameters
    ///
    /// * `read_ref_info` - When `true`, READS reference flag from buffer. When `false`, SKIPS reading ref flag.
    /// * `read_type_info` - When `true`, READS type information from buffer. When `false`, SKIPS reading type info.
    ///
    /// # Type Requirements
    ///
    /// This method requires `Self: Sized + ForyDefault` because:
    ///
    /// - **Sized**: Need to construct concrete instances
    /// - **ForyDefault**: Need to create default/null values for optional types
    ///
    /// # Default Implementation
    ///
    /// The default implementation handles standard non-nullable types:
    ///
    /// 1. If `read_ref_info` is true:
    ///    - Reads ref flag from buffer
    ///    - Returns `ForyDefault::fory_default()` for null values
    ///    - Handles ref tracking for shared references
    /// 2. If `read_type_info` is true, calls [`fory_read_type_info`]
    /// 3. Calls [`fory_read_data`] to read the actual data
    ///
    /// # When to Override
    ///
    /// Override this method for:
    ///
    /// - **Option types**: Need custom null handling logic
    /// - **Reference types** (Rc/Arc/Weak): Need custom ref tracking
    /// - **Polymorphic types**: Need to dispatch based on actual runtime type
    ///
    /// For regular types (structs, primitives, collections), the default implementation is sufficient.
    ///
    /// # Implementation Notes
    ///
    /// - Unlike [`fory_write`], this doesn't need `has_generics` parameter
    /// - Generic metadata is already in the buffer and parsed during read
    /// - Implemented by Fory for all built-in types
    /// - User types with custom serialization typically don't need to override this
    /// - Focus on implementing [`fory_read_data`] instead
    ///
    /// [`fory_read_data`]: Serializer::fory_read_data
    /// [`fory_read_type_info`]: Serializer::fory_read_type_info
    /// [`fory_write`]: Serializer::fory_write
    #[inline(always)]
    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        if read_ref_info {
            let ref_flag = context.reader.read_i8()?;
            match ref_flag {
                flag if flag == RefFlag::Null as i8 => Ok(Self::fory_default()),
                flag if flag == RefFlag::NotNullValue as i8 || flag == RefFlag::RefValue as i8 => {
                    if read_type_info {
                        Self::fory_read_type_info(context)?;
                    }
                    Self::fory_read_data(context)
                }
                flag if flag == RefFlag::Ref as i8 => {
                    Err(Error::invalid_ref("Invalid ref, current type is not a ref"))
                }
                other => Err(Error::invalid_data(format!("Unknown ref flag: {}", other))),
            }
        } else {
            if read_type_info {
                Self::fory_read_type_info(context)?;
            }
            Self::fory_read_data(context)
        }
    }

    /// Deserialize with pre-read type information.
    ///
    /// This method is used when type information has already been read from the buffer
    /// and needs to be passed to the deserialization logic. This is common in polymorphic
    /// deserialization scenarios where the runtime type differs from the static type.
    ///
    /// # Parameters
    ///
    /// * `read_ref_info` - When `true`, READS reference flag from buffer. When `false`, SKIPS reading ref flag.
    /// * `type_info` - Type information that has already been read ahead. DO NOT read type info again from buffer.
    ///
    /// # Important
    ///
    /// **DO NOT** read type info from the buffer in this method. The `type_info` parameter
    /// contains the already-read type metadata. Reading it again will cause buffer position errors.
    ///
    /// # Default Implementation
    ///
    /// The default implementation ignores the provided `type_info` and delegates to [`fory_read`]:
    ///
    /// ```rust,ignore
    /// fn fory_read_with_type_info(
    ///     context: &mut ReadContext,
    ///     read_ref_info: bool,
    ///     type_info: Rc<TypeInfo>,
    /// ) -> Result<Self, Error> {
    ///     // Ignore type_info since static type matches
    ///     Self::fory_read(context, read_ref_info, false) // read_type_info=false
    /// }
    /// ```
    ///
    /// This works for:
    ///
    /// - **Monomorphic types**: Static type matches runtime type
    /// - **Final types**: Types that don't participate in polymorphism
    /// - **User-defined types**: Types registered with Fory that don't require polymorphism
    ///
    /// # When to Override
    ///
    /// Override this method for:
    ///
    /// - **Trait objects** (dyn Trait): Need to dispatch to concrete implementation based on type_info
    /// - **Reference types with polymorphic targets**: Rc\<dyn Trait\>, Arc\<dyn Trait\>
    /// - **Custom polymorphic types**: Types with runtime type variation
    ///
    /// # Implementation Notes
    ///
    /// - Called by Fory's polymorphic deserialization infrastructure
    /// - The `type_info` parameter contains the actual runtime type ID
    /// - Implemented by Fory for all polymorphic types
    /// - User types with custom serialization rarely need to override this
    ///
    /// [`fory_read`]: Serializer::fory_read
    #[inline(always)]
    #[allow(unused_variables)]
    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        // Default implementation ignores the provided typeinfo because the static type matches.
        // Honor the supplied `read_ref_info` flag so callers can control whether ref metadata is present.
        Self::fory_read(context, read_ref_info, false)
    }

    /// **[USER IMPLEMENTATION REQUIRED]** Deserialize the type's data from the buffer.
    ///
    /// This is the core deserialization method that you must implement for custom types.
    /// It should read all the type's fields and data from the buffer in the same order
    /// they were written by [`fory_write_data`].
    ///
    /// # Parameters
    ///
    /// * `context` - Read context providing:
    ///   - `context.reader`: Buffer to read binary data
    ///   - `context.type_resolver`: Registry of type information
    ///   - `context.ref_resolver`: Resolver for shared/circular references (if enabled)
    ///
    /// # Type Requirements
    ///
    /// Requires `Self: Sized + ForyDefault`:
    ///
    /// - **Sized**: Need to construct concrete instances on the stack
    /// - **ForyDefault**: Need to create default/null values for optional fields
    ///
    /// # Reading Data
    ///
    /// Use methods on `context.reader` to read primitive types:
    ///
    /// - `read_i8()`, `read_i16()`, `read_i32()`, `read_i64()`: Signed integers
    /// - `read_u8()`, `read_u16()`, `read_u32()`, `read_u64()`: Unsigned integers
    /// - `read_f32()`, `read_f64()`: Floating point numbers
    /// - `read_bool()`: Boolean values
    /// - `read_bytes()`: Raw byte arrays
    ///
    /// For nested types that implement `Serializer`, call their deserialization:
    ///
    /// ```rust,ignore
    /// // For types that implement Serializer
    /// let field = T::fory_read(context, false, false)?;
    /// ```
    ///
    /// # Examples
    ///
    /// ## Simple struct with primitive fields
    ///
    /// ```rust
    /// use fory_core::{Serializer, ForyDefault};
    /// use fory_core::resolver::context::{ReadContext, WriteContext};
    /// use fory_core::error::Error;
    /// use std::any::Any;
    ///
    /// #[derive(Debug, PartialEq)]
    /// struct Point {
    ///     x: f64,
    ///     y: f64,
    /// }
    ///
    /// impl ForyDefault for Point {
    ///     fn fory_default() -> Self {
    ///         Point { x: 0.0, y: 0.0 }
    ///     }
    /// }
    ///
    /// impl Serializer for Point {
    ///     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
    ///         context.writer.write_f64(self.x);
    ///         context.writer.write_f64(self.y);
    ///         Ok(())
    ///     }
    ///
    ///     fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error>
    ///     where
    ///         Self: Sized + ForyDefault,
    ///     {
    ///         // Read fields in the same order as written
    ///         let x = context.reader.read_f64()?;
    ///         let y = context.reader.read_f64()?;
    ///         Ok(Point { x, y })
    ///     }
    ///
    ///     fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, Error> {
    ///         Self::fory_get_type_id(type_resolver)
    ///     }
    ///
    ///     fn as_any(&self) -> &dyn Any {
    ///         self
    ///     }
    /// }
    /// ```
    ///
    /// ## Struct with nested serializable types
    ///
    /// ```rust
    /// use fory_core::{Serializer, ForyDefault};
    /// use fory_core::resolver::context::{ReadContext, WriteContext};
    /// use fory_core::error::Error;
    /// use std::any::Any;
    ///
    /// #[derive(Debug, PartialEq)]
    /// struct Person {
    ///     name: String,
    ///     age: u32,
    ///     scores: Vec<i32>,
    /// }
    ///
    /// impl ForyDefault for Person {
    ///     fn fory_default() -> Self {
    ///         Person {
    ///             name: String::new(),
    ///             age: 0,
    ///             scores: Vec::new(),
    ///         }
    ///     }
    /// }
    ///
    /// impl Serializer for Person {
    ///     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
    ///         self.name.fory_write(context, false, false, false)?;
    ///         context.writer.write_u32(self.age);
    ///         self.scores.fory_write(context, false, false, true)?;
    ///         Ok(())
    ///     }
    ///
    ///     fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error>
    ///     where
    ///         Self: Sized + ForyDefault,
    ///     {
    ///         // Read nested types in the same order as written
    ///         let name = String::fory_read(context, false, false)?;
    ///         let age = context.reader.read_u32()?;
    ///         let scores = Vec::<i32>::fory_read(context, false, false)?;
    ///         Ok(Person { name, age, scores })
    ///     }
    ///
    ///     fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, Error> {
    ///         Self::fory_get_type_id(type_resolver)
    ///     }
    ///
    ///     fn as_any(&self) -> &dyn Any {
    ///         self
    ///     }
    /// }
    /// ```
    ///
    /// ## Struct with optional fields
    ///
    /// ```rust,ignore
    /// use fory_core::{Serializer, ForyDefault};
    /// use fory_core::resolver::context::{ReadContext, WriteContext};
    /// use fory_core::error::Error;
    /// use std::any::Any;
    ///
    /// #[derive(Debug, PartialEq)]
    /// struct Config {
    ///     name: String,
    ///     timeout: Option<u32>,
    /// }
    ///
    /// impl ForyDefault for Config {
    ///     fn fory_default() -> Self {
    ///         Config {
    ///             name: String::new(),
    ///             timeout: None,
    ///         }
    ///     }
    /// }
    ///
    /// impl Serializer for Config {
    ///     fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
    ///         self.name.fory_write(context, false, false, false)?;
    ///         // Option needs ref tracking to handle None
    ///         self.timeout.fory_write(context, true, false, false)?;
    ///         Ok(())
    ///     }
    ///
    ///     fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error>
    ///     where
    ///         Self: Sized + ForyDefault,
    ///     {
    ///         let name = String::fory_read(context, false, false)?;
    ///         // Read Option with ref tracking enabled
    ///         let timeout = Option::<u32>::fory_read(context, true, false)?;
    ///         Ok(Config { name, timeout })
    ///     }
    ///
    ///     fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, Error> {
    ///         Self::fory_get_type_id(type_resolver)
    ///     }
    ///
    ///     fn as_any(&self) -> &dyn Any {
    ///         self
    ///     }
    /// }
    /// ```
    ///
    /// # Implementation Guidelines
    ///
    /// 1. **Mirror write order**: Read fields in the exact same order as [`fory_write_data`] writes them
    /// 2. **Error propagation**: Use `?` operator to propagate read errors
    /// 3. **No metadata reading**: Don't read ref flags or type info; that's handled by [`fory_read`]
    /// 4. **Use Serializer for nested types**: Leverage existing implementations
    /// 5. **Handle errors gracefully**: Return descriptive errors for invalid data
    ///
    /// # Common Patterns
    ///
    /// ```rust,ignore
    /// // Reading primitives
    /// let count = context.reader.read_i32()?;
    ///
    /// // Reading nested Serializer types
    /// let nested = T::fory_read(context, false, false)?;
    ///
    /// // Reading collections
    /// let items = Vec::<T>::fory_read(context, false, false)?;
    ///
    /// // Reading optional types
    /// let optional = Option::<T>::fory_read(context, true, false)?;
    /// ```
    ///
    /// # Error Handling
    ///
    /// Return errors for:
    ///
    /// - **Buffer underflow**: Not enough data to read
    /// - **Invalid data**: Data doesn't match expected format
    /// - **Version mismatch**: Incompatible serialization format
    ///
    /// ```rust,ignore
    /// if value > MAX_ALLOWED {
    ///     return Err(Error::invalid_data(format!("Value {} exceeds maximum", value)));
    /// }
    /// ```
    ///
    /// # See Also
    ///
    /// - [`fory_write_data`]: Corresponding serialization method
    /// - [`fory_read`]: High-level deserialization entry point
    /// - [`ForyDefault`]: Trait for creating default values
    /// - [`ReadContext`]: Context providing buffer and resolver access
    ///
    /// [`fory_write_data`]: Serializer::fory_write_data
    /// [`fory_read`]: Serializer::fory_read
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault;

    /// Read and validate type metadata from the buffer.
    ///
    /// This method reads type information to verify that the data in the buffer
    /// matches the expected type. It's the counterpart to [`fory_write_type_info`].
    ///
    /// # Default Implementation
    ///
    /// The default implementation reads and validates the type info:
    ///
    /// ```rust,ignore
    /// fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
    ///     context.read_any_typeinfo()?;
    ///     Ok(())
    /// }
    /// ```
    ///
    /// # When to Override
    ///
    /// Override this method for:
    ///
    /// - **Built-in types**: Fory's internal types override for optimized performance
    /// - **Custom validation logic**: If you need special type checking
    ///
    /// For user types with custom serialization, the default implementation is typically sufficient.
    ///
    /// # Implementation Notes
    ///
    /// - Called by [`fory_read`] when `read_type_info` is true
    /// - Implemented by Fory for all built-in types with optimized paths
    /// - User types with custom serialization rarely need to override this
    ///
    /// [`fory_write_type_info`]: Serializer::fory_write_type_info
    /// [`fory_read`]: Serializer::fory_read
    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error>
    where
        Self: Sized,
    {
        // Serializer for internal types should overwrite this method for faster performance.
        context.read_any_typeinfo()?;
        Ok(())
    }

    /// Check if this type is `Option<T>`.
    ///
    /// # Returns
    ///
    /// - `true` if the type is `Option<T>`
    /// - `false` for all other types (default)
    ///
    /// # Implementation Notes
    ///
    /// - Implemented by Fory for `Option<T>` to return `true`
    /// - User types with custom serialization should not override this
    /// - Used internally for null handling optimization
    #[inline(always)]
    fn fory_is_option() -> bool
    where
        Self: Sized,
    {
        false
    }

    /// Check if this instance represents a `None` value.
    ///
    /// This method is used for runtime checking of optional values.
    ///
    /// # Returns
    ///
    /// - `true` if the instance is `Option::None`
    /// - `false` for all other values (default)
    ///
    /// # Implementation Notes
    ///
    /// - Implemented by Fory for `Option<T>` to check None state
    /// - User types with custom serialization should not override this
    /// - Used with [`fory_is_option`] for null handling
    ///
    /// [`fory_is_option`]: Serializer::fory_is_option
    #[inline(always)]
    fn fory_is_none(&self) -> bool {
        false
    }

    /// Check if this type supports polymorphic serialization.
    ///
    /// Polymorphic types can have different runtime types than their static type,
    /// requiring special handling during serialization and deserialization.
    ///
    /// # Returns
    ///
    /// - `true` if the type is polymorphic (trait objects, dynamic dispatch)
    /// - `false` for monomorphic types (default)
    ///
    /// # Examples of Polymorphic Types
    ///
    /// - Trait objects: `Box<dyn Trait>`, `Rc<dyn Trait>`, `Arc<dyn Trait>`
    ///
    /// # Implementation Notes
    ///
    /// - Implemented by Fory for all polymorphic types
    /// - User types with custom serialization typically should not override this
    /// - Used to determine if type info must be written
    #[inline(always)]
    fn fory_is_polymorphic() -> bool
    where
        Self: Sized,
    {
        false
    }

    /// Check if this type is a shared reference (Rc/Arc).
    ///
    /// Shared references require special handling for reference tracking
    /// to support proper sharing and circular reference detection.
    ///
    /// # Returns
    ///
    /// - `true` if the type is `Rc<T>` or `Arc<T>`
    /// - `false` for all other types (default)
    ///
    /// # Implementation Notes
    ///
    /// - Implemented by Fory for `Rc<T>` and `Arc<T>`
    /// - User types with custom serialization should not override this
    /// - Used for reference tracking and deduplication
    #[inline(always)]
    fn fory_is_shared_ref() -> bool
    where
        Self: Sized,
    {
        false
    }

    /// Get the static Fory type ID for this type.
    ///
    /// Type IDs are Fory's internal type identification system, separate from
    /// Rust's `std::any::TypeId`. They're used for cross-language serialization
    /// and protocol compatibility.
    ///
    /// # Returns
    ///
    /// - For built-in types: Specific type ID (e.g., `TypeId::I32`, `TypeId::STRING`)
    /// - For external types: `TypeId::EXT` (default)
    ///
    /// # Type ID Categories
    ///
    /// - **Primitives**: `I8`, `I16`, `I32`, `I64`, `U8`, `U16`, `U32`, `U64`, `F32`, `F64`, `BOOL`
    /// - **Strings**: `STRING`
    /// - **Collections**: `LIST`, `MAP`, `SET`
    /// - **Structs**: `STRUCT`
    /// - **Enums**: `ENUM`
    /// - **User types**: `EXT` for user types with custom serialization logic
    ///
    /// # Implementation Notes
    ///
    /// - Default returns `TypeId::EXT` for user types
    /// - Fory implements specific type IDs for all built-in types
    /// - User types with custom serialization should use the default
    /// - This is a compile-time constant
    #[inline(always)]
    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        // set to ext to simplify the user defined serializer.
        // serializer for other types will override this method.
        TypeId::EXT
    }

    /// Get the registered type ID from the type resolver.
    ///
    /// This method looks up the type's assigned ID in the type registry,
    /// which is required for serialization of external types.
    ///
    /// # Parameters
    ///
    /// * `type_resolver` - The type registry to query
    ///
    /// # Returns
    ///
    /// The numeric type ID assigned to this type during registration.
    ///
    /// # Errors
    ///
    /// Returns an error if the type is not registered with the resolver.
    ///
    /// # Implementation Notes
    ///
    /// - Default implementation looks up via `std::any::TypeId`
    /// - User types must be registered before serialization
    /// - Built-in types have pre-assigned IDs
    /// - This is typically called by [`fory_type_id_dyn`]
    ///
    /// [`fory_type_id_dyn`]: Serializer::fory_type_id_dyn
    #[inline(always)]
    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error>
    where
        Self: Sized,
    {
        Ok(type_resolver
            .get_type_info(&std::any::TypeId::of::<Self>())?
            .get_type_id())
    }

    /// **[USER IMPLEMENTATION REQUIRED]** Get the runtime type ID for this instance.
    ///
    /// This method returns the type ID for the actual runtime type of the instance,
    /// which may differ from the static type for polymorphic types.
    ///
    /// # Parameters
    ///
    /// * `type_resolver` - The type registry to query
    ///
    /// # Returns
    ///
    /// The numeric type ID for this instance's runtime type.
    ///
    /// # Implementation Pattern
    ///
    /// For most types, simply delegate to [`fory_get_type_id`]:
    ///
    /// ```rust,ignore
    /// fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
    ///     Self::fory_get_type_id(type_resolver)
    /// }
    /// ```
    ///
    /// For polymorphic types, return the actual runtime type:
    ///
    /// ```rust,ignore
    /// fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
    ///     // Get the actual type ID based on runtime type
    ///     self.get_actual_type().fory_get_type_id(type_resolver)
    /// }
    /// ```
    ///
    /// [`fory_get_type_id`]: Serializer::fory_get_type_id
    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error>;

    /// Get Rust's `std::any::TypeId` for this instance.
    ///
    /// Returns the runtime type identifier from Rust's type system.
    /// This is used for type resolution and registration.
    ///
    /// # Returns
    ///
    /// The `std::any::TypeId` for this instance's concrete type.
    ///
    /// # Implementation Notes
    ///
    /// - Default implementation uses `TypeId::of::<Self>()`
    /// - User types with custom serialization should not override this
    /// - Used by type resolution infrastructure
    #[inline(always)]
    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        std::any::TypeId::of::<Self>()
    }

    /// Hint for buffer pre-allocation size.
    ///
    /// This method provides a size hint for how much buffer space to pre-allocate
    /// before serializing this type. Accurate hints improve performance by reducing
    /// buffer reallocations.
    ///
    /// # Returns
    ///
    /// - Estimated maximum size in bytes for this type
    /// - `0` if unknown or variable size (default)
    ///
    /// # Implementation Guidelines
    ///
    /// Return a conservative upper bound:
    ///
    /// ```rust,ignore
    /// fn fory_reserved_space() -> usize {
    ///     std::mem::size_of::<Self>() + overhead
    /// }
    /// ```
    ///
    /// # Examples
    ///
    /// - Fixed-size struct: `std::mem::size_of::<Self>() + metadata_size`
    /// - Variable-size: Return `0` (collections, strings)
    ///
    /// # Implementation Notes
    ///
    /// - Default returns `0` (no pre-allocation)
    /// - Fory implements size hints for fixed-size types
    /// - User types with custom serialization can override for performance
    /// - Overestimation wastes memory; underestimation requires reallocation
    #[inline(always)]
    fn fory_reserved_space() -> usize
    where
        Self: Sized,
    {
        std::mem::size_of::<Self>()
    }

    /// Indicate whether this type should be treated as a reference type in cross-language (xlang) serialization.
    ///
    /// In cross-language scenarios, type systems differ between languages. For example:
    /// - In Java: `String`, `List`, `Map` are reference types (need RefFlag)
    /// - In Rust: `String`, `Vec`, `HashMap` are value types (by default, no RefFlag)
    ///
    /// This method bridges the gap by allowing Rust types to declare their cross-language reference semantics.
    ///
    /// # Returns
    ///
    /// - `true` if this type should be treated as a reference type in xlang mode
    ///   (will write RefFlag during serialization)
    /// - `false` if this type should be treated as a value type in xlang mode
    ///   (will not write RefFlag, default)
    ///
    /// # Type Mapping Guidelines
    ///
    /// | Rust Type | Java Type | Should Return |
    /// |-----------|-----------|---------------|
    /// | `i32`, `f64`, `bool` | `int`, `double`, `boolean` | `false` |
    /// | `Option<i32>` | `Integer` | `false` (Option handles null) |
    /// | `String` | `String` | `true` |
    /// | `Vec<T>` | `List<T>` | `true` |
    /// | `HashMap<K,V>` | `Map<K,V>` | `true` |
    /// | User struct | Java object | `true` |
    ///
    /// # Default Implementation
    ///
    /// Returns `false` for all types. Override for types that correspond to reference types in other languages.
    ///
    /// # Examples
    ///
    /// ```rust,ignore
    /// // String should be treated as reference type in Java
    /// impl Serializer for String {
    ///     fn fory_is_xlang_ref_type() -> bool {
    ///         true
    ///     }
    /// }
    ///
    /// // i32 is primitive in Java (int)
    /// impl Serializer for i32 {
    ///     fn fory_is_xlang_ref_type() -> bool {
    ///         false  // default
    ///     }
    /// }
    /// ```
    ///
    /// # Implementation Notes
    ///
    /// - Only affects behavior when `context.is_xlang()` is true
    /// - Used in [`fory_write`] to determine RefFlag behavior
    /// - Fory implements this for all built-in types
    /// - User types should override this for proper xlang interop
    ///
    /// [`fory_write`]: Serializer::fory_write
    #[inline(always)]
    fn fory_is_xlang_ref_type() -> bool
    where
        Self: Sized,
    {
        false
    }

    /// **[USER IMPLEMENTATION REQUIRED]** Downcast to `&dyn Any` for dynamic type checking.
    ///
    /// This method enables runtime type checking and downcasting, required for
    /// Fory's type system integration.
    ///
    /// # Returns
    ///
    /// A reference to this instance as `&dyn Any`.
    ///
    /// # Implementation Pattern
    ///
    /// Always implement this by returning `self`:
    ///
    /// ```rust,ignore
    /// fn as_any(&self) -> &dyn Any {
    ///     self
    /// }
    /// ```
    ///
    /// # Implementation Notes
    ///
    /// - Required for all types implementing `Serializer`
    /// - Enables `downcast_ref::<T>()` on serialized values
    /// - Used by Fory's polymorphic deserialization
    fn as_any(&self) -> &dyn Any;
}

/// Extended trait for struct and enum serialization.
///
/// `StructSerializer` extends [`Serializer`] with capabilities specific to struct and enum types,
/// including field metadata, schema evolution, and compatibility features. This trait is used
/// internally by Fory and automatically implemented by the derive macro.
///
/// # ⚠️ Important: Do NOT Implement This for Custom User Types
///
/// **User types with custom serialization should NOT implement this trait.** This trait is:
///
/// - **Only for derive-based types**: Automatically implemented by `#[derive(ForyObject)]`
/// - **Internal infrastructure**: Used by Fory's struct/enum deserialization engine
/// - **Not for manual implementation**: Unless you're extending Fory's core functionality
///
/// For user types that need custom serialization logic (EXT types), implement only the [`Serializer`] trait.
///
/// # Purpose
///
/// This trait enables Fory's advanced struct/enum features:
///
/// - **Field introspection**: Access to struct field metadata for serialization
/// - **Schema evolution**: Forward and backward compatibility for struct/enum changes
/// - **Compatible deserialization**: Read structs with different field sets (added/removed fields)
/// - **Type indexing**: Efficient type lookup for struct/enum types
/// - **Hash-based identification**: Support for versioned struct schemas
///
/// # Automatic Implementation
///
/// The `#[derive(ForyObject)]` macro automatically implements this trait for structs and enums:
///
/// ```rust,ignore
/// #[derive(ForyObject)]
/// struct Point {
///     x: f64,
///     y: f64,
/// }
/// // StructSerializer is automatically implemented
/// // Users should never implement this manually for custom serialization
/// ```
///
/// # Usage by Fory
///
/// Fory uses this trait internally for:
///
/// - **Struct serialization**: Writing field metadata and data
/// - **Enum serialization**: Writing variant information
/// - **Compatible reads**: Handling schema evolution scenarios
/// - **Type registration**: Managing struct/enum type metadata
///
/// # Implementation Notes
///
/// - **Do not implement manually** for user types with custom serialization (EXT types)
/// - **Only via derive macro**: `#[derive(ForyObject)]` is the only supported way
/// - **Internal use only**: Methods are called by Fory's serialization engine
/// - **Schema evolution**: Enables adding/removing fields without breaking compatibility
///
/// # See Also
///
/// - [`Serializer`]: Base trait that user types with custom serialization should implement
/// - [`FieldInfo`]: Metadata about struct fields
///
/// [`Serializer`]: Serializer
pub trait StructSerializer: Serializer + 'static {
    /// Get metadata about this struct's fields.
    ///
    /// Returns information about each field in the struct, including names,
    /// types, and offsets. This is used for schema evolution and compatibility.
    ///
    /// # Parameters
    ///
    /// * `type_resolver` - The type registry for looking up field types
    ///
    /// # Returns
    ///
    /// A vector of [`FieldInfo`] describing each field, or an empty vector by default.
    ///
    /// # Implementation Notes
    ///
    /// - Implemented automatically by `#[derive(ForyObject)]` macro
    /// - Default returns empty vector (no fields)
    /// - **Do not implement** for user types with custom serialization (EXT types)
    #[allow(unused_variables)]
    fn fory_fields_info(type_resolver: &TypeResolver) -> Result<Vec<FieldInfo>, Error> {
        Ok(Vec::default())
    }

    /// Get the type index for fast struct type lookup.
    ///
    /// Type index provides O(1) lookup for struct types in the type registry.
    ///
    /// # Returns
    ///
    /// A unique index assigned to this struct type.
    ///
    /// # Implementation Notes
    ///
    /// - Implemented automatically by `#[derive(ForyObject)]` macro
    /// - Used for efficient type resolution
    /// - **Do not implement** for user types with custom serialization (EXT types)
    fn fory_type_index() -> u32 {
        unimplemented!()
    }

    /// Get the actual type ID considering registration and compatibility modes.
    ///
    /// This method computes the effective type ID based on how the type was registered
    /// and whether compatibility mode is enabled.
    ///
    /// # Parameters
    ///
    /// * `type_id` - The base type ID
    /// * `register_by_name` - Whether type was registered by name (vs by hash)
    /// * `compatible` - Whether compatibility mode is enabled
    ///
    /// # Returns
    ///
    /// The actual type ID to use for serialization/deserialization.
    ///
    /// # Implementation Notes
    ///
    /// - Default delegates to `struct_::actual_type_id`
    /// - Handles type ID transformations for compatibility
    /// - **Do not override** for user types with custom serialization (EXT types)
    #[inline(always)]
    fn fory_actual_type_id(type_id: u32, register_by_name: bool, compatible: bool) -> u32 {
        struct_::actual_type_id(type_id, register_by_name, compatible)
    }

    /// Get sorted field names for consistent serialization order.
    ///
    /// Returns field names in sorted order to ensure deterministic serialization
    /// and enable schema evolution.
    ///
    /// # Returns
    ///
    /// A static slice of field names in sorted order, or empty slice by default.
    ///
    /// # Implementation Notes
    ///
    /// - Implemented automatically by `#[derive(ForyObject)]` macro
    /// - Used for field ordering and compatibility
    /// - Field order affects wire format
    /// - **Do not implement** for user types with custom serialization (EXT types)
    fn fory_get_sorted_field_names() -> &'static [&'static str] {
        &[]
    }

    /// Deserialize a struct with schema compatibility support.
    ///
    /// This method enables reading structs even when the reader's schema differs
    /// from the writer's schema. It handles:
    ///
    /// - Missing fields (uses defaults)
    /// - Extra fields (skips them)
    /// - Reordered fields
    /// - Type changes (within compatibility rules)
    ///
    /// # Parameters
    ///
    /// * `context` - Read context with buffer and resolvers
    /// * `type_info` - Type metadata from the serialized data
    ///
    /// # Returns
    ///
    /// A deserialized instance with compatible field mapping.
    ///
    /// # Errors
    ///
    /// Returns an error if:
    ///
    /// - Required fields are missing without defaults
    /// - Field types are incompatible
    /// - Data is corrupted
    ///
    /// # Implementation Notes
    ///
    /// - Implemented automatically by `#[derive(ForyObject)]` macro
    /// - Enables forward and backward compatibility
    /// - Uses field names for matching when possible
    /// - Falls back to field order for unnamed fields
    /// - **Do not implement** for user types with custom serialization (EXT types)
    fn fory_read_compatible(
        context: &mut ReadContext,
        type_info: Rc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized;
}

/// Serializes an object implementing `Serializer` to the write context.
///
/// This is a convenience wrapper around `T::fory_write_data` that delegates to the type's
/// serialization implementation.
#[inline(always)]
pub fn write_data<T: Serializer>(this: &T, context: &mut WriteContext) -> Result<(), Error> {
    T::fory_write_data(this, context)
}

/// Deserializes an object implementing `Serializer` from the read context.
///
/// This is a convenience wrapper around `T::fory_read_data` that delegates to the type's
/// deserialization implementation. Requires `ForyDefault` for instance creation.
#[inline(always)]
pub fn read_data<T: Serializer + ForyDefault>(context: &mut ReadContext) -> Result<T, Error> {
    T::fory_read_data(context)
}
