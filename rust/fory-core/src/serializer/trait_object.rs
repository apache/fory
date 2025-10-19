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

// Re-exports for use in macros - these are needed for macro expansion in user crates
// Even though they appear unused in this file, they are used by the macro-generated code

use crate::ensure;
use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::RefFlag;
use std::sync::Arc;

/// Helper macro for common type resolution and downcasting pattern
#[macro_export]
macro_rules! downcast_and_serialize {
    ($any_ref:expr, $context:expr, $trait_name:ident, $($impl_type:ty),+) => {{
        $(
            if $any_ref.type_id() == std::any::TypeId::of::<$impl_type>() {
                if let Some(concrete) = $any_ref.downcast_ref::<$impl_type>() {
                    concrete.fory_write_data($context)?;
                    return Ok(());
                }
            }
        )*
        return Err(fory_core::Error::type_error(format!("Failed to downcast to any registered type for trait {}", stringify!($trait_name))));
    }};
}

/// Helper macro for common type resolution and deserialization pattern
#[macro_export]
macro_rules! resolve_and_deserialize {
    ($fory_type_id:expr, $context:expr, $constructor:expr, $trait_name:ident, $($impl_type:ty),+) => {{
        $(
            if let Some(registered_type_id) = $context.get_type_resolver().get_fory_type_id(std::any::TypeId::of::<$impl_type>()) {
                if $fory_type_id == registered_type_id {
                    let concrete_obj = <$impl_type as fory_core::Serializer>::fory_read_data($context)?;
                    return Ok($constructor(concrete_obj));
                }
            }
        )*
        Err(fory_core::Error::type_error(
            format!("Type ID {} not registered for trait {}", $fory_type_id, stringify!($trait_name))
        ))
    }};
}

