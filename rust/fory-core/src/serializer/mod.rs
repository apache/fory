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

macro_rules! impl_single_carrier_serializer {
    (
        $provider:ident,
        $target:ident,
        $codec:ident,
        wrapper = $wrapper:expr
        $(, bounds = [$($bounds:tt)+])?
    ) => {
        #[doc = concat!(
            "Statically composes `",
            stringify!($provider),
            "<S>` over `S::Target` for serializer-selected roots and nested carrier composition. ",
            "Generated fields may select this exact carrier or recursively select child serializers; ",
            "both forms use the same carrier body implementation. This zero-sized carrier is not registered independently."
        )]
        pub struct $provider<S>(std::marker::PhantomData<fn() -> S>);

        impl<S> $crate::serializer::Serializer for $provider<S>
        where
            S: $crate::serializer::Serializer,
            $(S::Target: $($bounds)+,)?
        {
            type Target = $target<S::Target>;

            #[inline(always)]
            fn write_data(
                value: &Self::Target,
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::write_data(
                    value, context,
                )
            }

            #[inline(always)]
            fn read_data(
                context: &mut $crate::ReadContext,
            ) -> Result<Self::Target, $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(
                context: &mut $crate::ReadContext,
            ) -> Result<Self::Target, $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::default_value(
                    context,
                )
            }

            #[inline(always)]
            fn write(
                value: &Self::Target,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                write_type_info: bool,
            ) -> Result<(), $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn write_type_info_value(
                context: &mut $crate::WriteContext,
                target_type_id: std::any::TypeId,
            ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::write_type_info_value(
                    context,
                    target_type_id,
                )
            }

            #[inline(always)]
            fn write_with_type_info(
                value: &Self::Target,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
            ) -> Result<(), $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::write_with_type_info(
                    value,
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn read(
                context: &mut $crate::ReadContext,
                ref_mode: $crate::RefMode,
                read_type_info: bool,
            ) -> Result<Self::Target, $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::read(
                    context,
                    ref_mode,
                    read_type_info,
                )
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut $crate::ReadContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
            ) -> Result<Self::Target, $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::read_with_type_info(
                    context, ref_mode, type_info,
                )
            }

            #[inline(always)]
            fn write_type_info(
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::write_type_info(
                    context,
                )
            }

            #[inline(always)]
            fn read_type_info(
                context: &mut $crate::ReadContext,
            ) -> Result<(), $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::read_type_info(
                    context,
                )
            }

            #[inline(always)]
            fn static_type_id() -> $crate::TypeId {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::static_type_id()
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::reserved_space()
            }

            const IS_OPTIONAL: bool = <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::IS_OPTIONAL;

            #[inline(always)]
            fn is_none(value: &Self::Target) -> bool {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::is_none(value)
            }

            const IS_POLYMORPHIC: bool = <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::IS_POLYMORPHIC;

            const IS_SHARED_REF: bool = <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::IS_SHARED_REF;

            const IS_WRAPPER: bool = $wrapper;

            const REQUIRES_SCOPED_ACCESS: bool = <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::REQUIRES_SCOPED_ACCESS;

            const READ_DATA_ALWAYS_ADVANCES: bool = <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::READ_DATA_ALWAYS_ADVANCES;

            #[inline(always)]
            fn dynamic_type_id(
                value: &Self::Target,
            ) -> Result<Option<std::any::TypeId>, $crate::Error> {
                <$codec<
                    S::Target,
                    S,
                    false,
                    false,
                > as $crate::serializer::Serializer>::dynamic_type_id(
                    value,
                )
            }

        }

        impl<T> $crate::serializer::Serializer for $target<T>
        where
            T: $crate::serializer::Serializer<Target = T>,
            $(T: $($bounds)+,)?
        {
            type Target = Self;

            #[inline(always)]
            fn write_data(
                value: &Self,
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::write_data(value, context)
            }

            #[inline(always)]
            fn read_data(
                context: &mut $crate::ReadContext,
            ) -> Result<Self, $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::read_data(context)
            }

            #[inline(always)]
            fn default_value(
                context: &mut $crate::ReadContext,
            ) -> Result<Self, $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::default_value(context)
            }

            #[inline(always)]
            fn write(
                value: &Self,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                write_type_info: bool,
            ) -> Result<(), $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::write(
                    value,
                    context,
                    ref_mode,
                    write_type_info,
                )
            }

            #[inline(always)]
            fn write_type_info_value(
                context: &mut $crate::WriteContext,
                target_type_id: std::any::TypeId,
            ) -> Result<std::rc::Rc<$crate::TypeInfo>, $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::write_type_info_value(
                    context,
                    target_type_id,
                )
            }

            #[inline(always)]
            fn write_with_type_info(
                value: &Self,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
            ) -> Result<(), $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::write_with_type_info(
                    value,
                    context,
                    ref_mode,
                    type_info,
                )
            }

            #[inline(always)]
            fn read(
                context: &mut $crate::ReadContext,
                ref_mode: $crate::RefMode,
                read_type_info: bool,
            ) -> Result<Self, $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::read(
                    context,
                    ref_mode,
                    read_type_info,
                )
            }

            #[inline(always)]
            fn read_with_type_info(
                context: &mut $crate::ReadContext,
                ref_mode: $crate::RefMode,
                type_info: &std::rc::Rc<$crate::TypeInfo>,
            ) -> Result<Self, $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::read_with_type_info(
                    context, ref_mode, type_info,
                )
            }

            #[inline(always)]
            fn write_type_info(
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::write_type_info(context)
            }

            #[inline(always)]
            fn read_type_info(
                context: &mut $crate::ReadContext,
            ) -> Result<(), $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::read_type_info(context)
            }

            #[inline(always)]
            fn static_type_id() -> $crate::TypeId {
                <$provider<T> as $crate::serializer::Serializer>::static_type_id()
            }

            #[inline(always)]
            fn reserved_space() -> usize {
                <$provider<T> as $crate::serializer::Serializer>::reserved_space()
            }

            const IS_OPTIONAL: bool =
                <$provider<T> as $crate::serializer::Serializer>::IS_OPTIONAL;

            #[inline(always)]
            fn is_none(value: &Self) -> bool {
                <$provider<T> as $crate::serializer::Serializer>::is_none(value)
            }

            const IS_POLYMORPHIC: bool =
                <$provider<T> as $crate::serializer::Serializer>::IS_POLYMORPHIC;

            const IS_SHARED_REF: bool =
                <$provider<T> as $crate::serializer::Serializer>::IS_SHARED_REF;

            const IS_WRAPPER: bool = $wrapper;

            const REQUIRES_SCOPED_ACCESS: bool =
                <$provider<T> as $crate::serializer::Serializer>::REQUIRES_SCOPED_ACCESS;

            const READ_DATA_ALWAYS_ADVANCES: bool =
                <$provider<T> as $crate::serializer::Serializer>::READ_DATA_ALWAYS_ADVANCES;

            #[inline(always)]
            fn dynamic_type_id(
                value: &Self,
            ) -> Result<Option<std::any::TypeId>, $crate::Error> {
                <$provider<T> as $crate::serializer::Serializer>::dynamic_type_id(value)
            }

        }
    };
}

