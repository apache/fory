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
use crate::error::Error;
use crate::fory::Fory;
use crate::meta::TypeMeta;
use std::collections::HashMap;
use std::rc::Rc;

#[derive(Default)]
pub struct MetaReaderResolver {
    pub reading_type_defs: Vec<Rc<TypeMeta>>,
}

impl MetaReaderResolver {
    pub fn get(&self, index: usize) -> &Rc<TypeMeta> {
        unsafe { self.reading_type_defs.get_unchecked(index) }
    }

    pub fn load(&mut self, reader: &mut Reader) -> usize {
        let meta_size = reader.var_uint32();
        // self.reading_type_defs.reserve(meta_size as usize);
        for _ in 0..meta_size {
            self.reading_type_defs
                .push(Rc::new(TypeMeta::from_bytes(reader)));
        }
        reader.get_cursor()
    }
}

#[derive(Default)]
pub struct MetaWriterResolver<'a> {
    type_defs: Vec<&'a Vec<u8>>,
    type_id_index_map: HashMap<std::any::TypeId, usize>,
}

#[allow(dead_code)]
impl<'a> MetaWriterResolver<'a> {
    pub fn push<'b: 'a>(&mut self, type_id: std::any::TypeId, fory: &'a Fory) -> usize {
        match self.type_id_index_map.get(&type_id) {
            None => {
                let index = self.type_defs.len();
                self.type_defs.push(
                    fory.get_type_resolver()
                        .get_type_info(type_id)
                        .get_type_def(),
                );
                self.type_id_index_map.insert(type_id, index);
                index
            }
            Some(index) => *index,
        }
    }

    pub fn to_bytes(&self, writer: &mut Writer) -> Result<(), Error> {
        writer.var_uint32(self.type_defs.len() as u32);
        for item in &self.type_defs {
            writer.bytes(item);
        }
        Ok(())
    }

    pub fn empty(&mut self) -> bool {
        self.type_defs.is_empty()
    }

    pub fn reset(&mut self) {
        self.type_defs.clear();
    }
}
