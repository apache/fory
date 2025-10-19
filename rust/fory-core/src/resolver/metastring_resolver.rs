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

use std::collections::HashMap;
use std::convert::TryInto;

use crate::buffer::Writer;
use crate::error::Error;
use crate::meta::murmurhash3_x64_128;
use crate::meta::{Encoding, MetaString};
use crate::Reader;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct MetaStringBytes {
    pub bytes: Vec<u8>,
    pub hash_code: i64,
    pub encoding: Encoding,
    pub first8: u64,
    pub second8: u64,
    pub dynamic_write_id: i16,
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

impl MetaStringBytes {
    pub const DEFAULT_DYNAMIC_WRITE_STRING_ID: i16 = -1;
    pub const EMPTY: MetaStringBytes = MetaStringBytes {
        bytes: Vec::new(),
        hash_code: 0,
        encoding: Encoding::Utf8,
        first8: 0,
        second8: 0,
        dynamic_write_id: MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID,
    };

    pub fn new(
        bytes: Vec<u8>,
        hash_code: i64,
        encoding: Encoding,
        first8: u64,
        second8: u64,
    ) -> Self {
        MetaStringBytes {
            bytes,
            hash_code,
            encoding,
            first8,
            second8,
            dynamic_write_id: MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID,
        }
    }

    pub fn decode_lossy(&self) -> String {
        String::from_utf8_lossy(&self.bytes).into_owned()
    }

    pub(crate) fn from_metastring(meta_string: &MetaString) -> Result<Self, Error> {
        let mut bytes = meta_string.bytes.to_vec();
        let mut hash_code = murmurhash3_x64_128(&bytes, 47).0 as i64;
        hash_code = hash_code.abs();
        if hash_code == 0 {
            hash_code += 256;
        }
        hash_code = (hash_code as u64 & 0xffffffffffffff00) as i64;
        let encoding = meta_string.encoding;
        let header = encoding as i64 & HEADER_MASK;
        hash_code |= header;
        let header = (hash_code & HEADER_MASK) as u8;
        let encoding = byte_to_encoding(header);

        if bytes.len() < 16 {
            bytes.resize(16, 0);
        }

        let first8: [u8; 8] = bytes[0..8].try_into().map_err(|_| {
            Error::invalid_data(format!("expected at least 8 bytes, got {}", bytes.len()))
        })?;
        let first8 = u64::from_le_bytes(first8);

        let second8: [u8; 8] = bytes[8..16].try_into().map_err(|_| {
            Error::invalid_data(format!("expected at least 16 bytes, got {}", bytes.len()))
        })?;
        let second8 = u64::from_le_bytes(second8);

        Ok(Self::new(bytes, hash_code, encoding, first8, second8))
    }
}

#[derive(Default)]
pub struct MetaStringWriterResolver {
    meta_string_to_bytes: HashMap<MetaString, MetaStringBytes>,
    dynamic_written: Vec<Option<MetaStringBytes>>,
    dynamic_write_id: usize,
}

impl MetaStringWriterResolver {
    const INITIAL_CAPACITY: usize = 8;
    const SMALL_STRING_THRESHOLD: usize = 16;

    pub fn new() -> Self {
        Self {
            meta_string_to_bytes: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            dynamic_written: vec![None; 32],
            dynamic_write_id: 0,
        }
    }

    pub fn get_or_create_meta_string_bytes(&mut self, m: &MetaString) -> MetaStringBytes {
        if let Some(b) = self.meta_string_to_bytes.get(m) {
            return b.clone();
        }
        let bytes = m.bytes.to_vec();
        let hash_code = murmurhash3_x64_128(&bytes, 47).0 as i64;
        let encoding = m.encoding;
        let mut first8: u64 = 0;
        let mut second8: u64 = 0;
        for (i, b) in bytes.iter().take(8).enumerate() {
            first8 |= (*b as u64) << (8 * i);
        }
        if bytes.len() > 8 {
            for j in 0..usize::min(8, bytes.len() - 8) {
                second8 |= (bytes[8 + j] as u64) << (8 * j);
            }
        }
        let msb = MetaStringBytes::new(bytes, hash_code, encoding, first8, second8);
        self.meta_string_to_bytes.insert(m.clone(), msb.clone());
        msb
    }

    pub fn write_meta_string_bytes_with_flag(&mut self, w: &mut Writer, mut mb: MetaStringBytes) {
        let id = mb.dynamic_write_id;
        if id == MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
            let id_usize = self.dynamic_write_id;
            self.dynamic_write_id += 1;
            mb.dynamic_write_id = id_usize as i16;
            if id_usize >= self.dynamic_written.len() {
                self.dynamic_written.resize(id_usize * 2, None);
            }
            self.dynamic_written[id_usize] = Some(mb.clone());

            let len = mb.bytes.len();
            let header = ((len as u32) << 2) | 0b1;
            w.write_varuint32(header);
            if len > Self::SMALL_STRING_THRESHOLD {
                w.write_i64(mb.hash_code);
            } else {
                w.write_u8(mb.encoding as i16 as u8);
            }
            w.write_bytes(&mb.bytes);
        } else {
            let header = ((id as u32 + 1) << 2) | 0b11;
            w.write_varuint32(header);
        }
    }

    pub fn write_meta_string_bytes(
        &mut self,
        writer: &mut Writer,
        ms: &MetaString,
    ) -> Result<(), Error> {
        let mut mb = MetaStringBytes::from_metastring(ms)?;
        let id = mb.dynamic_write_id;
        if id == MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
            let id_usize = self.dynamic_write_id;
            self.dynamic_write_id += 1;
            mb.dynamic_write_id = id_usize as i16;
            if id_usize >= self.dynamic_written.len() {
                self.dynamic_written.resize(id_usize * 2 + 1, None);
            }
            self.dynamic_written[id_usize] = Some(mb.clone());

            let len = mb.bytes.len();
            writer.write_varuint32((len as u32) << 1);
            if len > Self::SMALL_STRING_THRESHOLD {
                writer.write_i64(mb.hash_code);
            } else {
                writer.write_u8(mb.encoding as i16 as u8);
            }
            writer.write_bytes(&mb.bytes);
        } else {
            let header = ((id as u32 + 1) << 1) | 1;
            writer.write_varuint32(header);
        }
        Ok(())
    }

    pub fn reset_write(&mut self) {
        if self.dynamic_write_id != 0 {
            for i in 0..self.dynamic_write_id {
                if let Some(ref mut mb) = self.dynamic_written[i] {
                    mb.dynamic_write_id = MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID;
                }
                self.dynamic_written[i] = None;
            }
            self.dynamic_write_id = 0;
        }
    }
}

