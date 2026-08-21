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

use super::codec::{compatible_field_pair, field_ref_mode, generic_field_type, Codec};
use super::collection::{
    read_collection_type_info, write_collection_type_info, DECL_ELEMENT_TYPE, HAS_NULL,
    IS_SAME_TYPE, TRACKING_REF,
};
use super::skip::{skip_any_value, skip_known_value};
use crate::context::{ReadContext, WriteContext};
use crate::error::Error;
use crate::meta::FieldType;
use crate::resolver::{RefFlag, RefMode, TypeInfo, TypeResolver};
use crate::serializer::{
    core::{check_group_type_info, read_group_type_info},
    Serializer,
};
use crate::type_id::{TypeId, SIZE_OF_REF_AND_TYPE};
use std::marker::PhantomData;
use std::rc::Rc;

impl Serializer for () {
    type Target = Self;

    #[inline(always)]
    fn write_data(_: &Self, _: &mut WriteContext) -> Result<(), Error> {
        Ok(())
    }

    #[inline(always)]
    fn read_data(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(())
    }

    #[inline(always)]
    fn default_value(_: &mut ReadContext) -> Result<Self, Error> {
        Ok(())
    }

    #[inline(always)]
    fn static_type_id() -> TypeId {
        TypeId::NONE
    }

    #[inline(always)]
    fn reserved_space() -> usize {
        0
    }

    #[inline(always)]
    fn read_arc_any(
        _: &mut ReadContext,
    ) -> Result<std::sync::Arc<dyn std::any::Any + Send + Sync>, Error> {
        Ok(std::sync::Arc::new(()))
    }
}

#[inline(always)]
fn write_tuple_element<T: 'static, S: Serializer<Target = T>>(
    value: &T,
    context: &mut WriteContext,
) -> Result<(), Error> {
    if S::IS_OPTIONAL || S::IS_SHARED_REF || S::static_type_id() == TypeId::UNKNOWN {
        S::write(
            value,
            context,
            if S::IS_SHARED_REF {
                RefMode::Tracking
            } else {
                RefMode::NullOnly
            },
            false,
        )
    } else {
        S::write_data(value, context)
    }
}

#[inline(always)]
fn read_tuple_element<T: 'static, S: Serializer<Target = T>>(
    context: &mut ReadContext,
) -> Result<T, Error> {
    if S::IS_OPTIONAL || S::IS_SHARED_REF || S::static_type_id() == TypeId::UNKNOWN {
        S::read(
            context,
            if S::IS_SHARED_REF {
                RefMode::Tracking
            } else {
                RefMode::NullOnly
            },
            false,
        )
    } else {
        S::read_data(context)
    }
}

#[inline(always)]
fn tuple_ref_mode(header: u8) -> RefMode {
    if (header & TRACKING_REF) != 0 {
        RefMode::Tracking
    } else if (header & HAS_NULL) != 0 {
        RefMode::NullOnly
    } else {
        RefMode::None
    }
}

#[inline(always)]
fn read_tuple_value<T: 'static, C: Codec<T>>(
    context: &mut ReadContext,
    ref_mode: RefMode,
    same_type: bool,
    declared_type: Option<&FieldType>,
    type_info: Option<&Rc<TypeInfo>>,
    type_info_field: Option<&FieldType>,
) -> Result<T, Error> {
    if !same_type {
        return C::read(context, ref_mode, true);
    }
    if let Some(field_type) = declared_type {
        let local_field_type = C::field_type(context.get_type_resolver())?;
        return C::read_compatible(context, &local_field_type, field_type)?
            .ok_or_else(tuple_type_mismatch);
    }
    if let (Some(type_info), Some(type_info_field)) = (type_info, type_info_field) {
        let local_field_type = C::field_type(context.get_type_resolver())?;
        if !compatible_field_pair(&local_field_type, type_info_field) {
            return Err(tuple_type_mismatch());
        }
        return C::read_with_type_info(context, ref_mode, type_info);
    }
    Err(missing_tuple_metadata())
}