macro_rules! impl_collection_carrier_codec {
    (
        $codec:ident,
        $container:ident,
        $wire_type:ident,
        zst_no_backing = $zst_no_backing:literal
        $(, bounds = [$($bounds:tt)+])?
    ) => {
        pub struct $codec<T, C, const NULLABLE: bool, const TRACK_REF: bool>(
            std::marker::PhantomData<(T, C)>,
        );

        impl<T, S, const NULLABLE: bool, const TRACK_REF: bool>
            $crate::serializer::Serializer for $codec<T, S, NULLABLE, TRACK_REF>
        where
            T: 'static,
            S: $crate::serializer::Serializer<Target = T>,
            $(T: $($bounds)+,)?
            $container<T>: FromIterator<T>,
            for<'a> &'a $container<T>: IntoIterator<Item = &'a T>,
            for<'a> <&'a $container<T> as IntoIterator>::IntoIter:
                ExactSizeIterator + Clone,
        {
            type Target = $container<T>;

            #[inline(always)]
            fn reserved_space() -> usize {
                std::mem::size_of::<u32>() + $crate::type_id::SIZE_OF_REF_AND_TYPE
            }

            #[inline(always)]
            fn write_data(
                value: &$container<T>,
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                $crate::serializer::collection::write_collection_value_data::<
                    T,
                    S,
                    _,
                    true,
                    $zst_no_backing,
                >(value, context)
            }

            #[inline(always)]
            fn read_data(
                context: &mut $crate::ReadContext,
            ) -> Result<$container<T>, $crate::Error> {
                $crate::serializer::collection::read_collection_value_data::<
                    $container<T>,
                    T,
                    S,
                    true,
                    $zst_no_backing,
                >(context)
            }

            #[inline(always)]
            fn default_value(
                _context: &mut $crate::ReadContext,
            ) -> Result<$container<T>, $crate::Error> {
                Ok($container::new())
            }

            #[inline(always)]
            fn write_type_info(
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                $crate::serializer::collection::write_collection_type_info(
                    context,
                    $crate::TypeId::$wire_type as u32,
                )
            }

            #[inline(always)]
            fn read_type_info(
                context: &mut $crate::ReadContext,
            ) -> Result<(), $crate::Error> {
                $crate::serializer::collection::read_collection_type_info(
                    context,
                    $crate::TypeId::$wire_type as u32,
                )
            }

            #[inline(always)]
            fn static_type_id() -> $crate::TypeId {
                $crate::TypeId::$wire_type
            }

            const READ_DATA_ALWAYS_ADVANCES: bool = true;
        }

        impl<T, C, const NULLABLE: bool, const TRACK_REF: bool>
            $crate::serializer::codec::Codec<$container<T>>
            for $codec<T, C, NULLABLE, TRACK_REF>
        where
            T: 'static,
            C: $crate::serializer::codec::Codec<T>,
            $(T: $($bounds)+,)?
            $container<T>: FromIterator<T>,
            for<'a> &'a $container<T>: IntoIterator<Item = &'a T>,
            for<'a> <&'a $container<T> as IntoIterator>::IntoIter:
                ExactSizeIterator + Clone,
        {
            #[inline(always)]
            fn field_type(
                type_resolver: &$crate::resolver::TypeResolver,
            ) -> Result<$crate::meta::FieldType, $crate::Error> {
                Ok($crate::meta::FieldType::new_with_ref(
                    $crate::TypeId::$wire_type as u32,
                    NULLABLE,
                    TRACK_REF,
                    vec![C::field_type(type_resolver)?],
                ))
            }

            #[inline(always)]
            fn write_field(
                value: &$container<T>,
                context: &mut $crate::WriteContext,
            ) -> Result<(), $crate::Error> {
                if NULLABLE || TRACK_REF {
                    context
                        .writer
                        .write_i8($crate::RefFlag::NotNullValue as i8);
                }
                $crate::serializer::collection::write_collection_data::<
                    T,
                    C,
                    _,
                    true,
                    $zst_no_backing,
                >(
                    value,
                    context,
                    true,
                )
            }

            #[inline(always)]
            fn read_field(
                context: &mut $crate::ReadContext,
            ) -> Result<$container<T>, $crate::Error> {
                if NULLABLE || TRACK_REF {
                    if context.reader.read_i8()? == $crate::RefFlag::Null as i8 {
                        return Ok($container::new());
                    }
                }
                $crate::serializer::collection::read_collection_data::<
                    $container<T>,
                    T,
                    C,
                    true,
                    $zst_no_backing,
                >(context)
            }

            #[inline(always)]
            fn read_compatible(
                context: &mut $crate::ReadContext,
                local_field_type: &$crate::meta::FieldType,
                remote_field_type: &$crate::meta::FieldType,
            ) -> Result<Option<$container<T>>, $crate::Error> {
                if $crate::serializer::codec::field_types_compatible(
                    local_field_type,
                    remote_field_type,
                ) || local_field_type.compatible_shape_match(remote_field_type)
                    || (local_field_type.type_id == remote_field_type.type_id
                        && $crate::serializer::codec::allows_missing_generics(
                            local_field_type.type_id,
                        )
                        && (local_field_type.generics.is_empty()
                            || remote_field_type.generics.is_empty()))
                {
                    return Self::read_field_with_type(context, remote_field_type).map(Some);
                }
                Ok(None)
            }

            #[inline(always)]
            fn read_data_with_type(
                context: &mut $crate::ReadContext,
                remote_data_type: &$crate::meta::FieldType,
            ) -> Result<$container<T>, $crate::Error> {
                $crate::serializer::collection::read_collection_data_with_type::<
                    $container<T>,
                    T,
                    C,
                    true,
                    $zst_no_backing,
                >(context, remote_data_type)
            }

            #[inline(always)]
            fn read_field_with_type(
                context: &mut $crate::ReadContext,
                remote_field_type: &$crate::meta::FieldType,
            ) -> Result<$container<T>, $crate::Error> {
                if $crate::serializer::codec::field_ref_mode(remote_field_type)
                    != $crate::RefMode::None
                    && context.reader.read_i8()? == $crate::RefFlag::Null as i8
                {
                    return Ok($container::new());
                }
                Self::read_data_with_type(context, remote_field_type)
            }

            #[inline(always)]
            fn write_with_mode(
                value: &$container<T>,
                context: &mut $crate::WriteContext,
                ref_mode: $crate::RefMode,
                write_type_info: bool,
                has_generics: bool,
            ) -> Result<(), $crate::Error> {
                if ref_mode != $crate::RefMode::None {
                    context
                        .writer
                        .write_i8($crate::RefFlag::NotNullValue as i8);
                }
                if write_type_info {
                    <Self as $crate::serializer::Serializer>::write_type_info(context)?;
                }
                $crate::serializer::collection::write_collection_data::<
                    T,
                    C,
                    _,
                    true,
                    $zst_no_backing,
                >(
                    value,
                    context,
                    has_generics,
                )
            }

        }
    };
}

