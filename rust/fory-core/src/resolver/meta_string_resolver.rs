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

use std::collections::hash_map::Entry;
use std::collections::HashMap;
use std::convert::TryInto;
use std::rc::Rc;
use std::sync::OnceLock;

use crate::buffer::Writer;
use crate::error::Error;
use crate::meta::{murmurhash3_x64_128, NAMESPACE_DECODER};
use crate::meta::{Encoding, MetaString};
use crate::Reader;

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
        0 => Encoding::Extended,
        1 => Encoding::LowerSpecial,
        2 => Encoding::LowerUpperDigitSpecial,
        3 => Encoding::FirstToLowerSpecial,
        4 => Encoding::AllToLowerSpecial,
        _ => unreachable!(),
    }
}

static EMPTY: OnceLock<MetaStringBytes> = OnceLock::new();

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

    pub fn to_meta_string(&self) -> Result<MetaString, Error> {
        let ms = NAMESPACE_DECODER.decode(&self.bytes, self.encoding)?;
        Ok(ms)
    }

    pub(crate) fn from_meta_string(meta_string: &MetaString) -> Result<Self, Error> {
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

    pub fn get_empty() -> &'static MetaStringBytes {
        EMPTY.get_or_init(|| MetaStringBytes::from_meta_string(MetaString::get_empty()).unwrap())
    }
}

pub struct MetaStringWriterResolver {
    meta_string_to_bytes: HashMap<Rc<MetaString>, MetaStringBytes>,
    dynamic_written: Vec<*const MetaStringBytes>,
    dynamic_write_id: usize,
    bytes_id_map: HashMap<*const MetaStringBytes, i16>,
}

impl Default for MetaStringWriterResolver {
    fn default() -> Self {
        Self {
            meta_string_to_bytes: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            dynamic_written: vec![std::ptr::null(); 32],
            dynamic_write_id: 0,
            bytes_id_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
        }
    }
}

impl MetaStringWriterResolver {
    const INITIAL_CAPACITY: usize = 8;
    const SMALL_STRING_THRESHOLD: usize = 16;

    pub fn write_meta_string_bytes(
        &mut self,
        writer: &mut Writer,
        ms: Rc<MetaString>,
    ) -> Result<(), Error> {
        // get_or_create_meta_string_bytes
        let mb_ref = {
            let entry = self.meta_string_to_bytes.entry(ms.clone());
            match entry {
                Entry::Occupied(o) => o.into_mut(),
                Entry::Vacant(v) => v.insert(MetaStringBytes::from_meta_string(&ms)?),
            }
        };

        let mb_ptr: *const MetaStringBytes = mb_ref as *const _;
        let id = if let Some(exist_id) = self.bytes_id_map.get_mut(&mb_ptr) {
            if *exist_id != MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID {
                writer.write_var_uint32(((*exist_id as u32 + 1) << 1) | 1);
                return Ok(());
            }
            let id = self.dynamic_write_id;
            *exist_id = id as i16;
            id
        } else {
            let id = self.dynamic_write_id;
            self.bytes_id_map.insert(mb_ptr, id as i16);
            id
        };
        // // update dynamic_write
        self.dynamic_write_id += 1;
        if id >= self.dynamic_written.len() {
            self.dynamic_written.resize(id * 2, std::ptr::null());
        }
        self.dynamic_written[id] = mb_ptr;

        let len = mb_ref.bytes.len();
        writer.write_var_uint32((len as u32) << 1);
        if len > Self::SMALL_STRING_THRESHOLD {
            writer.write_i64(mb_ref.hash_code);
        } else if len != 0 {
            writer.write_u8(mb_ref.encoding as i16 as u8);
        }
        writer.write_bytes(&mb_ref.bytes);
        Ok(())
    }

    pub fn reset(&mut self) {
        if self.dynamic_write_id != 0 {
            for i in 0..self.dynamic_write_id {
                let key = self.dynamic_written[i];
                if !key.is_null() {
                    if let Some(v) = self.bytes_id_map.get_mut(&key) {
                        *v = MetaStringBytes::DEFAULT_DYNAMIC_WRITE_STRING_ID;
                    }
                    self.dynamic_written[i] = std::ptr::null();
                }
            }
            self.dynamic_write_id = 0;
        }
    }
}

pub struct MetaStringReaderResolver {
    meta_string_bytes_to_string: HashMap<*const MetaStringBytes, MetaString>,
    hash_to_meta_string_bytes: HashMap<i64, MetaStringBytes>,
    long_long_byte_map: HashMap<(u64, u64, u8), MetaStringBytes>,
    dynamic_read: Vec<Option<*const MetaStringBytes>>,
    dynamic_read_id: usize,
}