#[cold]
#[inline(never)]
fn tuple_type_mismatch() -> Error {
    Error::type_error("same-type tuple element is incompatible with local position")
}

#[cold]
#[inline(never)]
fn missing_tuple_metadata() -> Error {
    Error::invalid_data("same-type tuple metadata is missing")
}

#[cold]
#[inline(never)]
fn tuple_ref_mismatch() -> Error {
    Error::invalid_data("tuple header conflicts with declared element metadata")
}

#[cold]
#[inline(never)]
fn skip_tuple_values(
    context: &mut ReadContext,
    count: u32,
    ref_mode: RefMode,
    same_type: bool,
    declared_type: Option<&FieldType>,
    type_info: Option<&Rc<TypeInfo>>,
) -> Result<(), Error> {
    if !same_type {
        for _ in 0..count {
            skip_any_value(context, ref_mode != RefMode::None)?;
        }
        return Ok(());
    }
    if let Some(field_type) = declared_type {
        for _ in 0..count {
            skip_known_value(context, Some(field_type), ref_mode, None)?;
        }
        return Ok(());
    }
    let type_info = type_info.ok_or_else(missing_tuple_metadata)?;
    for _ in 0..count {
        skip_known_value(context, None, ref_mode, Some(type_info))?;
    }
    Ok(())
}

#[cold]
#[inline(never)]
fn skip_declared_tuple_values<T, S>(
    context: &mut ReadContext,
    count: u32,
    ref_mode: RefMode,
) -> Result<(), Error>
where
    T: 'static,
    S: Serializer<Target = T>,
{
    for _ in 0..count {
        let _ = S::read(context, ref_mode, false)?;
    }
    Ok(())
}

macro_rules! tuple_declared_type {
    (value, $context:expr, $remote:expr, $same_type:expr, $declared:expr, $ref_mode:expr) => {
        ()
    };
    (field, $context:expr, $remote:expr, $same_type:expr, $declared:expr, $ref_mode:expr) => {{
        if $same_type && $declared {
            let field_type = generic_field_type($remote, 0, "tuple")?;
            if field_ref_mode(field_type) != $ref_mode {
                return Err(tuple_ref_mismatch());
            }
            Some(field_type)
        } else {
            None
        }
    }};
}

macro_rules! tuple_type_info_field {
    (value, $type_info:expr, $ref_mode:expr) => {
        ()
    };
    (field, $type_info:expr, $ref_mode:expr) => {
        $type_info.as_ref().map(|type_info| {
            FieldType::new_with_user_type_id(
                type_info.get_type_id() as u32,
                type_info.get_user_type_id(),
                $ref_mode.is_nullable(),
                $ref_mode.tracks_refs(),
                Vec::new(),
            )
        })
    };
}

macro_rules! tuple_same_type_info {
    (value, $context:expr; ($T:ident, $C:ident, $S:ident, $index:tt) $(, $rest:tt)*) => {
        read_group_type_info::<$C, false>($context)
    };
    (field, $context:expr; ($T:ident, $C:ident, $S:ident, $index:tt) $(, $rest:tt)*) => {
        read_group_type_info::<$C, true>($context)
    };
}

macro_rules! tuple_read_node {
    (
        value,
        $T:ty,
        $C:ty,
        $index:expr,
        $context:expr,
        $ref_mode:expr,
        $same_type:expr,
        $declared:expr,
        $declared_type:expr,
        $type_info:expr,
        $type_info_field:expr
    ) => {
        if !$same_type {
            <$C as Serializer>::read($context, $ref_mode, true)
        } else if $declared {
            <$C as Serializer>::read($context, $ref_mode, false)
        } else {
            if $index != 0 {
                check_group_type_info::<$C, false>(
                    $type_info.as_ref().ok_or_else(missing_tuple_metadata)?,
                )?;
            }
            <$C as Serializer>::read_with_type_info(
                $context,
                $ref_mode,
                $type_info.as_ref().ok_or_else(missing_tuple_metadata)?,
            )
        }
    };
    (
        field,
        $T:ty,
        $C:ty,
        $_index:expr,
        $context:expr,
        $ref_mode:expr,
        $same_type:expr,
        $declared:expr,
        $declared_type:expr,
        $type_info:expr,
        $type_info_field:expr
    ) => {
        read_tuple_value::<$T, $C>(
            $context,
            $ref_mode,
            $same_type,
            $declared_type,
            $type_info.as_ref(),
            $type_info_field.as_ref(),
        )
    };
}