/// Macro to register trait object conversions for custom traits.
///
/// This macro automatically generates serializers for `Box<dyn Trait>` trait objects.
/// Due to Rust's orphan rules, only `Box<dyn Trait>` is supported for user-defined traits.
/// For `Rc<dyn Trait>` and `Arc<dyn Trait>`, wrapper types are generated (e.g., `TraitRc`, `TraitArc`),
/// either you use the wrapper types or use the `Rc<dyn Any>` or `Arc<dyn Any>` instead if it's not
/// inside struct fields. For struct fields, you can use the `Rc<dyn Trait>`, `Arc<dyn Trait>` directly,
/// fory will generate converters for `Rc<dyn Trait>` and `Arc<dyn Trait>` to convert to wrapper for
/// serialization/ deserialization automatically.
///
/// The macro generates:
/// - `Serializer` implementation for `Box<dyn Trait>`
/// - `Default` implementation for `Box<dyn Trait>` (uses first registered type)
///
/// **Note**: Your trait must extend the `Serializer` trait.
/// The `as_any()` method is automatically provided by the `Serializer` trait.
///
/// # Example
///
/// ```rust,ignore
/// use fory_core::{fory::Fory, register_trait_type, serializer::Serializer};
/// use fory_derive::ForyObject;
///
/// trait Animal: Serializer {
///     fn speak(&self) -> String;
///     fn name(&self) -> &str;
/// }
///
/// #[derive(ForyObject, Debug)]
/// struct Dog { name: String }
///
/// #[derive(ForyObject, Debug)]
/// struct Cat { name: String }
///
/// impl Animal for Dog {
///     fn speak(&self) -> String { "Woof!".to_string() }
///     fn name(&self) -> &str { &self.name }
/// }
///
/// impl Animal for Cat {
///     fn speak(&self) -> String { "Meow!".to_string() }
///     fn name(&self) -> &str { &self.name }
/// }
///
/// register_trait_type!(Animal, Dog, Cat);
///
/// # fn main() {
/// let mut fory = Fory::default().compatible(true);
/// fory.register::<Dog>(100);
/// fory.register::<Cat>(101);
///
/// let dog: Box<dyn Animal> = Box::new(Dog { name: "Rex".to_string() });
/// let bytes = fory.serialize(&dog);
/// let decoded: Box<dyn Animal> = fory.deserialize(&bytes).unwrap();
/// assert_eq!(decoded.name(), "Rex");
/// # }
/// ```
#[macro_export]
macro_rules! register_trait_type {
    ($trait_name:ident, $($impl_type:ty),+ $(,)?) => {
        // 1. Generate Box<dyn Trait> serializer (existing functionality)
        // Default implementation using first registered type
        impl std::default::Default for Box<dyn $trait_name> {
            fn default() -> Self {
                Box::new(<register_trait_type!(@first_type $($impl_type),+) as std::default::Default>::default())
            }
        }

        impl $crate::serializer::ForyDefault for Box<dyn $trait_name> {
            fn fory_default() -> Self {
                Box::new(<register_trait_type!(@first_type $($impl_type),+) as std::default::Default>::default())
            }
        }

        // 2. Auto-generate Rc wrapper type and conversions
        $crate::generate_smart_pointer_wrapper!(
            std::rc::Rc,
            Rc,
            std::rc::Rc::get_mut,
            $trait_name,
            try_write_rc_ref,
            get_rc_ref,
            store_rc_ref,
            $($impl_type),+
        );

        // 3. Auto-generate Arc wrapper type and conversions
        $crate::generate_smart_pointer_wrapper!(
            std::sync::Arc,
            Arc,
            std::sync::Arc::get_mut,
            $trait_name,
            try_write_arc_ref,
            get_arc_ref,
            store_arc_ref,
            $($impl_type),+
        );

        // 4. Serializer implementation for Box<dyn Trait> (existing functionality)
        impl fory_core::Serializer for Box<dyn $trait_name> {
            fn fory_write(&self, context: &mut fory_core::WriteContext, write_ref_info: bool, write_type_info: bool, has_generics: bool) -> Result<(), fory_core::Error> {
                let any_ref = <dyn $trait_name as fory_core::Serializer>::as_any(&**self);
                fory_core::serializer::write_box_any(any_ref, context, write_ref_info, write_type_info, has_generics)
            }

            fn fory_write_data(&self, context: &mut fory_core::WriteContext) -> Result<(), fory_core::Error> {
                let any_ref = <dyn $trait_name as fory_core::Serializer>::as_any(&**self);
                fory_core::serializer::write_box_any(any_ref, context, false, false, false)
            }

            fn fory_write_data_generic(&self, context: &mut fory_core::WriteContext, has_generics: bool) -> Result<(), fory_core::Error> {
                let any_ref = <dyn $trait_name as fory_core::Serializer>::as_any(&**self);
                fory_core::serializer::write_box_any(any_ref, context, false, false, has_generics)
            }

            fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, fory_core::Error> {
                let any_ref = <dyn $trait_name as fory_core::Serializer>::as_any(&**self);
                let concrete_type_id = any_ref.type_id();
                type_resolver
                    .get_fory_type_id(concrete_type_id)
                    .ok_or_else(|| fory_core::Error::type_error("Type not registered for trait object"))
            }

            fn fory_is_polymorphic() -> bool {
                true
            }

            fn fory_write_type_info(_context: &mut fory_core::WriteContext) -> Result<(), fory_core::Error> {
                $crate::not_allowed!("fory_write_type_info should not be called directly on polymorphic Box<dyn {}> trait object", stringify!($trait_name))
            }

            fn fory_read_type_info(_context: &mut fory_core::ReadContext) -> Result<(), fory_core::Error> {
                $crate::not_allowed!("fory_read_type_info should not be called directly on polymorphic Box<dyn {}> trait object", stringify!($trait_name))
            }

            fn fory_read(context: &mut fory_core::ReadContext, read_ref_info: bool, read_type_info: bool) -> Result<Self, fory_core::Error> {
                let boxed_any = fory_core::serializer::read_box_any(context, read_ref_info, read_type_info, None)?;
                $(
                    if boxed_any.is::<$impl_type>() {
                        let concrete = boxed_any.downcast::<$impl_type>()
                            .map_err(|_| fory_core::Error::type_error("Downcast failed"))?;
                        let ptr = Box::new(*concrete);
                        return Ok(Self::from(ptr));
                    }
                )*
                Err(fory_core::Error::type_error(
                    format!("Deserialized type does not implement trait {}", stringify!($trait_name))
                ))
            }

            fn fory_read_with_type_info(
                context: &mut fory_core::ReadContext,
                read_ref_info: bool,
                type_info: std::sync::Arc<fory_core::TypeInfo>,
            ) -> Result<Self, fory_core::Error>
            where
                Self: Sized + fory_core::ForyDefault,
            {
                let boxed_any = fory_core::serializer::read_box_any(context, read_ref_info, false, Some(type_info))?;
                $(
                    if boxed_any.is::<$impl_type>() {
                        let concrete = boxed_any.downcast::<$impl_type>()
                            .map_err(|_| fory_core::Error::type_error("Downcast failed"))?;
                        let ptr = Box::new(*concrete);
                        return Ok(Self::from(ptr));
                    }
                )*
                Err(fory_core::Error::type_error(
                    format!("Deserialized type does not implement trait {}", stringify!($trait_name))
                ))
            }

            fn fory_read_data(_context: &mut fory_core::ReadContext) -> Result<Self, fory_core::Error> {
                // This should not be called for polymorphic types like Box<dyn Trait>
                // The fory_read method handles the polymorphic dispatch
                $crate::not_allowed!("fory_read_data should not be called directly on polymorphic Box<dyn {}> trait object", stringify!($trait_name))
            }

            fn fory_get_type_id(_type_resolver: &fory_core::TypeResolver) -> Result<u32, fory_core::Error> {
                $crate::not_allowed!("fory_get_type_id should not be called directly on polymorphic Box<dyn {}> trait object", stringify!($trait_name))
            }

            fn fory_static_type_id() -> fory_core::TypeId {
                fory_core::TypeId::UNKNOWN
            }

            fn fory_reserved_space() -> usize {
                $crate::types::SIZE_OF_REF_AND_TYPE
            }

            fn fory_concrete_type_id(&self) -> std::any::TypeId {
                <dyn $trait_name as fory_core::Serializer>::as_any(&**self).type_id()
            }

            fn as_any(&self) -> &dyn std::any::Any {
                <dyn $trait_name as fory_core::Serializer>::as_any(&**self)
            }
        }
    };

    // Helper to get first type for Default impl
    (@first_type $first_type:ty $(, $rest:ty)*) => {
        $first_type
    };
}