impl Default for MetaStringReaderResolver {
    fn default() -> Self {
        Self {
            meta_string_bytes_to_string: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            hash_to_meta_string_bytes: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            long_long_byte_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
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
    ) -> Result<&MetaStringBytes, Error> {
        let len = (header >> 2) as usize;

        if (header & 0b10) == 0 {
            if len <= Self::SMALL_STRING_THRESHOLD {
                self.read_small_meta_string_bytes_and_update(reader, len)
            } else {
                let hash_code = reader.read_i64()?;
                self.read_big_meta_string_bytes_and_update(reader, len, hash_code)
            }
        } else {
            let idx = len - 1;
            self.dynamic_read
                .get(idx)
                .and_then(|opt| opt.as_ref())
                .map(|ptr| unsafe { &**ptr })
                .ok_or_else(|| Error::invalid_data("dynamic id not found"))
        }
    }

    pub fn read_meta_string_bytes(
        &mut self,
        reader: &mut Reader,
    ) -> Result<&MetaStringBytes, Error> {
        let header = reader.read_varuint32()?;
        let len = (header >> 1) as usize;

        if (header & 0b1) == 0 {
            if len > Self::SMALL_STRING_THRESHOLD {
                let hash_code = reader.read_i64()?;
                self.read_big_meta_string_bytes_and_update(reader, len, hash_code)
            } else {
                self.read_small_meta_string_bytes_and_update(reader, len)
            }
        } else {
            let idx = len - 1;
            self.dynamic_read
                .get(idx)
                .and_then(|opt| opt.as_ref())
                .map(|ptr| unsafe { &**ptr })
                .ok_or_else(|| Error::invalid_data("dynamic id not found"))
        }
    }

    fn read_big_meta_string_bytes_and_update(
        &mut self,
        reader: &mut Reader,
        len: usize,
        hash_code: i64,
    ) -> Result<&MetaStringBytes, Error> {
        let mb_ref: &mut MetaStringBytes = match self.hash_to_meta_string_bytes.entry(hash_code) {
            Entry::Occupied(entry) => {
                reader.skip(len)?;
                entry.into_mut()
            }
            Entry::Vacant(entry) => {
                let bytes = reader.read_bytes(len)?.to_vec();
                let mb = MetaStringBytes::new(bytes, hash_code);
                entry.insert(mb)
            }
        };

        // update dynamic_read
        let id = self.dynamic_read_id;
        self.dynamic_read_id += 1;
        if id >= self.dynamic_read.len() {
            self.dynamic_read.resize(id * 2 + 1, None);
        }
        let ptr = mb_ref as *const MetaStringBytes;
        self.dynamic_read[id] = Some(ptr);
        Ok(mb_ref)
    }

    fn read_small_meta_string_bytes_and_update(
        &mut self,
        reader: &mut Reader,
        len: usize,
    ) -> Result<&MetaStringBytes, Error> {
        if len == 0 {
            let empty = MetaStringBytes::get_empty();
            // empty must be a static or globally unique instance
            return Ok(empty);
        }
        let encoding_val = reader.read_u8()?;

        let (v1, v2) = if len <= 8 {
            let v1 = Self::read_bytes_as_u64(reader, len)?;
            (v1, 0)
        } else {
            let v1 = reader.read_i64()? as u64;
            let v2 = Self::read_bytes_as_u64(reader, len - 8)?;
            (v1, v2)
        };
        let key = (v1, v2, encoding_val);

        let mb_ref = match self.long_long_byte_map.entry(key) {
            Entry::Occupied(entry) => entry.into_mut(),
            Entry::Vacant(entry) => {
                let mut data = vec![0u8; 16];
                data[0..8].copy_from_slice(&v1.to_le_bytes());
                data[8..16].copy_from_slice(&v2.to_le_bytes());
                data.truncate(len);

                let hash_code = (murmurhash3_x64_128(&data, 47).0 as i64).abs();
                let hash_code =
                    (hash_code as u64 & 0xffffffffffffff00_u64) as i64 | (encoding_val as i64);
                let mb = MetaStringBytes::new(data, hash_code);
                entry.insert(mb)
            }
        };
        // update dynamic_read
        let ptr = mb_ref as *const MetaStringBytes;
        let id = self.dynamic_read_id;
        self.dynamic_read_id += 1;
        if id >= self.dynamic_read.len() {
            self.dynamic_read.resize(id * 2, None);
        }
        self.dynamic_read[id] = Some(ptr);
        Ok(mb_ref)
    }

    #[inline(always)]
    fn read_bytes_as_u64(reader: &mut Reader, len: usize) -> Result<u64, Error> {
        let mut v = 0;
        let slice = reader.read_bytes(len)?;
        for (i, b) in slice.iter().take(len).enumerate() {
            v |= (*b as u64) << (8 * i);
        }
        Ok(v)
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        if self.dynamic_read_id != 0 {
            for i in 0..self.dynamic_read_id {
                self.dynamic_read[i] = None;
            }
            self.dynamic_read_id = 0;
        }
    }

    #[inline(always)]
    pub fn read_meta_string(&mut self, reader: &mut Reader) -> Result<&MetaString, Error> {
        let ptr = {
            let mb_ref = self.read_meta_string_bytes(reader)?;
            mb_ref as *const MetaStringBytes
        };
        let ms_ref = self
            .meta_string_bytes_to_string
            .entry(ptr)
            .or_insert_with(|| {
                let mb_ref = unsafe { &*ptr };
                mb_ref.to_meta_string().unwrap()
            });

        Ok(ms_ref)
    }
}
