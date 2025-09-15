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
use anyhow::anyhow;

use crate::meta::TypeMeta;
use crate::resolver::meta_resolver::{MetaReaderResolver, MetaWriterResolver};
use std::rc::Rc;

pub struct WriteContext<'se> {
    pub writer: &'se mut Writer,
    pub tags: Vec<&'static str>,
    fory: &'se Fory,
    meta_resolver: MetaWriterResolver<'se>,
}

impl<'se> WriteContext<'se> {
    pub fn new(fory: &'se Fory, writer: &'se mut Writer) -> WriteContext<'se> {
        WriteContext {
            writer,
            tags: Vec::new(),
            fory,
            meta_resolver: MetaWriterResolver::default(),
        }
    }

    pub fn empty(&mut self) -> bool {
        self.meta_resolver.empty()
    }

    pub fn push_meta(&mut self, type_id: std::any::TypeId) -> usize {
        self.meta_resolver.push(type_id, self.fory)
    }

    pub fn write_meta(&mut self, offset: usize) {
        self.writer
            .set_bytes(offset, &(self.writer.len() as u32).to_le_bytes());
        self.meta_resolver.to_bytes(self.writer).unwrap()
    }

    pub fn get_fory(&self) -> &Fory {
        self.fory
    }

    pub fn write_tag(&mut self, tag: &'static str) {
        const USESTRINGVALUE: u8 = 0;
        const USESTRINGID: u8 = 1;

        let mayby_idx = self.tags.iter().position(|x| *x == tag);
        match mayby_idx {
            Some(idx) => {
                self.writer.u8(USESTRINGID);
                self.writer.i16(idx as i16);
            }
            None => {
                self.writer.u8(USESTRINGVALUE);
                self.writer.skip(8); // todo tag hash
                self.writer.i16(tag.len() as i16);
                self.writer.bytes(tag.as_bytes());
            }
        };
    }
}

pub struct ReadContext<'de, 'bf: 'de> {
    pub reader: Reader<'bf>,
    pub tags: Vec<&'de str>,
    pub fory: &'de Fory,
    pub meta_resolver: MetaReaderResolver,
}

impl<'de, 'bf: 'de> ReadContext<'de, 'bf> {
    pub fn new(fory: &'de Fory, reader: Reader<'bf>) -> ReadContext<'de, 'bf> {
        ReadContext {
            reader,
            tags: Vec::new(),
            fory,
            meta_resolver: MetaReaderResolver::default(),
        }
    }

    pub fn get_fory(&self) -> &Fory {
        self.fory
    }

    pub fn get_meta(&self, type_index: usize) -> &Rc<TypeMeta> {
        self.meta_resolver.get(type_index)
    }

    pub fn get_meta_by_type_id(&self, type_id: u32) -> Rc<TypeMeta> {
        let type_defs: Vec<_> = self.meta_resolver.reading_type_defs.to_vec();
        for type_def in type_defs.iter() {
            if type_def.get_type_id() == type_id {
                return type_def.clone();
            }
        }
        unreachable!()
    }

    pub fn load_meta(&mut self, offset: usize) {
        self.meta_resolver
            .load(&mut Reader::new(&self.reader.slice()[offset..]))
    }

    pub fn read_tag(&mut self) -> Result<&str, Error> {
        const USESTRINGVALUE: u8 = 0;
        const USESTRINGID: u8 = 1;
        let tag_type = self.reader.u8();
        if tag_type == USESTRINGID {
            Ok(self.tags[self.reader.i16() as usize])
        } else if tag_type == USESTRINGVALUE {
            self.reader.skip(8); // todo tag hash
            let len = self.reader.i16();
            let tag: &str =
                unsafe { std::str::from_utf8_unchecked(self.reader.bytes(len as usize)) };
            self.tags.push(tag);
            Ok(tag)
        } else {
            Err(anyhow!("Unknown tag type, value:{tag_type}"))?
        }
    }
}