#[derive(Default)]
pub struct MetaStringReaderResolver {
    meta_string_bytes_to_string: HashMap<MetaStringBytes, String>,
    hash_to_meta: HashMap<i64, MetaStringBytes>,
    small_map: HashMap<(u64, u64, u8), MetaStringBytes>,
    dynamic_read: Vec<Option<MetaStringBytes>>,
    dynamic_read_id: usize,
}

impl MetaStringReaderResolver {
    const INITIAL_CAPACITY: usize = 8;
    const SMALL_STRING_THRESHOLD: usize = 16;

    pub fn new() -> Self {
        Self {
            meta_string_bytes_to_string: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            hash_to_meta: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            small_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            dynamic_read: vec![None; 32],
            dynamic_read_id: 0,
        }
    }

    pub fn read_meta_string_bytes_with_flag(
        &mut self,
        reader: &mut Reader,
        header: u32,
    ) -> Result<MetaStringBytes, Error> {
        let len = (header >> 2) as usize;
        if (header & 0b10) == 0 {
            if len <= Self::SMALL_STRING_THRESHOLD {
                let mb = self.read_small_meta_string_bytes(reader, len)?;
                self.update_dynamic_string(mb.clone());
                Ok(mb)
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
                .ok_or_else(|| Error::invalid_data("dynamic id not found"))
        }
    }

    pub fn read_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
    ) -> Result<MetaStringBytes, Error> {
        let header = reader.read_varuint32()?;
        let len = (header >> 1) as usize;
        if (header & 0b1) == 0 {
            if len > Self::SMALL_STRING_THRESHOLD {
                let hash_code = reader.read_i64()?;
                let mb = self.read_big_meta_string_bytes(reader, len, hash_code)?;
                self.update_dynamic_string(mb.clone());
                Ok(mb)
            } else {
                let mb = self.read_small_meta_string_bytes(reader, len)?;
                self.update_dynamic_string(mb.clone());
                Ok(mb)
            }
        } else {
            let idx = len - 1;
            self.dynamic_read
                .get(idx)
                .and_then(|opt| opt.clone())
                .ok_or_else(|| Error::invalid_data("dynamic id not found"))
        }
    }

