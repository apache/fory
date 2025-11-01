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
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::TypeResolver;
use crate::serializer::collection::{read_collection_type_info, write_collection_type_info};
use crate::serializer::skip::skip_any_value;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::TypeId;
use std::mem;

/// Helper function to write a tuple element based on its type characteristics.
/// This handles the different serialization strategies for various element types.
#[inline(always)]
fn write_tuple_element<T: Serializer>(elem: &T, context: &mut WriteContext) -> Result<(), Error> {
    if T::fory_is_option() || T::fory_is_shared_ref() || T::fory_static_type_id() == TypeId::UNKNOWN
    {
        // For Option, shared references, or unknown static types, use full write with ref tracking
        elem.fory_write(context, true, false, false)
    } else {
        // For concrete types with known static type IDs, directly write data
        elem.fory_write_data(context)
    }
}

/// Helper function to read a tuple element based on its type characteristics.
#[inline(always)]
fn read_tuple_element<T: Serializer + ForyDefault>(
    context: &mut ReadContext,
    _has_generics: bool,
) -> Result<T, Error> {
    if T::fory_is_option() || T::fory_is_shared_ref() || T::fory_static_type_id() == TypeId::UNKNOWN
    {
        // For Option, shared references, or unknown static types, use full read with ref tracking
        T::fory_read(context, true, false)
    } else {
        // For concrete types with known static type IDs, directly read data
        T::fory_read_data(context)
    }
}

impl<T0: Serializer + ForyDefault> Serializer for (T0,) {
    #[inline(always)]
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        if !context.is_compatible() && !context.is_xlang() {
            // Non-compatible mode: write elements directly
            write_tuple_element(&self.0, context)?;
        } else {
            // Compatible mode: use collection protocol (heterogeneous)
            context.writer.write_varuint32(1);
            let header = 0u8; // No IS_SAME_TYPE flag
            context.writer.write_u8(header);
            self.0.fory_write(context, true, true, false)?;
        }
        Ok(())
    }

    #[inline(always)]
    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        write_collection_type_info(context, TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        if !context.is_compatible() && !context.is_xlang() {
            // Non-compatible mode: read elements directly
            let elem0 = read_tuple_element::<T0>(context, false)?;
            Ok((elem0,))
        } else {
            // Compatible mode: read collection protocol (heterogeneous)
            let len = context.reader.read_varuint32()?;
            let _header = context.reader.read_u8()?;

            let elem0 = if len > 0 {
                T0::fory_read(context, true, true)?
            } else {
                T0::fory_default()
            };

            // Skip any extra elements beyond the first
            for _ in 1..len {
                skip_any_value(context, true)?;
            }

            Ok((elem0,))
        }
    }

    #[inline(always)]
    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_collection_type_info(context, TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_reserved_space() -> usize {
        mem::size_of::<u32>()
    }

    #[inline(always)]
    fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
        Ok(TypeId::LIST as u32)
    }

    #[inline(always)]
    fn fory_static_type_id() -> TypeId {
        TypeId::LIST
    }

    #[inline(always)]
    fn fory_is_wrapper_type() -> bool
    where
        Self: Sized,
    {
        true
    }

    #[inline(always)]
    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

impl<T0: ForyDefault> ForyDefault for (T0,) {
    #[inline(always)]
    fn fory_default() -> Self {
        (T0::fory_default(),)
    }
}

macro_rules! fory_tuple_field {
    ($tuple:expr, T0) => {
        $tuple.0
    };
    ($tuple:expr, T1) => {
        $tuple.1
    };
    ($tuple:expr, T2) => {
        $tuple.2
    };
    ($tuple:expr, T3) => {
        $tuple.3
    };
    ($tuple:expr, T4) => {
        $tuple.4
    };
    ($tuple:expr, T5) => {
        $tuple.5
    };
    ($tuple:expr, T6) => {
        $tuple.6
    };
    ($tuple:expr, T7) => {
        $tuple.7
    };
    ($tuple:expr, T8) => {
        $tuple.8
    };
    ($tuple:expr, T9) => {
        $tuple.9
    };
    ($tuple:expr, T10) => {
        $tuple.10
    };
    ($tuple:expr, T11) => {
        $tuple.11
    };
    ($tuple:expr, T12) => {
        $tuple.12
    };
    ($tuple:expr, T13) => {
        $tuple.13
    };
    ($tuple:expr, T14) => {
        $tuple.14
    };
    ($tuple:expr, T15) => {
        $tuple.15
    };
    ($tuple:expr, T16) => {
        $tuple.16
    };
    ($tuple:expr, T17) => {
        $tuple.17
    };
    ($tuple:expr, T18) => {
        $tuple.18
    };
    ($tuple:expr, T19) => {
        $tuple.19
    };
    ($tuple:expr, T20) => {
        $tuple.20
    };
    ($tuple:expr, T21) => {
        $tuple.21
    };
    ($tuple:expr, T22) => {
        $tuple.22
    };
    ($tuple:expr, T23) => {
        $tuple.23
    };
    ($tuple:expr, T24) => {
        $tuple.24
    };
    ($tuple:expr, T25) => {
        $tuple.25
    };
    ($tuple:expr, T26) => {
        $tuple.26
    };
    ($tuple:expr, T27) => {
        $tuple.27
    };
    ($tuple:expr, T28) => {
        $tuple.28
    };
    ($tuple:expr, T29) => {
        $tuple.29
    };
    ($tuple:expr, T30) => {
        $tuple.30
    };
    ($tuple:expr, T31) => {
        $tuple.31
    };
    ($tuple:expr, T32) => {
        $tuple.32
    };
    ($tuple:expr, T33) => {
        $tuple.33
    };
    ($tuple:expr, T34) => {
        $tuple.34
    };
    ($tuple:expr, T35) => {
        $tuple.35
    };
    ($tuple:expr, T36) => {
        $tuple.36
    };
    ($tuple:expr, T37) => {
        $tuple.37
    };
    ($tuple:expr, T38) => {
        $tuple.38
    };
    ($tuple:expr, T39) => {
        $tuple.39
    };
    ($tuple:expr, T40) => {
        $tuple.40
    };
}