/// Unified macro to generate smart pointer wrapper types for traits
/// Supports both Rc and Arc pointer types
#[macro_export]
macro_rules! generate_smart_pointer_wrapper {
    ($ptr_path:path, $ptr_name:ident, $get_mut:path, $trait_name:ident, $try_write_ref:ident, $get_ref:ident, $store_ref:ident, $($impl_type:ty),+ $(,)?) => {
        $crate::paste::paste! {
            #[derive(Clone)]
            pub(crate) struct [<$trait_name $ptr_name>]($ptr_path<dyn $trait_name>);

            impl [<$trait_name $ptr_name>] {
                pub(crate) fn new(inner: $ptr_path<dyn $trait_name>) -> Self {
                    Self(inner)
                }

                pub(crate) fn into_inner(self) -> $ptr_path<dyn $trait_name> {
                    self.0
                }

                pub(crate) fn unwrap(self) -> $ptr_path<dyn $trait_name> {
                    self.0
                }

                pub(crate) fn as_ref(&self) -> &dyn $trait_name {
                    &*self.0
                }
            }

            impl std::ops::Deref for [<$trait_name $ptr_name>] {
                type Target = dyn $trait_name;

                fn deref(&self) -> &Self::Target {
                    &*self.0
                }
            }

            impl std::ops::DerefMut for [<$trait_name $ptr_name>] {
                fn deref_mut(&mut self) -> &mut Self::Target {
                    $get_mut(&mut self.0)
                        .expect(&format!("Cannot get mutable reference to {} with multiple strong references", stringify!($ptr_name)))
                }
            }

            impl From<$ptr_path<dyn $trait_name>> for [<$trait_name $ptr_name>] {
                fn from(ptr: $ptr_path<dyn $trait_name>) -> Self {
                    Self::new(ptr)
                }
            }

            impl From<[<$trait_name $ptr_name>]> for $ptr_path<dyn $trait_name> {
                fn from(wrapper: [<$trait_name $ptr_name>]) -> Self {
                    wrapper.into_inner()
                }
            }

            impl std::default::Default for [<$trait_name $ptr_name>] {
                fn default() -> Self {
                    Self($ptr_path::new(<$crate::register_trait_type!(@first_type $($impl_type),+) as std::default::Default>::default()))
                }
            }

            impl $crate::serializer::ForyDefault for [<$trait_name $ptr_name>] {
                fn fory_default() -> Self {
                    Self($ptr_path::new(<$crate::register_trait_type!(@first_type $($impl_type),+) as std::default::Default>::default()))
                }
            }

            impl std::fmt::Debug for [<$trait_name $ptr_name>] {
                fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                    let any_obj = <dyn $trait_name as fory_core::Serializer>::as_any(&*self.0);
                    $(
                        if let Some(concrete) = any_obj.downcast_ref::<$impl_type>() {
                            return write!(f, concat!(stringify!($trait_name), stringify!($ptr_name), "({:?})"), concrete);
                        }
                    )*
                    write!(f, concat!(stringify!($trait_name), stringify!($ptr_name), "({:p})"), &*self.0)
                }
            }

            $crate::impl_smart_pointer_serializer!(
                [<$trait_name $ptr_name>],
                $ptr_path<dyn $trait_name>,
                $ptr_path::new,
                $trait_name,
                $try_write_ref,
                $get_ref,
                $store_ref,
                $($impl_type),+
            );
        }
    };
}

