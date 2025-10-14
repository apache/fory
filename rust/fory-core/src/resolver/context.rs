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

use crate::error::Error;
use crate::meta::{MetaString, TypeMeta};
use crate::resolver::meta_resolver::{MetaReaderResolver, MetaWriterResolver};
use crate::resolver::metastring_resolver::{
    MetaStringBytes, MetaStringReaderResolver, MetaStringWriterResolver,
};
use crate::resolver::ref_resolver::{RefReader, RefWriter};
use crate::resolver::type_resolver::{Harness, TypeResolver};
use std::rc::Rc;
use std::sync::{Arc, Mutex};

pub struct WriteContext {
    pub writer: Writer,
    meta_resolver: MetaWriterResolver,
    meta_string_resolver: MetaStringWriterResolver,
    pub ref_writer: RefWriter,
}

impl WriteContext {
    pub fn new(writer: Writer) -> WriteContext {
        WriteContext {
            writer,
            meta_resolver: MetaWriterResolver::default(),
            meta_string_resolver: MetaStringWriterResolver::default(),
            ref_writer: RefWriter::new(),
        }
    }

    #[inline(always)]
    pub fn empty(&mut self) -> bool {
        self.meta_resolver.empty()
    }

    #[inline(always)]
    pub fn push_meta(&mut self, fory: &Fory, type_id: std::any::TypeId) -> Result<usize, Error> {
        self.meta_resolver.push(type_id, fory)
    }

    #[inline(always)]
    pub fn write_meta(&mut self, offset: usize) {
        self.writer.set_bytes(
            offset,
            &((self.writer.len() - offset - 4) as u32).to_le_bytes(),
        );
        self.meta_resolver.to_bytes(&mut self.writer);
    }

    pub fn write_any_typeinfo(
        &mut self,
        fory: &Fory,
        concrete_type_id: std::any::TypeId,
    ) -> Result<Arc<Harness>, Error> {
        use crate::types::TypeId as ForyTypeId;

        let type_resolver = fory.get_type_resolver();
        let type_info = type_resolver.get_type_info(concrete_type_id)?;
        let fory_type_id = type_info.get_type_id();

        if type_info.is_registered_by_name() {
            if fory_type_id & 0xff == ForyTypeId::NAMED_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                if fory.is_share_meta() {
                    let meta_index = self.push_meta(fory, concrete_type_id)? as u32;
                    self.writer.write_varuint32(meta_index);
                } else {
                    type_info.get_namespace().write_to(&mut self.writer);
                    type_info.get_type_name().write_to(&mut self.writer);
                }
            } else if fory_type_id & 0xff == ForyTypeId::NAMED_COMPATIBLE_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                let meta_index = self.push_meta(fory, concrete_type_id)? as u32;
                self.writer.write_varuint32(meta_index);
            } else {
                self.writer.write_varuint32(u32::MAX);
                type_info.get_namespace().write_to(&mut self.writer);
                type_info.get_type_name().write_to(&mut self.writer);
            }
            type_resolver
                .get_name_harness(type_info.get_namespace(), type_info.get_type_name())
                .ok_or_else(|| Error::TypeError("Name harness not found".into()))
        } else {
            if fory_type_id & 0xff == ForyTypeId::COMPATIBLE_STRUCT as u32 {
                self.writer.write_varuint32(fory_type_id);
                let meta_index = self.push_meta(fory, concrete_type_id)? as u32;
                self.writer.write_varuint32(meta_index);
            } else {
                self.writer.write_varuint32(fory_type_id);
            }
            type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        }
    }

    #[inline(always)]
    pub fn write_meta_string_bytes(&mut self, ms: &MetaString) -> Result<(), Error> {
        self.meta_string_resolver
            .write_meta_string_bytes(&mut self.writer, ms)
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.meta_resolver.reset();
        self.ref_writer.reset();
        self.writer.reset();
    }
}

pub struct ReadContext {
    pub reader: Reader,
    pub meta_resolver: MetaReaderResolver,
    meta_string_resolver: MetaStringReaderResolver,
    pub ref_reader: RefReader,
    max_dyn_depth: u32,
    current_depth: u32,
}