macro_rules! tuple_skip_nodes {
    (
        value,
        $context:expr,
        $count:expr,
        $ref_mode:expr,
        $same_type:expr,
        $declared:expr,
        $declared_type:expr,
        $type_info:expr;
        ($T:ident, $C:ident, $S:ident, $index:tt)
        $(, ($rest_t:ident, $rest_c:ident, $rest_s:ident, $rest_index:tt))*
    ) => {
        if $same_type && $declared {
            skip_declared_tuple_values::<$T, $C>($context, $count, $ref_mode)
        } else {
            skip_tuple_values(
                $context,
                $count,
                $ref_mode,
                $same_type,
                None,
                $type_info.as_ref(),
            )
        }
    };
    (
        field,
        $context:expr,
        $count:expr,
        $ref_mode:expr,
        $same_type:expr,
        $declared:expr,
        $declared_type:expr,
        $type_info:expr;
        $(($T:ident, $C:ident, $S:ident, $index:tt)),+
    ) => {
        skip_tuple_values(
            $context,
            $count,
            $ref_mode,
            $same_type,
            $declared_type,
            $type_info.as_ref(),
        )
    };
}

macro_rules! read_tuple_body {
    (
        $layer:ident,
        $context:expr,
        $remote:expr;
        $(($T:ident, $C:ident, $S:ident, $index:tt)),+
    ) => {{
        let context = &mut *$context;
        if !context.is_compatible() && !context.is_xlang() {
            return Ok(($(read_tuple_element::<$T, $C>(context)?,)+));
        }
        let len = context.reader.read_var_u32()?;
        context.reader.check_bound(len as usize)?;
        if len == 0 {
            return Ok(($($C::default_value(context)?,)+));
        }
        let header = context.reader.read_u8()?;
        let same_type = (header & IS_SAME_TYPE) != 0;
        let ref_mode = tuple_ref_mode(header);
        let declared = (header & DECL_ELEMENT_TYPE) != 0;
        let declared_type = tuple_declared_type!(
            $layer,
            context,
            $remote,
            same_type,
            declared,
            ref_mode
        );
        let type_info = if same_type && !declared {
            Some(tuple_same_type_info!(
                $layer, context;
                $(($T, $C, $S, $index)),+
            )?)
        } else {
            None
        };
        let type_info_field =
            tuple_type_info_field!($layer, type_info, ref_mode);
        let _ = &declared_type;
        let _ = &type_info_field;
        let mut index = 0u32;
        let value = ($({
            let value = if index < len {
                index += 1;
                tuple_read_node!(
                    $layer,
                    $T,
                    $C,
                    $index,
                    context,
                    ref_mode,
                    same_type,
                    declared,
                    declared_type,
                    type_info,
                    type_info_field
                )?
            } else {
                $C::default_value(context)?
            };
            value
        },)+);
        tuple_skip_nodes!(
            $layer,
            context,
            len - index,
            ref_mode,
            same_type,
            declared,
            declared_type,
            type_info;
            $(($T, $C, $S, $index)),+
        )?;
        Ok(value)
    }};
}

