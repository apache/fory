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
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{Serializer, StructSerializer};
use crate::types::{config_flags, Language, Mode, SIZE_OF_REF_AND_TYPE};

pub struct Fory {
    mode: Mode,
    type_resolver: TypeResolver,
}

impl Default for Fory {
    fn default() -> Self {
        Fory {
            mode: Mode::SchemaConsistent,
            type_resolver: TypeResolver::default(),
        }
    }
}

impl Fory {
    pub fn mode(mut self, mode: Mode) -> Self {
        self.mode = mode;
        self
    }

    pub fn get_mode(&self) -> &Mode {
        &self.mode
    }

    pub fn write_head<T: Serializer>(&self, writer: &mut Writer) -> usize {
        const HEAD_SIZE: usize = 10;
        writer.reserve(<T as Serializer>::reserved_space() + SIZE_OF_REF_AND_TYPE + HEAD_SIZE);
        let mut bitmap = 0;
        bitmap |= config_flags::IS_LITTLE_ENDIAN_FLAG;
        bitmap |= config_flags::IS_CROSS_LANGUAGE_FLAG;
        writer.u8(bitmap);
        writer.u8(Language::Rust as u8);
        writer.skip(4); // meta offset
        writer.len() - 4
    }

    fn read_head(&self, reader: &mut Reader) -> Result<u32, Error> {
        let _bitmap = reader.u8();
        let _language: Language = reader.u8().try_into()?;
        Ok(reader.u32())
    }

    pub fn deserialize<T: Serializer>(&self, bf: &[u8]) -> Result<T, Error> {
        let mut reader = Reader::new(bf);
        let meta_offset = self.read_head(&mut reader)?;
        let mut context = ReadContext::new(self, reader);
        if meta_offset > 0 {
            context.load_meta(meta_offset as usize);
        }
        <T as Serializer>::deserialize(&mut context)
    }

    pub fn serialize<T: Serializer>(&self, record: &T) -> Vec<u8> {
        let mut writer = Writer::default();
        let meta_offset = self.write_head::<T>(&mut writer);
        let mut context: WriteContext<'_> = WriteContext::new(self, &mut writer);
        <T as Serializer>::serialize(record, &mut context);
        if Mode::Compatible == self.mode {
            context.write_meta(meta_offset);
        }
        writer.dump()
    }

    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    pub fn register<T: 'static + StructSerializer>(&mut self, id: i16) {
        let type_info = TypeInfo::new::<T>(self, id);
        self.type_resolver.register::<T>(type_info, id);
    }
}
