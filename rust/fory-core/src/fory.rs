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
use crate::ensure;
use crate::error::Error;
use crate::resolver::context::ReadContext;
use crate::resolver::context::WriteContext;
use crate::resolver::metastring_resolver::MetaStringResolver;
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::ForyDefault;
use crate::serializer::{Serializer, StructSerializer};
use crate::types::config_flags::IS_NULL_FLAG;
use crate::types::{
    config_flags::{IS_CROSS_LANGUAGE_FLAG, IS_LITTLE_ENDIAN_FLAG},
    Language, Mode, MAGIC_NUMBER, SIZE_OF_REF_AND_TYPE,
};
use crate::util::get_ext_actual_type_id;
use anyhow::anyhow;
use std::cell::RefCell;
use std::rc::Rc;

static EMPTY_STRING: String = String::new();

pub struct Fory {
    mode: Mode,
    xlang: bool,
    share_meta: bool,
    type_resolver: TypeResolver,
    metastring_resolver: Rc<RefCell<MetaStringResolver>>,
    compress_string: bool,
}

impl Default for Fory {
    fn default() -> Self {
        Fory {
            mode: Mode::SchemaConsistent,
            xlang: true,
            share_meta: false,
            type_resolver: TypeResolver::default(),
            metastring_resolver: Rc::from(RefCell::from(MetaStringResolver::default())),
            compress_string: false,
        }
    }
}

impl Fory {
    pub fn mode(mut self, mode: Mode) -> Self {
        // Setting share_meta individually is not supported currently
        self.share_meta = mode != Mode::SchemaConsistent;
        self.mode = mode;
        self
    }

    pub fn xlang(mut self, xlang: bool) -> Self {
        self.xlang = xlang;
        self
    }

    pub fn compress_string(mut self, compress_string: bool) -> Self {
        self.compress_string = compress_string;
        self
    }

    pub fn get_mode(&self) -> &Mode {
        &self.mode
    }

    pub fn is_compress_string(&self) -> bool {
        self.compress_string
    }

    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    pub fn get_metastring_resolver(&self) -> Rc<RefCell<MetaStringResolver>> {
        Rc::clone(&self.metastring_resolver)
    }

    pub fn write_head<T: Serializer>(&self, is_none: bool, writer: &mut Writer) {
        const HEAD_SIZE: usize = 10;
        writer.reserve(T::fory_reserved_space() + SIZE_OF_REF_AND_TYPE + HEAD_SIZE);
        if self.xlang {
            writer.write_u16(MAGIC_NUMBER);
        }
        #[cfg(target_endian = "big")]
        let mut bitmap = 0;
        #[cfg(target_endian = "little")]
        let mut bitmap = IS_LITTLE_ENDIAN_FLAG;
        if self.xlang {
            bitmap |= IS_CROSS_LANGUAGE_FLAG;
        }
        if is_none {
            bitmap |= IS_NULL_FLAG;
        }
        writer.write_u8(bitmap);
        if is_none {
            return;
        }
        if self.xlang {
            writer.write_u8(Language::Rust as u8);
        }
    }

    fn read_head(&self, reader: &mut Reader) -> Result<bool, Error> {
        if self.xlang {
            let magic_numer = reader.read_u16();
            ensure!(
                magic_numer == MAGIC_NUMBER,
                anyhow!(
                    "The fory xlang serialization must start with magic number {:X}. \
                    Please check whether the serialization is based on the xlang protocol \
                    and the data didn't corrupt.",
                    MAGIC_NUMBER
                )
            )
        }
        let bitmap = reader.read_u8();
        let peer_is_xlang = (bitmap & IS_CROSS_LANGUAGE_FLAG) != 0;
        ensure!(
            self.xlang == peer_is_xlang,
            anyhow!("header bitmap mismatch at xlang bit")
        );
        let is_little_endian = (bitmap & IS_LITTLE_ENDIAN_FLAG) != 0;
        ensure!(
            is_little_endian,
            anyhow!(
                "Big endian is not supported for now, please ensure peer machine is little endian."
            )
        );
        let is_none = (bitmap & IS_NULL_FLAG) != 0;
        if is_none {
            return Ok(true);
        }
        if peer_is_xlang {
            let _peer_lang = reader.read_u8();
        }
        Ok(false)
    }