macro_rules! impl_tuple_codec {
    (
        $codec:ident,
        $provider:ident,
        $(($T:ident, $C:ident, $S:ident, $index:tt)),+ $(,)?
    ) => {
        pub struct $codec<
            $($T, $C,)+
            const NULLABLE: bool,
            const TRACK_REF: bool,
        >(PhantomData<fn() -> ($($T, $C,)+)>);

        impl<
                $($T, $C,)+
                const NULLABLE: bool,
                const TRACK_REF: bool,
            > Serializer for $codec<$($T, $C,)+ NULLABLE, TRACK_REF>
        where
            $($T: 'static, $C: Serializer<Target = $T>,)+
        {
            type Target = ($($T,)+);

            const READ_DATA_ALWAYS_ADVANCES: bool = false $(
                || $C::IS_OPTIONAL
                || $C::IS_SHARED_REF
                || $C::READ_DATA_ALWAYS_ADVANCES
            )+;

            #[inline(always)]
            fn write_data(
                value: &Self::Target,
                context: &mut WriteContext,
            ) -> Result<(), Error> {
                if !context.is_compatible() && !context.is_xlang() {
                    $(write_tuple_element::<$T, $C>(&value.$index, context)?;)+
                    return Ok(());
                }
                context.writer.write_var_u32(impl_tuple_codec!(@count $($T),+) as u32);
                let mut header = 0u8;
                $(
                    if $C::IS_OPTIONAL {
                        header |= HAS_NULL;
                    }
                    if $C::IS_SHARED_REF {
                        header |= TRACKING_REF;
                    }
                )+
                context.writer.write_u8(header);
                let ref_mode = tuple_ref_mode(header);
                $(
                    $C::write(&value.$index, context, ref_mode, true)?;
                )+
                Ok(())
            }

            // Debug builds must not inline recursively nested tuple readers
            // into one generated compatible-struct frame; complex schemas can
            // otherwise exhaust the test thread's stack.
            #[cfg_attr(debug_assertions, inline(never))]
            #[cfg_attr(not(debug_assertions), inline(always))]
            fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
                read_tuple_body!(
                    value,
                    context,
                    ();
                    $(($T, $C, $S, $index)),+
                )
            }

            #[inline(always)]
            fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
                Ok(($($C::default_value(context)?,)+))
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                write_collection_type_info(context, TypeId::LIST as u32)
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                read_collection_type_info(context, TypeId::LIST as u32)
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::LIST
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
            }

        }

        impl<
                $($T, $C,)+
                const NULLABLE: bool,
                const TRACK_REF: bool,
            > $codec<$($T, $C,)+ NULLABLE, TRACK_REF>
        where
            $($T: 'static, $C: Codec<$T>,)+
        {
            // This is the field-only counterpart to `Serializer::read_data`.
            // It consumes the remote tuple field schema without leaking
            // `FieldType` into value-level serializer composition.
            #[cfg_attr(debug_assertions, inline(never))]
            #[cfg_attr(not(debug_assertions), inline(always))]
            fn read_tuple_with_type(
                context: &mut ReadContext,
                remote_data_type: &FieldType,
            ) -> Result<($($T,)+), Error> {
                read_tuple_body!(
                    field,
                    context,
                    remote_data_type;
                    $(($T, $C, $S, $index)),+
                )
            }
        }

        impl<
                $($T, $C,)+
                const NULLABLE: bool,
                const TRACK_REF: bool,
            > Codec<($($T,)+)>
            for $codec<$($T, $C,)+ NULLABLE, TRACK_REF>
        where
            $($T: 'static, $C: Codec<$T>,)+
        {
            #[inline(always)]
            fn field_type(type_resolver: &TypeResolver) -> Result<FieldType, Error> {
                let _ = type_resolver;
                // Tuple positions carry their own type metadata in compatible and
                // xlang bodies. LIST metadata has one homogeneous generic slot, so
                // declaring position codecs here would truncate the schema on wire.
                Ok(FieldType::new_with_ref(
                    TypeId::LIST as u32,
                    NULLABLE,
                    TRACK_REF,
                    vec![FieldType::new(TypeId::UNKNOWN as u32, true, Vec::new())],
                ))
            }

            #[inline(always)]
            fn write_field(
                value: &($($T,)+),
                context: &mut WriteContext,
            ) -> Result<(), Error> {
                if NULLABLE || TRACK_REF {
                    context.writer.write_i8(RefFlag::NotNullValue as i8);
                }
                <Self as Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_field(context: &mut ReadContext) -> Result<($($T,)+), Error> {
                if (NULLABLE || TRACK_REF)
                    && context.reader.read_i8()? == RefFlag::Null as i8
                {
                    return <Self as Serializer>::default_value(context);
                }
                <Self as Serializer>::read_data(context)
            }

            #[inline(always)]
            fn read_data_with_type(
                context: &mut ReadContext,
                remote_data_type: &FieldType,
            ) -> Result<($($T,)+), Error> {
                Self::read_tuple_with_type(context, remote_data_type)
            }

            #[inline(always)]
            fn read_field_with_type(
                context: &mut ReadContext,
                remote_field_type: &FieldType,
            ) -> Result<($($T,)+), Error> {
                if field_ref_mode(remote_field_type) != RefMode::None
                    && context.reader.read_i8()? == RefFlag::Null as i8
                {
                    return <Self as Serializer>::default_value(context);
                }
                Self::read_data_with_type(context, remote_field_type)
            }

            #[inline(always)]
            fn write_with_mode(
                value: &($($T,)+),
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
                _has_generics: bool,
            ) -> Result<(), Error> {
                <Self as Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }
        }

        #[doc = concat!(
            "Statically serializes the recursively formed tuple of each child serializer's ",
            "`Target` at roots or recursive carrier nodes. This zero-sized carrier is not ",
            "registered independently."
        )]
        pub struct $provider<$($S,)+>(PhantomData<fn() -> ($($S,)+)>);

        impl<$($S: Serializer,)+> Serializer for $provider<$($S,)+> {
            type Target = ($($S::Target,)+);

            const READ_DATA_ALWAYS_ADVANCES: bool = false $(
                || $S::IS_OPTIONAL
                || $S::IS_SHARED_REF
                || $S::READ_DATA_ALWAYS_ADVANCES
            )+;

            #[inline(always)]
            fn write_data(value: &Self::Target, context: &mut WriteContext) -> Result<(), Error> {
                <$codec<
                    $($S::Target, $S,)+
                    false,
                    false,
                > as Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self::Target, Error> {
                <$codec<
                    $($S::Target, $S,)+
                    false,
                    false,
                > as Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(context: &mut ReadContext) -> Result<Self::Target, Error> {
                <$codec<
                    $($S::Target, $S,)+
                    false,
                    false,
                > as Serializer>::default_value(context)
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                write_collection_type_info(context, TypeId::LIST as u32)
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                read_collection_type_info(context, TypeId::LIST as u32)
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::LIST
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
            }

        }

        impl<$($T,)+> Serializer for ($($T,)+)
        where
            $($T: Serializer<Target = $T>,)+
        {
            type Target = Self;

            const READ_DATA_ALWAYS_ADVANCES: bool = false $(
                || $T::IS_OPTIONAL
                || $T::IS_SHARED_REF
                || $T::READ_DATA_ALWAYS_ADVANCES
            )+;

            #[inline(always)]
            fn write_data(value: &Self, context: &mut WriteContext) -> Result<(), Error> {
                <$provider<$($T,)+> as Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_data(context: &mut ReadContext) -> Result<Self, Error> {
                <$provider<$($T,)+> as Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(context: &mut ReadContext) -> Result<Self, Error> {
                <$provider<$($T,)+> as Serializer>::default_value(context)
            }

            #[inline(always)]
            fn write(
                value: &Self,
                context: &mut WriteContext,
                ref_mode: RefMode,
                write_type_info: bool,
            ) -> Result<(), Error> {
                <$provider<$($T,)+> as Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn read(
                context: &mut ReadContext,
                ref_mode: RefMode,
                read_type_info: bool,
            ) -> Result<Self, Error> {
                <$provider<$($T,)+> as Serializer>::read(
                    context,
                    ref_mode,
                    read_type_info,
                )
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut ReadContext,
                ref_mode: RefMode,
                type_info: &Rc<TypeInfo>,
            ) -> Result<Self, Error> {
                <$provider<$($T,)+> as Serializer>::read_with_type_info(
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn write_type_info(context: &mut WriteContext) -> Result<(), Error> {
                <$provider<$($T,)+> as Serializer>::write_type_info(context)
            }

            #[inline(always)]
            fn read_type_info(context: &mut ReadContext) -> Result<(), Error> {
                <$provider<$($T,)+> as Serializer>::read_type_info(context)
            }

            #[inline(always)]
            fn static_type_id() -> TypeId {
                TypeId::LIST
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + SIZE_OF_REF_AND_TYPE
            }

        }
    };

    (@count $head:ident $(, $tail:ident)*) => {
        1usize $(+ impl_tuple_codec!(@one $tail))*
    };
    (@one $value:ident) => { 1usize };
}

impl_tuple_codec!(Tuple1Codec, Tuple1Serializer, (T0, C0, S0, 0));
impl_tuple_codec!(
    Tuple2Codec,
    Tuple2Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1)
);
impl_tuple_codec!(
    Tuple3Codec,
    Tuple3Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2)
);
impl_tuple_codec!(
    Tuple4Codec,
    Tuple4Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3)
);
impl_tuple_codec!(
    Tuple5Codec,
    Tuple5Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4)
);
impl_tuple_codec!(
    Tuple6Codec,
    Tuple6Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5)
);
impl_tuple_codec!(
    Tuple7Codec,
    Tuple7Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6)
);
impl_tuple_codec!(
    Tuple8Codec,
    Tuple8Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7)
);
impl_tuple_codec!(
    Tuple9Codec,
    Tuple9Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8)
);
impl_tuple_codec!(
    Tuple10Codec,
    Tuple10Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9)
);
impl_tuple_codec!(
    Tuple11Codec,
    Tuple11Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10)
);
impl_tuple_codec!(
    Tuple12Codec,
    Tuple12Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11)
);
impl_tuple_codec!(
    Tuple13Codec,
    Tuple13Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12)
);
impl_tuple_codec!(
    Tuple14Codec,
    Tuple14Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13)
);
impl_tuple_codec!(
    Tuple15Codec,
    Tuple15Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14)
);
impl_tuple_codec!(
    Tuple16Codec,
    Tuple16Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15)
);
impl_tuple_codec!(
    Tuple17Codec,
    Tuple17Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15),
    (T16, C16, S16, 16)
);
impl_tuple_codec!(
    Tuple18Codec,
    Tuple18Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15),
    (T16, C16, S16, 16),
    (T17, C17, S17, 17)
);
impl_tuple_codec!(
    Tuple19Codec,
    Tuple19Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15),
    (T16, C16, S16, 16),
    (T17, C17, S17, 17),
    (T18, C18, S18, 18)
);
impl_tuple_codec!(
    Tuple20Codec,
    Tuple20Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15),
    (T16, C16, S16, 16),
    (T17, C17, S17, 17),
    (T18, C18, S18, 18),
    (T19, C19, S19, 19)
);
impl_tuple_codec!(
    Tuple21Codec,
    Tuple21Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15),
    (T16, C16, S16, 16),
    (T17, C17, S17, 17),
    (T18, C18, S18, 18),
    (T19, C19, S19, 19),
    (T20, C20, S20, 20)
);
impl_tuple_codec!(
    Tuple22Codec,
    Tuple22Serializer,
    (T0, C0, S0, 0),
    (T1, C1, S1, 1),
    (T2, C2, S2, 2),
    (T3, C3, S3, 3),
    (T4, C4, S4, 4),
    (T5, C5, S5, 5),
    (T6, C6, S6, 6),
    (T7, C7, S7, 7),
    (T8, C8, S8, 8),
    (T9, C9, S9, 9),
    (T10, C10, S10, 10),
    (T11, C11, S11, 11),
    (T12, C12, S12, 12),
    (T13, C13, S13, 13),
    (T14, C14, S14, 14),
    (T15, C15, S15, 15),
    (T16, C16, S16, 16),
    (T17, C17, S17, 17),
    (T18, C18, S18, 18),
    (T19, C19, S19, 19),
    (T20, C20, S20, 20),
    (T21, C21, S21, 21)
);
