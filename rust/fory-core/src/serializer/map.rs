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
use crate::fory::Fory;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::serializer::Serializer;
use crate::types::{ForyGeneralList, Mode, TypeId, SIZE_OF_REF_AND_TYPE};
use anyhow::anyhow;
use std::collections::HashMap;
use std::mem;

const MAX_CHUNK_SIZE: u8 = 255;

impl<T1: Serializer + Eq + std::hash::Hash, T2: Serializer> Serializer for HashMap<T1, T2> {
    fn write(&self, context: &mut WriteContext, is_field: bool) {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            context.writer.var_uint32(TypeId::MAP as u32);
        }
        context.writer.var_uint32(self.len() as u32);
        let reserved_space = (<T1 as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE)
            * self.len()
            + (<T2 as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE) * self.len();
        context.writer.reserve(reserved_space);

        let mut header_offset = 0;
        let mut pair_counter = 0;
        let mut header_gen = false;
        for entry in self.iter() {
            if !header_gen {
                header_offset = context.writer.len();
                let _is_key_null = false;
                let _is_val_null = false;
                // todo
                // if T1::is_option() {}
                // if T2::is_option() {}
                context.writer.i16(-1);
                context
                    .writer
                    .var_uint32(T1::get_type_id(context.get_fory()));
                context
                    .writer
                    .var_uint32(T2::get_type_id(context.get_fory()));
                let header = 0;
                context.writer.set_bytes(header_offset, &[header]);
                header_gen = true;
            }
            entry.0.write(context, true);
            entry.1.write(context, true);
            pair_counter += 1;
            if pair_counter == MAX_CHUNK_SIZE {
                context.writer.set_bytes(header_offset + 1, &[pair_counter]);
                pair_counter = 0;
                header_gen = false;
            }
        }
        if pair_counter > 0 {
            assert!(header_offset > 0);
            context.writer.set_bytes(header_offset + 1, &[pair_counter]);
        }
    }

    fn read(context: &mut ReadContext, is_field: bool) -> Result<Self, Error> {
        if *context.get_fory().get_mode() == Mode::Compatible && !is_field {
            let remote_collection_type_id = context.reader.var_uint32();
            assert_eq!(remote_collection_type_id, TypeId::MAP as u32);
        }
        let mut map = HashMap::<T1, T2>::new();
        let len = context.reader.var_uint32();
        let mut len_counter = 0;
        loop {
            if len_counter == len {
                break;
            }
            let _header = context.reader.u8();
            let chunk_size = context.reader.u8();
            let actual_key_id = context.reader.var_uint32();
            let expected_key_id = T1::get_type_id(context.fory);
            ensure!(
                expected_key_id == actual_key_id,
                anyhow!("Invalid field type, expected:{expected_key_id}, actual:{actual_key_id}")
            );
            let actual_val_id = context.reader.var_uint32();
            let expected_val_id = T2::get_type_id(context.fory);
            ensure!(
                expected_val_id == actual_val_id,
                anyhow!("Invalid field type, expected:{expected_val_id}, actual:{actual_val_id}")
            );
            assert!(len_counter + chunk_size as u32 <= len);
            for _ in (0..chunk_size).enumerate() {
                map.insert(T1::read(context, true)?, T2::read(context, true)?);
            }
            len_counter += chunk_size as u32;
        }
        Ok(map)
    }

    fn reserved_space() -> usize {
        mem::size_of::<i32>()
    }

    fn get_type_id(_fory: &Fory) -> u32 {
        TypeId::MAP as u32
    }
}

impl<T1: Serializer + Eq + std::hash::Hash, T2: Serializer> ForyGeneralList for HashMap<T1, T2> {}