/// Macro to read smart pointer trait objects (Rc<dyn Trait>, Arc<dyn Trait>)
/// This macro handles ref tracking and directly constructs the trait object from concrete types
#[macro_export]
macro_rules! read_ptr_trait_object {
    ($context:expr, $read_ref_info:expr, $read_type_info:expr, $type_info:expr, $pointer_type:ty, $trait_name:ident, $constructor_expr:expr, $get_ref:ident, $store_ref:ident, $($impl_type:ty),+) => {{
        let ref_flag = if $read_ref_info {
            $context.ref_reader.read_ref_flag(&mut $context.reader)?
        } else {
            fory_core::RefFlag::NotNullValue
        };
        match ref_flag {
            fory_core::RefFlag::Null => Err(fory_core::Error::invalid_ref(format!("smart pointer to dyn {} cannot be null", stringify!($trait_name)))),
            fory_core::RefFlag::Ref => {
                let ref_id = $context.ref_reader.read_ref_id(&mut $context.reader)?;
                let ptr_ref = $context.ref_reader.$get_ref::<dyn $trait_name>(ref_id)
                    .ok_or_else(|| fory_core::Error::invalid_data(format!("dyn {} reference {} not found", stringify!($trait_name), ref_id)))?;
                Ok(Self::from(ptr_ref))
            }
            fory_core::RefFlag::NotNullValue => {
                $context.inc_depth()?;
                let typeinfo = if $read_type_info {
                    $context.read_any_typeinfo()?
                } else {
                    $type_info.ok_or_else(|| fory_core::Error::type_error("No type info found for read"))?
                };
                let fory_type_id = typeinfo.get_type_id();
                $(
                    if let Some(registered_type_id) = $context.get_type_resolver().get_fory_type_id(std::any::TypeId::of::<$impl_type>()) {
                        if fory_type_id == registered_type_id {
                            let concrete_obj = <$impl_type as fory_core::Serializer>::fory_read_data($context)?;
                            $context.dec_depth();
                            let ptr = $constructor_expr(concrete_obj) as $pointer_type;
                            return Ok(Self::from(ptr));
                        }
                    }
                )*
                $context.dec_depth();
                Err(fory_core::Error::type_error(
                    format!("Type ID {} not registered for trait {}", fory_type_id, stringify!($trait_name))
                ))
            }
            fory_core::RefFlag::RefValue => {
                $context.inc_depth()?;
                let typeinfo = if $read_type_info {
                    $context.read_any_typeinfo()?
                } else {
                    $type_info.ok_or_else(|| fory_core::Error::type_error("No type info found for read"))?
                };
                let fory_type_id = typeinfo.get_type_id();
                $(
                    if let Some(registered_type_id) = $context.get_type_resolver().get_fory_type_id(std::any::TypeId::of::<$impl_type>()) {
                        if fory_type_id == registered_type_id {
                            let concrete_obj = <$impl_type as fory_core::Serializer>::fory_read_data($context)?;
                            $context.dec_depth();
                            let ptr = $constructor_expr(concrete_obj) as $pointer_type;
                            let wrapper = Self::from(ptr.clone());
                            $context.ref_reader.$store_ref(ptr);
                            return Ok(wrapper);
                        }
                    }
                )*
                $context.dec_depth();
                Err(fory_core::Error::type_error(
                    format!("Type ID {} not registered for trait {}", fory_type_id, stringify!($trait_name))
                ))
            }
        }
    }};
}