    fn read_big_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
        len: usize,
        hash_code: i64,
    ) -> Result<MetaStringBytes, Error> {
        if let Some(existing) = self.hash_to_meta.get(&hash_code) {
            reader.skip(len)?;
            Ok(existing.clone())
        } else {
            let bytes = reader.read_bytes(len)?.to_vec();
            let mut first8 = 0;
            let mut second8 = 0;
            for (i, b) in bytes.iter().enumerate().take(8) {
                first8 |= (*b as u64) << (8 * i);
            }
            if bytes.len() > 8 {
                for j in 0..usize::min(8, bytes.len() - 8) {
                    second8 |= (bytes[8 + j] as u64) << (8 * j);
                }
            }
            let mb = MetaStringBytes::new(bytes, hash_code, Encoding::Utf8, first8, second8);
            self.hash_to_meta.insert(hash_code, mb.clone());
            Ok(mb)
        }
    }

    fn read_small_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
        len: usize,
    ) -> Result<MetaStringBytes, Error> {
        let encoding_val = reader.read_u8()?;
        if len == 0 {
            debug_assert_eq!(encoding_val, Encoding::Utf8 as i16 as u8);
            return Ok(MetaStringBytes::EMPTY.clone());
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
            let mut data = Vec::with_capacity(len);
            for i in 0..usize::min(8, len) {
                data.push(((v1 >> (8 * i)) & 0xFF) as u8);
            }
            if len > 8 {
                for j in 0..(len - 8) {
                    data.push(((v2 >> (8 * j)) & 0xFF) as u8);
                }
            }
            data.truncate(len);
            let hash_code = (murmurhash3_x64_128(&data, 47).0 as i64).abs();
            let hash_code =
                (hash_code as u64 & 0xffffffffffffff00_u64) as i64 | (encoding_val as i64);
            let mb = MetaStringBytes::new(
                data.clone(),
                hash_code,
                byte_to_encoding(encoding_val),
                v1,
                v2,
            );
            self.small_map.insert(key, mb.clone());
            Ok(mb)
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

    fn update_dynamic_string(&mut self, mb: MetaStringBytes) {
        let id = self.dynamic_read_id;
        self.dynamic_read_id += 1;
        if id >= self.dynamic_read.len() {
            self.dynamic_read.resize(id * 2 + 1, None);
        }
        self.dynamic_read[id] = Some(mb);
    }

    pub fn reset_read(&mut self) {
        if self.dynamic_read_id != 0 {
            for i in 0..self.dynamic_read_id {
                self.dynamic_read[i] = None;
            }
            self.dynamic_read_id = 0;
        }
    }

    pub fn read_meta_string(&mut self, reader: &mut Reader) -> Result<String, Error> {
        let mb = self.read_meta_string_bytes(reader)?;
        Ok(if let Some(s) = self.meta_string_bytes_to_string.get(&mb) {
            s.clone()
        } else {
            let s = mb.decode_lossy();
            self.meta_string_bytes_to_string.insert(mb, s.clone());
            s
        })
    }
}