    pub fn deserialize<T: Serializer + ForyDefault>(&self, bf: &[u8]) -> Result<T, Error> {
        let reader = Reader::new(bf);
        let mut context = ReadContext::new(self, reader);
        let result = self.deserialize_with_context(&mut context);
        assert_eq!(context.reader.slice_after_cursor().len(), 0);
        result
    }

    pub fn deserialize_with_context<T: Serializer + ForyDefault>(
        &self,
        context: &mut ReadContext,
    ) -> Result<T, Error> {
        let is_none = self.read_head(&mut context.reader)?;
        if is_none {
            return Ok(T::fory_default());
        }
        let mut bytes_to_skip = 0;
        if self.mode == Mode::Compatible {
            let meta_offset = context.reader.read_i32();
            if meta_offset != -1 {
                bytes_to_skip = context.load_meta(meta_offset as usize);
            }
        }
        let result = <T as Serializer>::fory_read(context, false);
        if bytes_to_skip > 0 {
            context.reader.skip(bytes_to_skip as u32);
        }
        context.ref_reader.resolve_callbacks();
        result
    }

    pub fn serialize<T: Serializer>(&self, record: &T) -> Vec<u8> {
        let mut writer = Writer::default();
        let mut context: WriteContext<'_> = WriteContext::new(self, &mut writer);
        self.serialize_with_context(record, &mut context)
    }

    pub fn serialize_with_context<T: Serializer>(
        &self,
        record: &T,
        context: &mut WriteContext,
    ) -> Vec<u8> {
        let is_none = record.fory_is_none();
        self.write_head::<T>(is_none, context.writer);
        let meta_start_offset = context.writer.len();
        if !is_none {
            if self.mode == Mode::Compatible {
                context.writer.write_i32(-1);
            };
            <T as Serializer>::fory_write(record, context, false);
            if self.mode == Mode::Compatible && !context.empty() {
                context.write_meta(meta_start_offset);
            }
        }
        context.writer.dump()
    }

    pub fn register<T: 'static + StructSerializer + Serializer + ForyDefault>(&mut self, id: u32) {
        let actual_type_id = T::fory_actual_type_id(id, false, &self.mode);
        let type_info =
            TypeInfo::new::<T>(self, actual_type_id, &EMPTY_STRING, &EMPTY_STRING, false);
        self.type_resolver.register::<T>(&type_info);
    }

    pub fn register_by_namespace<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) {
        let actual_type_id = T::fory_actual_type_id(0, true, &self.mode);
        let type_info = TypeInfo::new::<T>(self, actual_type_id, namespace, type_name, true);
        self.type_resolver.register::<T>(&type_info);
    }

    pub fn register_by_name<T: 'static + StructSerializer + Serializer + ForyDefault>(
        &mut self,
        type_name: &str,
    ) {
        self.register_by_namespace::<T>("", type_name);
    }

    pub fn register_serializer<T: Serializer + ForyDefault>(&mut self, id: u32) {
        let actual_type_id = get_ext_actual_type_id(id, false);
        let type_info = TypeInfo::new_with_empty_fields::<T>(
            self,
            actual_type_id,
            &EMPTY_STRING,
            &EMPTY_STRING,
            false,
        );
        self.type_resolver.register_serializer::<T>(&type_info);
    }

    pub fn register_serializer_by_namespace<T: Serializer + ForyDefault>(
        &mut self,
        namespace: &str,
        type_name: &str,
    ) {
        let actual_type_id = get_ext_actual_type_id(0, true);
        let type_info =
            TypeInfo::new_with_empty_fields::<T>(self, actual_type_id, namespace, type_name, true);
        self.type_resolver.register_serializer::<T>(&type_info);
    }

    pub fn register_serializer_by_name<T: Serializer + ForyDefault>(&mut self, type_name: &str) {
        self.register_serializer_by_namespace::<T>("", type_name);
    }
}

pub fn write_data<T: Serializer>(this: &T, context: &mut WriteContext, is_field: bool) {
    T::fory_write_data(this, context, is_field);
}

pub fn read_data<T: Serializer + ForyDefault>(
    context: &mut ReadContext,
    is_field: bool,
) -> Result<T, Error> {
    T::fory_read_data(context, is_field)
}