macro_rules! fory_tuple_count {
    ($($name:ident),+ $(,)?) => {
        0usize $(+ fory_tuple_count!(@one $name))*
    };
    (@one $name:ident) => { 1usize };
}

/// Macro to implement Serializer for tuples of various sizes.
/// Fory supports tuples up to 22 elements, longer tuples are not allowed.
///
/// This handles two serialization modes:
/// 1. Non-compatible mode: Write elements one by one without collection headers and type metadata
/// 2. Compatible mode: Use full collection protocol with headers and type info (always heterogeneous)
#[macro_export]
macro_rules! impl_tuple_serializer {
    // Multiple element tuples (2+)
    ($T0:ident $(, $T:ident)+ $(,)?) => {
        impl<$T0: Serializer + ForyDefault, $($T: Serializer + ForyDefault),*> Serializer for ($T0, $($T),*) {
            #[inline(always)]
            fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
                if !context.is_compatible() && !context.is_xlang() {
                    // Non-compatible mode: write elements directly one by one
                    write_tuple_element(&self.0, context)?;
                    $(
                        write_tuple_element(&fory_tuple_field!(self, $T), context)?;
                    )*
                } else {
                    // Compatible mode: use collection protocol (always heterogeneous)
                    let len = fory_tuple_count!($T0, $($T),*);
                    context.writer.write_varuint32(len as u32);

                    // Write header without IS_SAME_TYPE flag
                    let header = 0u8;
                    context.writer.write_u8(header);

                    // Write each element with its type info
                    self.0.fory_write(context, true, true, false)?;
                    $(
                        fory_tuple_field!(self, $T).fory_write(context, true, true, false)?;
                    )*
                }
                Ok(())
            }

            #[inline(always)]
            fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                write_collection_type_info(context, TypeId::LIST as u32)
            }

            #[inline(always)]
            fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
                if !context.is_compatible() && !context.is_xlang() {
                    // Non-compatible mode: read elements directly
                    let elem0 = read_tuple_element::<$T0>(context, false)?;
                    $(
                        #[allow(non_snake_case)]
                        let $T = read_tuple_element::<$T>(context, false)?;
                    )*
                    Ok((elem0, $($T),*))
                } else {
                    // Compatible mode: read collection protocol (always heterogeneous)
                    // Handle flexible length: use defaults for missing elements, skip extras
                    let len = context.reader.read_varuint32()?;
                    let _header = context.reader.read_u8()?;

                    // Track how many elements we've read
                    let mut index = 0u32;

                    // Read first element or use default
                    let elem0 = if index < len {
                        index += 1;
                        $T0::fory_read(context, true, true)?
                    } else {
                        $T0::fory_default()
                    };

                    // Read remaining elements or use defaults
                    $(
                        #[allow(non_snake_case)]
                        let $T = if index < len {
                            index += 1;
                            $T::fory_read(context, true, true)?
                        } else {
                            $T::fory_default()
                        };
                    )*

                    // Skip any extra elements beyond what we expect
                    for _ in index..len {
                        skip_any_value(context, true)?;
                    }

                    Ok((elem0, $($T),*))
                }
            }

            #[inline(always)]
            fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                read_collection_type_info(context, TypeId::LIST as u32)
            }

            #[inline(always)]
            fn fory_reserved_space() -> usize {
                mem::size_of::<u32>() // Size for length
            }

            #[inline(always)]
            fn fory_get_type_id(_: &TypeResolver) -> Result<u32, Error> {
                Ok(TypeId::LIST as u32)
            }

            #[inline(always)]
            fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<u32, Error> {
                Ok(TypeId::LIST as u32)
            }

            #[inline(always)]
            fn fory_static_type_id() -> TypeId {
                TypeId::LIST
            }

            #[inline(always)]
            fn fory_is_wrapper_type() -> bool
                where
                    Self: Sized, {
                true
            }

            #[inline(always)]
            fn as_any(&self) -> &dyn std::any::Any {
                self
            }
        }

        impl<$T0: ForyDefault, $($T: ForyDefault),*> ForyDefault for ($T0, $($T),*) {
            #[inline(always)]
            fn fory_default() -> Self {
                ($T0::fory_default(), $($T::fory_default()),*)
            }
        }
    };
}

// Implement Serializer for tuples of size 2-22
impl_tuple_serializer!(T0, T1);
impl_tuple_serializer!(T0, T1, T2);
impl_tuple_serializer!(T0, T1, T2, T3);
impl_tuple_serializer!(T0, T1, T2, T3, T4);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15);
impl_tuple_serializer!(T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16);
impl_tuple_serializer!(
    T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17
);
impl_tuple_serializer!(
    T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18
);
impl_tuple_serializer!(
    T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19
);
impl_tuple_serializer!(
    T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20
);
impl_tuple_serializer!(
    T0, T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20,
    T21
);
