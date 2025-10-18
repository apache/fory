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

use crate::buffer::Writer;
use crate::error::Error;
use crate::meta::{murmurhash3_x64_128, NAMESPACE_DECODER};
use crate::meta::{Encoding, MetaString};
use crate::{ensure, Reader};
use std::collections::HashMap;
use std::convert::TryInto;
use std::rc::Rc;
use std::sync::Arc;

use once_cell::sync::Lazy;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct MetaStringBytes {
    pub bytes: Vec<u8>,
    pub hash_code: i64,
    pub encoding: Encoding,
    pub first8: u64,
    pub second8: u64,
}

const HEADER_MASK: i64 = 0xff;

fn byte_to_encoding(byte: u8) -> Encoding {
    match byte {
        0 => Encoding::Utf8,
        1 => Encoding::LowerSpecial,
        2 => Encoding::LowerUpperDigitSpecial,
        3 => Encoding::FirstToLowerSpecial,
        4 => Encoding::AllToLowerSpecial,
        _ => unreachable!(),
    }
}

static EMPTY: Lazy<MetaStringBytes> =
    Lazy::new(|| MetaStringBytes::from_metastring(MetaString::get_empty()).unwrap());

impl MetaStringBytes {
    pub const DEFAULT_DYNAMIC_WRITE_STRING_ID: i16 = -1;

    pub fn new(bytes: Vec<u8>, hash_code: i64) -> Self {
        let header = (hash_code & HEADER_MASK) as u8;
        let encoding = byte_to_encoding(header);
        let mut data = bytes.clone();
        if bytes.len() < 16 {
            data.resize(16, 0);
        }
        let first8 = u64::from_le_bytes(data[0..8].try_into().unwrap());
        let second8 = u64::from_le_bytes(data[8..16].try_into().unwrap());
        MetaStringBytes {
            bytes,
            hash_code,
            encoding,
            first8,
            second8,
        }
    }

    pub fn to_metastring(&self) -> Result<MetaString, Error> {
        let ms = NAMESPACE_DECODER.decode(&self.bytes, self.encoding)?;
        Ok(ms)
    }

    pub(crate) fn from_metastring(meta_string: Arc<MetaString>) -> Result<Self, Error> {
        let bytes = meta_string.bytes.to_vec();
        let mut hash_code = murmurhash3_x64_128(&bytes, 47).0 as i64;
        hash_code = hash_code.abs();
        if hash_code == 0 {
            hash_code += 256;
        }
        hash_code = (hash_code as u64 & 0xffffffffffffff00) as i64;
        let encoding = meta_string.encoding;
        let header = encoding as i64 & HEADER_MASK;
        hash_code |= header;
        Ok(Self::new(bytes, hash_code))
    }
}

pub struct MetaStringWriterResolver {
    meta_string_to_bytes: HashMap<Arc<MetaString>, Rc<MetaStringBytes>>,
    dynamic_written: Vec<Option<Rc<MetaStringBytes>>>,
    dynamic_write_id: usize,
    bytes_id_map: HashMap<Rc<MetaStringBytes>, i16>,
}

impl Default for MetaStringWriterResolver {
    fn default() -> Self {
        Self {
            meta_string_to_bytes: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            dynamic_written: vec![None; 32],
            dynamic_write_id: 0,
            bytes_id_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
        }
    }
}

impl MetaStringWriterResolver {
    const INITIAL_CAPACITY: usize = 8;
    const SMALL_STRING_THRESHOLD: usize = 16;

    pub fn get_or_create_meta_string_bytes(
        &mut self,
        ms: Arc<MetaString>,
    ) -> Result<Rc<MetaStringBytes>, Error> {
        if let Some(b) = self.meta_string_to_bytes.get(&ms) {
            Ok(b.clone())
        } else {
            let mb = MetaStringBytes::from_metastring(ms.clone())?;
            let rc_mb = Rc::from(mb);
            self.meta_string_to_bytes.insert(ms.clone(), rc_mb.clone());
            Ok(rc_mb)
        }
    }