pub mod any;
mod arc;
mod array;
mod bool;
mod box_;
#[doc(hidden)]
pub mod codec;
pub mod collection;
mod datetime;
pub mod enum_;
mod heap;
mod list;
pub mod map;
mod marker;
mod mutex;
mod number;
mod option;
mod primitive_list;
mod rc;
mod refcell;
mod scalar_conversion;
mod set;
pub mod skip;
mod string;
pub mod struct_;
pub mod trait_object;
mod tuple;
#[doc(hidden)]
pub mod unknown_case;
mod unsigned_number;
pub mod util;
pub mod weak;

mod core;
mod decimal;
pub use any::{read_box_any, write_box_any};
pub use arc::ArcSerializer;
pub use array::ArraySerializer;
pub use box_::BoxSerializer;
pub use core::{read_data, write_data, Serializer, StructSerializer};
pub use heap::BinaryHeapSerializer;
pub use list::{LinkedListSerializer, VecDequeSerializer, VecSerializer};
pub use map::{BTreeMapSerializer, HashMapSerializer};
pub use mutex::MutexSerializer;
pub use option::OptionSerializer;
pub use rc::RcSerializer;
pub use refcell::RefCellSerializer;
pub use set::{BTreeSetSerializer, HashSetSerializer};
pub use tuple::{
    Tuple10Serializer, Tuple11Serializer, Tuple12Serializer, Tuple13Serializer, Tuple14Serializer,
    Tuple15Serializer, Tuple16Serializer, Tuple17Serializer, Tuple18Serializer, Tuple19Serializer,
    Tuple1Serializer, Tuple20Serializer, Tuple21Serializer, Tuple22Serializer, Tuple2Serializer,
    Tuple3Serializer, Tuple4Serializer, Tuple5Serializer, Tuple6Serializer, Tuple7Serializer,
    Tuple8Serializer, Tuple9Serializer,
};
pub use util::send_sync::box_send_sync;
pub use weak::{ArcWeakSerializer, RcWeakSerializer};