impl ReadContext {
    pub fn new(reader: Reader, max_dyn_depth: u32) -> ReadContext {
        ReadContext {
            reader,
            meta_resolver: MetaReaderResolver::default(),
            meta_string_resolver: MetaStringReaderResolver::default(),
            ref_reader: RefReader::new(),
            max_dyn_depth,
            current_depth: 0,
        }
    }

    #[inline(always)]
    pub fn init(&mut self, bytes: &[u8], max_dyn_depth: u32) {
        self.reader.init(bytes);
        self.max_dyn_depth = max_dyn_depth;
        self.current_depth = 0;
    }

    #[inline(always)]
    pub fn get_meta(&self, type_index: usize) -> &Arc<TypeMeta> {
        self.meta_resolver.get(type_index)
    }

    #[inline(always)]
    pub fn load_meta(
        &mut self,
        type_resolver: &TypeResolver,
        offset: usize,
    ) -> Result<usize, Error> {
        self.meta_resolver.load(
            type_resolver,
            &mut Reader::new(&self.reader.slice_after_cursor()[offset..]),
        )
    }

    pub fn read_any_typeinfo(&mut self, fory: &Fory) -> Result<Arc<Harness>, Error> {
        use crate::types::TypeId as ForyTypeId;

        let fory_type_id = self.reader.read_varuint32()?;
        let type_resolver = fory.get_type_resolver();

        if fory_type_id == u32::MAX {
            let namespace = self.meta_resolver.read_metastring(&mut self.reader)?;
            let type_name = self.meta_resolver.read_metastring(&mut self.reader)?;
            type_resolver
                .get_name_harness(&namespace, &type_name)
                .ok_or_else(|| Error::TypeError("Name harness not found".into()))
        } else if fory_type_id & 0xff == ForyTypeId::NAMED_STRUCT as u32 {
            if fory.is_share_meta() {
                let _meta_index = self.reader.read_varuint32();
            } else {
                let namespace = self.meta_resolver.read_metastring(&mut self.reader)?;
                let type_name = self.meta_resolver.read_metastring(&mut self.reader)?;
                return type_resolver
                    .get_name_harness(&namespace, &type_name)
                    .ok_or_else(|| Error::TypeError("Name harness not found".into()));
            }
            type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        } else if fory_type_id & 0xff == ForyTypeId::NAMED_COMPATIBLE_STRUCT as u32
            || fory_type_id & 0xff == ForyTypeId::COMPATIBLE_STRUCT as u32
        {
            let _meta_index = self.reader.read_varuint32();
            type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        } else {
            type_resolver
                .get_harness(fory_type_id)
                .ok_or_else(|| Error::TypeError("ID harness not found".into()))
        }
    }

    pub fn read_meta_string_bytes(&mut self) -> Result<Rc<MetaStringBytes>, Error> {
        self.meta_string_resolver
            .read_meta_string_bytes(&mut self.reader)
    }

    #[inline(always)]
    pub fn inc_depth(&mut self) -> Result<(), Error> {
        self.current_depth += 1;
        if self.current_depth > self.max_dyn_depth {
            return Err(Error::DepthExceed(
                format!(
                    "Maximum dynamic object nesting depth ({}) exceeded. Current depth: {}. \
                    This may indicate a circular reference or overly deep object graph. \
                    Consider increasing max_dyn_depth if this is expected.",
                    self.max_dyn_depth, self.current_depth
                )
                .into(),
            ));
        }
        Ok(())
    }

    #[inline(always)]
    pub fn dec_depth(&mut self) {
        self.current_depth = self.current_depth.saturating_sub(1);
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.reader.reset();
        self.meta_resolver.reset();
        self.ref_reader.reset();
    }
}

pub struct Pool<T> {
    items: Mutex<Vec<T>>,
    factory: fn() -> T,
}

impl<T> Pool<T> {
    pub fn new(factory: fn() -> T) -> Self {
        Pool {
            items: Mutex::new(vec![]),
            factory,
        }
    }

    #[inline(always)]
    pub fn get(&self) -> T {
        let item = self
            .items
            .lock()
            .unwrap()
            .pop()
            .unwrap_or_else(|| (self.factory)());
        // println!("Object address: {:p}", &item);
        item
    }

    // put back manually
    #[inline(always)]
    pub fn put(&self, item: T) {
        self.items.lock().unwrap().push(item);
    }
}