    pub fn write_meta_string_bytes_with_flag(
        &mut self,
        writer: &mut Writer,
        mb: Rc<MetaStringBytes>,
    ) {
        let id_option = self.bytes_id_map.get_mut(&mb);
        let id;
        if let Some(exist_id) = id_option {
            if *exist_id != MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
                let header = ((*exist_id as u32 + 1) << 2) | 0b11;
                writer.write_varuint32(header);
                return;
            }
            id = self.dynamic_write_id;
            *exist_id = id as i16;
        } else {
            id = self.dynamic_write_id;
            self.bytes_id_map.insert(mb.clone(), id as i16);
        }
        self.dynamic_write_id += 1;
        if id >= self.dynamic_written.len() {
            self.dynamic_written.resize(id * 2, None);
        }
        self.dynamic_written[id] = Some(mb.clone());

        let len = mb.bytes.len();
        let header = ((len as u32) << 2) | 0b1;
        writer.write_varuint32(header);
        if len > Self::SMALL_STRING_THRESHOLD {
            writer.write_i64(mb.hash_code);
        } else {
            writer.write_u8(mb.encoding as i16 as u8);
        }
        writer.write_bytes(&mb.bytes);
    }

    pub fn write_meta_string_bytes(&mut self, writer: &mut Writer, mb: Rc<MetaStringBytes>) {
        let id_option = self.bytes_id_map.get_mut(&mb);
        let id;
        if let Some(exist_id) = id_option {
            if *exist_id != MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
                let header = ((*exist_id as u32 + 1) << 1) | 1;
                writer.write_varuint32(header);
                return;
            }
            id = self.dynamic_write_id;
            *exist_id = id as i16;
        } else {
            id = self.dynamic_write_id;
            self.bytes_id_map.insert(mb.clone(), id as i16);
        }

        self.dynamic_write_id += 1;
        if id >= self.dynamic_written.len() {
            self.dynamic_written.resize(id * 2, None);
        }
        self.dynamic_written[id] = Some(mb.clone());

        let len = mb.bytes.len();
        writer.write_varuint32((len as u32) << 1);
        if len > Self::SMALL_STRING_THRESHOLD {
            writer.write_i64(mb.hash_code);
        } else {
            writer.write_u8(mb.encoding as i16 as u8);
        }
        writer.write_bytes(&mb.bytes);
    }

    pub fn reset(&mut self) {
        if self.dynamic_write_id != 0 {
            for i in 0..self.dynamic_write_id {
                let key = self.dynamic_written[i].as_ref().unwrap().clone();
                if let Some(v) = self.bytes_id_map.get_mut(&key) {
                    *v = MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID;
                }
                self.dynamic_written[i] = None;
            }
            self.dynamic_write_id = 0;
        }
    }
}

pub struct MetaStringReaderResolver {
    meta_string_bytes_to_string: HashMap<Rc<MetaStringBytes>, Arc<MetaString>>,
    hash_to_meta: HashMap<i64, Rc<MetaStringBytes>>,
    small_map: HashMap<(u64, u64, u8), Rc<MetaStringBytes>>,
    dynamic_read: Vec<Option<Rc<MetaStringBytes>>>,
    dynamic_read_id: usize,
}

impl Default for MetaStringReaderResolver {
    fn default() -> Self {
        Self {
            meta_string_bytes_to_string: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            hash_to_meta: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            small_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            dynamic_read: vec![None; 32],
            dynamic_read_id: 0,
        }
    }
}
impl MetaStringReaderResolver {
    const INITIAL_CAPACITY: usize = 8;
    const SMALL_STRING_THRESHOLD: usize = 16;

    pub fn read_meta_string_bytes_with_flag(
        &mut self,
        reader: &mut Reader,
        header: u32,
    ) -> Result<Rc<MetaStringBytes>, Error> {
        let len = (header >> 2) as usize;
        if (header & 0b10) == 0 {
            if len <= Self::SMALL_STRING_THRESHOLD {
                let rc_mb = self.read_small_meta_string_bytes(reader, len)?;
                self.update_dynamic_string(rc_mb.clone());
                Ok(rc_mb)
            } else {
                let hash_code = reader.read_i64()?;
                let mb = self.read_big_meta_string_bytes(reader, len, hash_code)?;
                self.update_dynamic_string(mb.clone());
                Ok(mb)
            }
        } else {
            let idx = len - 1;
            self.dynamic_read
                .get(idx)
                .and_then(|opt| opt.clone())
                .ok_or_else(|| Error::InvalidData("dynamic id not found".into()))
        }
    }

