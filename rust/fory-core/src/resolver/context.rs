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
}

pub struct ReadContext<'de, 'bf: 'de> {
    pub reader: Reader<'bf>,
    fory: &'de Fory,
    pub meta_resolver: MetaReaderResolver,
    pub ref_reader: RefReader,
}

impl<'de, 'bf: 'de> ReadContext<'de, 'bf> {
    pub fn new(fory: &'de Fory, reader: Reader<'bf>) -> ReadContext<'de, 'bf> {
        ReadContext {
            reader,
            fory,
            meta_resolver: MetaReaderResolver::default(),
            ref_reader: RefReader::new(),
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
}