/// Shared serializer implementation for smart pointer wrappers
#[macro_export]
macro_rules! impl_smart_pointer_serializer {
    ($wrapper_name:ident, $pointer_type:ty, $constructor_expr:expr, $trait_name:ident, $try_write_ref:ident, $get_ref:ident, $store_ref:ident, $($impl_type:ty),+) => {
        impl fory_core::Serializer for $wrapper_name {
            fn fory_write(&self, context: &mut fory_core::WriteContext, write_ref_info: bool, write_type_info: bool, has_generics: bool) -> Result<(), fory_core::Error> {
                if !write_ref_info || !context.ref_writer.$try_write_ref(&mut context.writer, &self.0) {
                    let any_obj = <dyn $trait_name as fory_core::Serializer>::as_any(&*self.0);
                    let concrete_type_id = any_obj.type_id();
                    let typeinfo = if write_type_info {
                         context.write_any_typeinfo(fory_core::TypeId::UNKNOWN as u32, concrete_type_id)?
                    } else {
                        context.get_type_info(&concrete_type_id)?
                    };
                    let serializer_fn = typeinfo.get_harness().get_write_data_fn();
                    serializer_fn(any_obj, context, has_generics)?;
                }
                Ok(())
            }

            fn fory_write_data(&self, context: &mut fory_core::WriteContext) -> Result<(), fory_core::Error> {
                let any_obj = <dyn $trait_name as fory_core::Serializer>::as_any(&*self.0);
                $crate::downcast_and_serialize!(any_obj, context, $trait_name, $($impl_type),+)
            }

            fn fory_read(context: &mut fory_core::ReadContext, read_ref_info: bool, read_type_info: bool) -> Result<Self, fory_core::Error> {
                $crate::read_ptr_trait_object!(
                    context,
                    read_ref_info,
                    read_type_info,
                    None,
                    $pointer_type,
                    $trait_name,
                    $constructor_expr,
                    $get_ref,
                    $store_ref,
                    $($impl_type),+
                )
            }

            fn fory_read_with_type_info(context: &mut fory_core::ReadContext, read_ref_info: bool, type_info: std::sync::Arc<fory_core::TypeInfo>) -> Result<Self, fory_core::Error> {
                $crate::read_ptr_trait_object!(
                    context,
                    read_ref_info,
                    false,
                    Some(type_info),
                    $pointer_type,
                    $trait_name,
                    $constructor_expr,
                    $get_ref,
                    $store_ref,
                    $($impl_type),+
                )
            }

            fn fory_read_data(context: &mut fory_core::ReadContext) -> Result<Self, fory_core::Error> {
                $crate::not_allowed!("fory_read_data should not be called directly on polymorphic {}<dyn {}> trait object", stringify!($ptr_path), stringify!($trait_name))
            }

            fn fory_get_type_id(_type_resolver: &fory_core::TypeResolver) -> Result<u32, fory_core::Error> {
                Ok(fory_core::TypeId::STRUCT as u32)
            }

            fn fory_static_type_id() -> fory_core::TypeId {
                fory_core::TypeId::UNKNOWN
            }

            fn fory_write_type_info(_context: &mut fory_core::WriteContext) -> Result<(), fory_core::Error> {
                Ok(())
            }

            fn fory_read_type_info(_context: &mut fory_core::ReadContext) -> Result<(), fory_core::Error>  {
                Ok(())
            }

            fn fory_is_polymorphic() -> bool {
                true
            }

            fn fory_is_shared_ref() -> bool {
                true
            }

            fn fory_type_id_dyn(&self, type_resolver: &fory_core::TypeResolver) -> Result<u32, fory_core::Error> {
                let any_obj = <dyn $trait_name as fory_core::Serializer>::as_any(&*self.0);
                let concrete_type_id = any_obj.type_id();
                type_resolver
                    .get_fory_type_id(concrete_type_id)
                    .ok_or_else(|| fory_core::Error::type_error("Type not registered for trait object"))
            }

            fn fory_concrete_type_id(&self) -> std::any::TypeId {
                <dyn $trait_name as fory_core::Serializer>::as_any(&*self.0).type_id()
            }

            fn as_any(&self) -> &dyn std::any::Any {
                <dyn $trait_name as fory_core::Serializer>::as_any(&*self.0)
            }
        }
    };
}