    pub fn read_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
    ) -> Result<Rc<MetaStringBytes>, Error> {
        let header = reader.read_varuint32()?;
        let len = (header >> 1) as usize;
        if (header & 0b1) == 0 {
            let mb = if len > Self::SMALL_STRING_THRESHOLD {
                let hash_code = reader.read_i64()?;
                self.read_big_meta_string_bytes(reader, len, hash_code)?
            } else {
                self.read_small_meta_string_bytes(reader, len)?
            };
            self.update_dynamic_string(mb.clone());
            Ok(mb)
        } else {
            let idx = len - 1;
            self.dynamic_read
                .get(idx)
                .and_then(|opt| opt.clone())
                .ok_or_else(|| Error::InvalidData("dynamic id not found".into()))
        }
    }

    fn read_big_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
        len: usize,
        hash_code: i64,
    ) -> Result<Rc<MetaStringBytes>, Error> {
        if let Some(existing) = self.hash_to_meta.get(&hash_code) {
            reader.skip(len)?;
            Ok(existing.clone())
        } else {
            let bytes = reader.read_bytes(len)?.to_vec();
            let mb = MetaStringBytes::new(bytes, hash_code);
            let rc_mb = Rc::from(mb);
            self.hash_to_meta.insert(hash_code, rc_mb.clone());
            Ok(rc_mb)
        }
    }

    fn read_small_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
        len: usize,
    ) -> Result<Rc<MetaStringBytes>, Error> {
        let encoding_val = reader.read_u8()?;
        if len == 0 {
            ensure!(
                encoding_val == Encoding::Utf8 as u8,
                Error::EncodingError(format!("wrong encoding value: {}", encoding_val).into())
            );
            let empty = EMPTY.clone();
            return Ok(Rc::new(empty));
        }
        let (v1, v2) = if len <= 8 {
            let v1 = Self::read_bytes_as_u64(reader, len)?;
            (v1, 0)
        } else {
            let v1 = reader.read_i64()? as u64;
            let v2 = Self::read_bytes_as_u64(reader, len - 8)?;
            (v1, v2)
        };
        let key = (v1, v2, encoding_val);
        if let Some(existing) = self.small_map.get(&key) {
            Ok(existing.clone())
        } else {
            let mut data = vec![0u8; 16];
            data[0..8].copy_from_slice(&v1.to_le_bytes());
            data[8..16].copy_from_slice(&v2.to_le_bytes());
            data.truncate(len);
            let hash_code = (murmurhash3_x64_128(&data, 47).0 as i64).abs();
            let hash_code =
                (hash_code as u64 & 0xffffffffffffff00_u64) as i64 | (encoding_val as i64);
            let mb = MetaStringBytes::new(data, hash_code);
            let rc_mb = Rc::from(mb);
            self.small_map.insert(key, rc_mb.clone());
            Ok(rc_mb)
        }
    }

    fn read_bytes_as_u64(reader: &mut Reader, len: usize) -> Result<u64, Error> {
        let mut v = 0;
        let slice = reader.read_bytes(len)?;
        for (i, b) in slice.iter().take(len).enumerate() {
            v |= (*b as u64) << (8 * i);
        }
        Ok(v)
    }

    fn update_dynamic_string(&mut self, mb: Rc<MetaStringBytes>) {
        let id = self.dynamic_read_id;
        self.dynamic_read_id += 1;
        if id >= self.dynamic_read.len() {
            self.dynamic_read.resize(id * 2, None);
        }
        self.dynamic_read[id] = Some(mb);
    }

    pub fn reset(&mut self) {
        if self.dynamic_read_id != 0 {
            for i in 0..self.dynamic_read_id {
                self.dynamic_read[i] = None;
            }
            self.dynamic_read_id = 0;
        }
    }

    pub fn read_meta_string(&mut self, reader: &mut Reader) -> Result<Arc<MetaString>, Error> {
        let mb = self.read_meta_string_bytes(reader)?;
        Ok(
            if let Some(ms) = self.meta_string_bytes_to_string.get(&mb) {
                ms.clone()
            } else {
                let ms = mb.to_metastring()?;
                let arc_ms = Arc::from(ms);
                self.meta_string_bytes_to_string.insert(mb, arc_ms.clone());
                arc_ms
            },
        )
    }
}

unsafe impl Send for MetaStringWriterResolver {}
unsafe impl Sync for MetaStringWriterResolver {}
unsafe impl Send for MetaStringReaderResolver {}
unsafe impl Sync for MetaStringReaderResolver {}
