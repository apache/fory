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
use crate::serializer::Serializer;
use crate::types::SIZE_OF_REF_AND_TYPE;
use anyhow::anyhow;

// const TRACKING_REF: u8 = 0b1;

const HAS_NULL: u8 = 0b10;

// Whether collection elements type is not declare type.
const NOT_DECL_ELEMENT_TYPE: u8 = 0b100;

//  Whether collection elements type different.
// const NOT_SAME_TYPE: u8 = 0b1000;

pub fn write_collection<'a, T: Serializer + 'a, I: IntoIterator<Item = &'a T>>(
    iter: I,
    context: &mut WriteContext,
) {
    let iter = iter.into_iter();
    let len = iter.size_hint().0;
    context.writer.var_uint32(len as u32);
    if len == 0 {
        return
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
            item.write(context);
        }
    } else {
        context
            .writer
            .reserve((<T as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE) * len);
        for item in iter {
            // println!("collection write: {:?}", context.writer.dump());
            item.serialize_field(context);
        }
        // println!("collection write: {:?}", context.writer.dump());
    }
}

pub fn read_collection<C, T>(context: &mut ReadContext) -> Result<C, Error>
where
    T: Serializer,
    C: FromIterator<T>,
{
    let len = context.reader.var_uint32();
    if len == 0 {
        return Ok(C::from_iter(std::iter::empty()));
    }
    // println!("len: {}", len);
    let header = context.reader.u8();
    let actual_elem_type_id = context.reader.var_uint32();
    let expected_elem_id = T::get_type_id(context.fory);
    ensure!(
        expected_elem_id == actual_elem_type_id,
        anyhow!("Invalid field type, expected:{expected_elem_id}, actual:{actual_elem_type_id}")
    );
    if header & HAS_NULL == 0 {
        (0..len)
            .map(|_| T::read(context))
            .collect::<Result<C, Error>>()
    } else {
        // println!("collection read: {:?}", context.reader.slice_after_cursor());
        (0..len)
            .map(|_| T::deserialize_field(context))
            .collect::<Result<C, Error>>()
    }
}