/// Helper macros for automatic conversions in derive code
/// These are used by fory-derive to generate transparent conversions
///
/// Convert field of type `Rc<dyn Trait>` to wrapper for serialization
#[macro_export]
macro_rules! wrap_rc {
    ($field:expr, $trait_name:ident) => {
        $crate::paste::paste! {
            [<$trait_name Rc>]::from($field)
        }
    };
}

/// Convert wrapper back to `Rc<dyn Trait>` for deserialization
#[macro_export]
macro_rules! unwrap_rc {
    ($wrapper:expr, $trait_name:ident) => {
        std::rc::Rc::<dyn $trait_name>::from($wrapper)
    };
}

/// Convert `Arc<dyn Trait>` to wrapper for serialization
#[macro_export]
macro_rules! wrap_arc {
    ($field:expr, $trait_name:ident) => {
        $crate::paste::paste! {
            [<$trait_name Arc>]::from($field)
        }
    };
}

/// Convert `Vec<Rc<dyn Trait>>` to `Vec<wrapper>` for serialization
#[macro_export]
macro_rules! wrap_vec_rc {
    ($vec:expr, $trait_name:ident) => {
        $crate::paste::paste! {
            $vec.into_iter().map(|item| [<$trait_name Rc>]::from(item)).collect()
        }
    };
}

impl Default for Box<dyn Serializer> {
    fn default() -> Self {
        Box::new(0)
    }
}

impl ForyDefault for Box<dyn Serializer> {
    fn fory_default() -> Self {
        Box::new(0)
    }
}

impl Serializer for Box<dyn Serializer> {
    fn fory_concrete_type_id(&self) -> std::any::TypeId {
        (**self).fory_concrete_type_id()
    }

    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if write_ref_info {
            context.writer.write_i8(RefFlag::NotNullValue as i8);
        }
        let fory_type_id_dyn = self.fory_type_id_dyn(context.get_type_resolver())?;
        let concrete_type_id = (**self).fory_concrete_type_id();
        if write_type_info {
            context.write_any_typeinfo(fory_type_id_dyn, concrete_type_id)?;
        };
        self.fory_write_data_generic(context, has_generics)
    }

    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        self.fory_write_data_generic(context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        (**self).fory_write_data_generic(context, has_generics)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        (**self).fory_type_id_dyn(type_resolver)
    }

    fn as_any(&self) -> &dyn std::any::Any {
        (**self).as_any()
    }

    fn fory_is_polymorphic() -> bool {
        true
    }

    fn fory_write_type_info(_context: &mut WriteContext) -> Result<(), Error> {
        panic!("Box<dyn Serializer> is polymorphic - can's write type info statically");
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        context.read_any_typeinfo()?;
        Ok(())
    }

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        read_box_seralizer(context, read_ref_info, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        type_info: Arc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_box_seralizer(context, read_ref_info, false, Some(type_info))
    }

    fn fory_read_data(_context: &mut ReadContext) -> Result<Self, Error> {
        panic!("fory_read_data should not be called directly on Box<dyn Serializer>");
    }
}

fn read_box_seralizer(
    context: &mut ReadContext,
    read_ref_info: bool,
    read_type_info: bool,
    type_info: Option<Arc<TypeInfo>>,
) -> Result<Box<dyn Serializer>, Error> {
    context.inc_depth()?;
    let ref_flag = if read_ref_info {
        context.reader.read_i8()?
    } else {
        RefFlag::NotNullValue as i8
    };
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::invalid_data(
            "Expected NotNullValue for Box<dyn Serializer>",
        ));
    }
    let typeinfo = if let Some(type_info) = type_info {
        type_info
    } else {
        ensure!(
            read_type_info,
            Error::invalid_data("Type info must be read for Box<dyn Serializer>")
        );
        context.read_any_typeinfo()?
    };
    let harness = typeinfo.get_harness();
    let boxed_any = harness.get_read_data_fn()(context)?;
    let trait_object = harness.get_to_serializer()(boxed_any)?;
    context.dec_depth();
    Ok(trait_object)
}
