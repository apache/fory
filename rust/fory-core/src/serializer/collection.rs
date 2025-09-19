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

use crate::ensure;
use crate::error::Error;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::{deserialize, serialize, Serializer};
use crate::types::{Mode, SIZE_OF_REF_AND_TYPE};
use anyhow::anyhow;

// const TRACKING_REF: u8 = 0b1;

pub const HAS_NULL: u8 = 0b10;

// Whether collection elements type is not declare type.
const NOT_DECL_ELEMENT_TYPE: u8 = 0b100;

//  Whether collection elements type different.
// const NOT_SAME_TYPE: u8 = 0b1000;

pub fn write_collection<'a, T: Serializer + 'a, I: IntoIterator<Item = &'a T>>(
    iter: I,
    context: &mut WriteContext,
    is_field: bool,
    collection_type_id: u32,
) {
    if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
        context.writer.var_uint32(collection_type_id);
    }
    let iter = iter.into_iter();
    let len = iter.size_hint().0;
    context.writer.var_uint32(len as u32);
    if len == 0 {
        return;
    }
    let has_null = T::is_option();
    let mut header = 0;
    if has_null {
        header |= HAS_NULL;
    }
    header |= NOT_DECL_ELEMENT_TYPE;
    context.writer.u8(header);
    context
        .writer
        .var_uint32(T::get_type_id(context.get_fory()));
    if !has_null {
        context
            .writer
            .reserve((<T as Serializer>::reserved_space()) * len);
        for item in iter {
            item.write(context, true);
        }
    } else {
        context
            .writer
            .reserve((<T as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE) * len);
        for item in iter {
            serialize(item, context, true);
        }
    }
    println!("bytes after write collection {:?}", context.writer.dump());
}

pub fn read_collection<C, T>(
    context: &mut ReadContext,
    is_field: bool,
    expected_collection_type_id: u32,
) -> Result<C, Error>
where
    T: Serializer,
    C: FromIterator<T>,
{
    if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
        let remote_collection_type_id = context.reader.var_uint32();
        assert_eq!(remote_collection_type_id, expected_collection_type_id);
    }
    let len = context.reader.var_uint32();
    if len == 0 {
        return Ok(C::from_iter(std::iter::empty()));
    }
    let header = context.reader.u8();
    let actual_elem_type_id = context.reader.var_uint32();
    let expected_elem_id = T::get_type_id(context.fory);
    ensure!(
        expected_elem_id == actual_elem_type_id,
        anyhow!("Invalid field type, expected:{expected_elem_id}, actual:{actual_elem_type_id}")
    );
    if header & HAS_NULL == 0 {
        (0..len)
            .map(|_| T::read(context, true))
            .collect::<Result<C, Error>>()
    } else {
        (0..len)
            .map(|_| deserialize(context, true))
            .collect::<Result<C, Error>>()
    }
}
