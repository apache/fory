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
use crate::fory::Fory;

use crate::meta::TypeMeta;
use crate::resolver::meta_resolver::{MetaReaderResolver, MetaWriterResolver};
use crate::resolver::ref_resolver::{RefReader, RefWriter};
use crate::resolver::type_resolver::Harness;
use std::any::TypeId;
use std::rc::Rc;

pub struct WriteContext<'se> {
    pub writer: &'se mut Writer,
    fory: &'se Fory,
    meta_resolver: MetaWriterResolver<'se>,
    pub ref_writer: RefWriter,
}

impl<'se> WriteContext<'se> {
    pub fn new(fory: &'se Fory, writer: &'se mut Writer) -> WriteContext<'se> {
        WriteContext {
            writer,
            fory,
            meta_resolver: MetaWriterResolver::default(),
            ref_writer: RefWriter::new(),
        }
    }

    pub fn empty(&mut self) -> bool {
        self.meta_resolver.empty()
    }

    pub fn push_meta(&mut self, type_id: std::any::TypeId) -> usize {
        self.meta_resolver.push(type_id, self.fory)
    }

    pub fn write_meta(&mut self, offset: usize) {
        self.writer.set_bytes(
            offset,
            &((self.writer.len() - offset - 4) as u32).to_le_bytes(),
        );
        self.meta_resolver.to_bytes(self.writer).unwrap()
    }

    pub fn get_fory(&self) -> &Fory {
        self.fory
    }

    pub fn write_any_typeinfo(&mut self, concrete_type_id: TypeId) -> &'se Harness {
        use crate::types::TypeId as ForyTypeId;

        let type_resolver = self.fory.get_type_resolver();
        let type_info = type_resolver.get_type_info(concrete_type_id);
        let fory_type_id = type_info.get_type_id();

        if type_info.is_registered_by_name() {
            if fory_type_id & 0xff == ForyTypeId::NAMED_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                if self.fory.is_share_meta() {
                    let meta_index = self.push_meta(concrete_type_id) as u32;
                    self.writer.write_varuint32(meta_index);
                } else {
                    type_info.get_namespace().write_to(self.writer);
                    type_info.get_type_name().write_to(self.writer);
                }
            } else if fory_type_id & 0xff == ForyTypeId::NAMED_COMPATIBLE_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                let meta_index = self.push_meta(concrete_type_id) as u32;
                self.writer.write_varuint32(meta_index);
            } else {
                self.writer.write_varuint32(u32::MAX);
                type_info.get_namespace().write_to(self.writer);
                type_info.get_type_name().write_to(self.writer);
            }
            type_resolver
                .get_name_harness(type_info.get_namespace(), type_info.get_type_name())
                .expect("Name harness not found")
        } else {
            if fory_type_id & 0xff == ForyTypeId::COMPATIBLE_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                let meta_index = self.push_meta(concrete_type_id) as u32;
                self.writer.write_varuint32(meta_index);
            } else {
                self.writer.write_varuint32(fory_type_id);
            }
            type_resolver
                .get_harness(fory_type_id)
                .expect("ID harness not found")
        }
    }
}

pub struct ReadContext<'de, 'bf: 'de> {
    pub reader: Reader<'bf>,
    fory: &'de Fory,
    pub meta_resolver: MetaReaderResolver,
    pub ref_reader: RefReader,
    max_dyn_depth: u32,
    current_depth: u32,
}

impl<'de, 'bf: 'de> ReadContext<'de, 'bf> {
    pub fn new(fory: &'de Fory, reader: Reader<'bf>, max_dyn_depth: u32) -> ReadContext<'de, 'bf> {
        ReadContext {
            reader,
            fory,
            meta_resolver: MetaReaderResolver::default(),
            ref_reader: RefReader::new(),
            max_dyn_depth,
            current_depth: 0,
        }
    }

    pub fn get_fory(&self) -> &Fory {
        self.fory
    }

    pub fn get_meta(&self, type_index: usize) -> &Rc<TypeMeta> {
        self.meta_resolver.get(type_index)
    }

    pub fn load_meta(&mut self, offset: usize) -> usize {
        self.meta_resolver.load(&mut Reader::new(
            &self.reader.slice_after_cursor()[offset..],
        ))
    }

    pub fn read_any_typeinfo(&mut self) -> &'de Harness {
        use crate::types::TypeId as ForyTypeId;

        let fory_type_id = self.reader.read_varuint32();
        let type_resolver = self.fory.get_type_resolver();

        if fory_type_id == u32::MAX {
            let namespace = self.meta_resolver.read_metastring(&mut self.reader);
            let type_name = self.meta_resolver.read_metastring(&mut self.reader);
            type_resolver
                .get_name_harness(&namespace, &type_name)
                .expect("Name harness not found")
        } else if fory_type_id & 0xff == ForyTypeId::NAMED_STRUCT as u32 {
            if self.fory.is_share_meta() {
                let _meta_index = self.reader.read_varuint32();
            } else {
                let namespace = self.meta_resolver.read_metastring(&mut self.reader);
                let type_name = self.meta_resolver.read_metastring(&mut self.reader);
                return type_resolver
                    .get_name_harness(&namespace, &type_name)
                    .expect("Name harness not found");
            }
            type_resolver
                .get_harness(fory_type_id)
                .expect("ID harness not found")
        } else if fory_type_id & 0xff == ForyTypeId::NAMED_COMPATIBLE_STRUCT as u32
            || fory_type_id & 0xff == ForyTypeId::COMPATIBLE_STRUCT as u32
        {
            let _meta_index = self.reader.read_varuint32();
            type_resolver
                .get_harness(fory_type_id)
                .expect("ID harness not found")
        } else {
            type_resolver
                .get_harness(fory_type_id)
                .expect("ID harness not found")
        }
    }

    pub fn inc_depth(&mut self) -> Result<(), crate::error::Error> {
        self.current_depth += 1;
        if self.current_depth > self.max_dyn_depth {
            return Err(crate::error::Error::Other(crate::error::AnyhowError::msg(
                format!(
                    "Maximum dynamic object nesting depth ({}) exceeded. Current depth: {}. \
                    This may indicate a circular reference or overly deep object graph. \
                    Consider increasing max_dyn_depth if this is expected.",
                    self.max_dyn_depth, self.current_depth
                ),
            )));
        }
        Ok(())
    }

    pub fn dec_depth(&mut self) {
        self.current_depth = self.current_depth.saturating_sub(1);
    }
}
