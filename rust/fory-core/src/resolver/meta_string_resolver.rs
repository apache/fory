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
use crate::meta::NAMESPACE_DECODER;
use crate::meta::{Encoding, MetaString};
use crate::util::murmurhash3_x64_128;
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

fn byte_to_encoding(byte: u8) -> Result<Encoding, Error> {
    match byte {
        0 => Ok(Encoding::Utf8),
        1 => Ok(Encoding::LowerSpecial),
        2 => Ok(Encoding::LowerUpperDigitSpecial),
        3 => Ok(Encoding::FirstToLowerSpecial),
        4 => Ok(Encoding::AllToLowerSpecial),
        _ => Err(Error::invalid_data(format!(
            "unknown encoding byte: {byte}"
        ))),
    }
}

fn compute_meta_string_hash(bytes: &[u8], encoding: Encoding) -> i64 {
    let mut hash_code = murmurhash3_x64_128(bytes, 47).0 as i64;
    // Java's Math.abs leaves MIN_VALUE unchanged; wrapping keeps the wire hash identical and
    // prevents a debug-build panic if MurmurHash produces that bit pattern.
    hash_code = hash_code.wrapping_abs();
    if hash_code == 0 {
        hash_code += 256;
    }
    hash_code = (hash_code as u64 & 0xffffffffffffff00) as i64;
    hash_code | (encoding as i64 & HEADER_MASK)
}

static EMPTY: OnceLock<MetaStringBytes> = OnceLock::new();

impl MetaStringBytes {
    pub const DEFAULT_DYNAMIC_WRITE_STRING_ID: i16 = -1;

    pub fn new(bytes: Vec<u8>, hash_code: i64) -> Result<Self, Error> {
        let header = (hash_code & HEADER_MASK) as u8;
        let encoding = byte_to_encoding(header)?;
        let mut data = bytes.clone();
        if bytes.len() < 16 {
            data.resize(16, 0);
        }
        let first8 = u64::from_le_bytes(data[0..8].try_into().unwrap());
        let second8 = u64::from_le_bytes(data[8..16].try_into().unwrap());
        Ok(MetaStringBytes {
            bytes,
            hash_code,
            encoding,
            first8,
            second8,
        })
    }

    pub fn to_meta_string(&self) -> Result<MetaString, Error> {
        let ms = NAMESPACE_DECODER.decode(&self.bytes, self.encoding)?;
        Ok(ms)
    }

    pub(crate) fn from_meta_string(meta_string: &MetaString) -> Result<Self, Error> {
        let bytes = meta_string.bytes.to_vec();
        let encoding = meta_string.encoding;
        let hash_code = compute_meta_string_hash(&bytes, encoding);
        Self::new(bytes, hash_code)
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
                writer.write_var_u32(((*exist_id as u32 + 1) << 1) | 1);
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
        writer.write_var_u32((len as u32) << 1);
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
    // `dynamic_read` stores raw pointers into these Box owners (or the static empty value).
    // Boxes keep pointees stable across map/vector growth, and reset invalidates every pointer
    // before dropping a root owner.
    hash_to_meta_string_bytes: HashMap<(i64, usize), Box<MetaStringBytes>>,
    long_long_byte_map: HashMap<(u64, u64, usize, u8), Box<MetaStringBytes>>,
    #[allow(clippy::vec_box)]
    root_meta_string_bytes: Vec<Box<MetaStringBytes>>,
    dynamic_read: Vec<Option<*const MetaStringBytes>>,
    dynamic_read_id: usize,
}

impl Default for MetaStringReaderResolver {
    fn default() -> Self {
        Self {
            meta_string_bytes_to_string: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            hash_to_meta_string_bytes: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            long_long_byte_map: HashMap::with_capacity(Self::INITIAL_CAPACITY),
            root_meta_string_bytes: Vec::new(),
            dynamic_read: vec![None; Self::INITIAL_DYNAMIC_READ_CAPACITY],
            dynamic_read_id: 0,
        }
    }
}

impl MetaStringReaderResolver {
    const INITIAL_CAPACITY: usize = 8;
    const INITIAL_DYNAMIC_READ_CAPACITY: usize = 32;
    const MAX_RETAINED_ROOT_CAPACITY: usize = 256;
    const SMALL_STRING_THRESHOLD: usize = 16;
    const MAX_CACHED_READ_META_STRINGS: usize = 8192;
    const MAX_CACHED_READ_META_STRING_LENGTH: usize = 2048;
    const MAX_DYNAMIC_READ_META_STRINGS: usize = 8192;

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
            if len == 0 {
                return Err(Error::invalid_data("dynamic string id cannot be zero"));
            }
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
        let header = reader.read_var_u32()?;
        let len = (header >> 1) as usize;

        if (header & 0b1) == 0 {
            if len > Self::SMALL_STRING_THRESHOLD {
                let hash_code = reader.read_i64()?;
                self.read_big_meta_string_bytes_and_update(reader, len, hash_code)
            } else {
                self.read_small_meta_string_bytes_and_update(reader, len)
            }
        } else {
            if len == 0 {
                return Err(Error::invalid_data("dynamic string id cannot be zero"));
            }
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
        self.check_dynamic_read_capacity()?;
        let key = (hash_code, len);
        if let Some(mb) = self.hash_to_meta_string_bytes.get(&key) {
            // The hash-length key identifies bytes validated on the cache miss. A hit can skip
            // the redundant body without hashing, allocation, or policy work.
            reader.skip(len)?;
            let ptr = mb.as_ref() as *const MetaStringBytes;
            self.update_dynamic_read(ptr);
            return Ok(unsafe { &*ptr });
        }

        let encoding = byte_to_encoding((hash_code & HEADER_MASK) as u8)?;
        let bytes = reader.read_bytes(len)?.to_vec();
        if compute_meta_string_hash(&bytes, encoding) != hash_code {
            return Err(Error::invalid_data("malformed meta string hash"));
        }
        let owner = Box::new(MetaStringBytes::new(bytes, hash_code)?);
        let ptr = owner.as_ref() as *const MetaStringBytes;
        if len <= Self::MAX_CACHED_READ_META_STRING_LENGTH
            && self.cached_meta_string_count() < Self::MAX_CACHED_READ_META_STRINGS
        {
            self.hash_to_meta_string_bytes.insert(key, owner);
        } else {
            self.root_meta_string_bytes.push(owner);
        }
        self.update_dynamic_read(ptr);
        Ok(unsafe { &*ptr })
    }

    fn read_small_meta_string_bytes_and_update(
        &mut self,
        reader: &mut Reader,
        len: usize,
    ) -> Result<&MetaStringBytes, Error> {
        self.check_dynamic_read_capacity()?;
        if len == 0 {
            let empty = MetaStringBytes::get_empty();
            self.update_dynamic_read(empty as *const MetaStringBytes);
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
        let key = (v1, v2, len, encoding_val);

        if let Some(mb) = self.long_long_byte_map.get(&key) {
            let ptr = mb.as_ref() as *const MetaStringBytes;
            self.update_dynamic_read(ptr);
            return Ok(unsafe { &*ptr });
        }

        let mut data = vec![0u8; 16];
        data[0..8].copy_from_slice(&v1.to_le_bytes());
        data[8..16].copy_from_slice(&v2.to_le_bytes());
        data.truncate(len);

        let encoding = byte_to_encoding(encoding_val)?;
        let hash_code = compute_meta_string_hash(&data, encoding);
        let owner = Box::new(MetaStringBytes::new(data, hash_code)?);
        let ptr = owner.as_ref() as *const MetaStringBytes;
        if self.cached_meta_string_count() < Self::MAX_CACHED_READ_META_STRINGS {
            self.long_long_byte_map.insert(key, owner);
        } else {
            self.root_meta_string_bytes.push(owner);
        }
        self.update_dynamic_read(ptr);
        Ok(unsafe { &*ptr })
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
    fn cached_meta_string_count(&self) -> usize {
        self.hash_to_meta_string_bytes.len() + self.long_long_byte_map.len()
    }

    #[inline(always)]
    fn check_dynamic_read_capacity(&self) -> Result<(), Error> {
        if self.dynamic_read_id >= Self::MAX_DYNAMIC_READ_META_STRINGS {
            return Err(too_many_meta_string_references());
        }
        Ok(())
    }

    #[inline(always)]
    fn update_dynamic_read(&mut self, ptr: *const MetaStringBytes) {
        let id = self.dynamic_read_id;
        if id == self.dynamic_read.len() {
            let next_len = (id * 2).min(Self::MAX_DYNAMIC_READ_META_STRINGS);
            self.dynamic_read.resize(next_len, None);
        }
        self.dynamic_read[id] = Some(ptr);
        self.dynamic_read_id = id + 1;
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        if self.dynamic_read_id != 0 || !self.root_meta_string_bytes.is_empty() {
            self.reset_root_state();
        }
    }

    #[cold]
    #[inline(never)]
    fn reset_root_state(&mut self) {
        // Invalidate every raw reference before removing derived pointer keys or dropping an owner.
        for ptr in self.dynamic_read.iter_mut().take(self.dynamic_read_id) {
            *ptr = None;
        }
        self.dynamic_read_id = 0;

        for owner in &self.root_meta_string_bytes {
            let ptr = owner.as_ref() as *const MetaStringBytes;
            self.meta_string_bytes_to_string.remove(&ptr);
        }
        self.root_meta_string_bytes.clear();

        if self.dynamic_read.len() > Self::MAX_RETAINED_ROOT_CAPACITY {
            self.dynamic_read = vec![None; Self::INITIAL_DYNAMIC_READ_CAPACITY];
        }
        if self.root_meta_string_bytes.capacity() > Self::MAX_RETAINED_ROOT_CAPACITY {
            self.root_meta_string_bytes = Vec::new();
        }
        let decoded_count = self.meta_string_bytes_to_string.len();
        let retained_decoded_capacity = decoded_count
            .saturating_mul(2)
            .max(Self::MAX_RETAINED_ROOT_CAPACITY);
        if self.meta_string_bytes_to_string.capacity() > retained_decoded_capacity {
            self.meta_string_bytes_to_string
                .shrink_to(decoded_count.max(Self::INITIAL_CAPACITY));
        }
    }

    #[inline(always)]
    pub fn read_meta_string(&mut self, reader: &mut Reader) -> Result<&MetaString, Error> {
        let ptr = {
            let mb_ref = self.read_meta_string_bytes(reader)?;
            mb_ref as *const MetaStringBytes
        };
        let ms_ref = match self.meta_string_bytes_to_string.entry(ptr) {
            Entry::Occupied(o) => o.into_mut(),
            Entry::Vacant(v) => {
                let mb_ref = unsafe { &*ptr };
                let ms = mb_ref.to_meta_string()?;
                v.insert(ms)
            }
        };

        Ok(ms_ref)
    }
}

#[cold]
#[inline(never)]
fn too_many_meta_string_references() -> Error {
    Error::invalid_data("too many meta string references in input")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn write_big(writer: &mut Writer<'_>, bytes: &[u8]) {
        let hash_code = compute_meta_string_hash(bytes, Encoding::Utf8);
        writer.write_var_u32((bytes.len() as u32) << 1);
        writer.write_i64(hash_code);
        writer.write_bytes(bytes);
    }

    fn write_small(writer: &mut Writer<'_>, bytes: &[u8]) {
        writer.write_var_u32((bytes.len() as u32) << 1);
        writer.write_u8(Encoding::Utf8 as u8);
        writer.write_bytes(bytes);
    }

    #[test]
    fn cache_and_reference_bounds() {
        let mut buffer = Vec::new();
        let mut writer = Writer::from_buffer(&mut buffer);
        for value in 0..=MetaStringReaderResolver::MAX_DYNAMIC_READ_META_STRINGS {
            write_small(&mut writer, &(value as u64).to_le_bytes());
        }
        let bytes = writer.dump();
        let mut reader = Reader::new(&bytes);
        let mut resolver = MetaStringReaderResolver::default();
        for _ in 0..MetaStringReaderResolver::MAX_DYNAMIC_READ_META_STRINGS {
            resolver.read_meta_string_bytes(&mut reader).unwrap();
        }

        let rejected_start = reader.get_cursor();
        let error = resolver
            .read_meta_string_bytes(&mut reader)
            .unwrap_err()
            .to_string();
        assert!(error.contains("too many meta string references"));
        assert_eq!(reader.get_cursor(), rejected_start + 1);
        assert_eq!(
            resolver.cached_meta_string_count(),
            MetaStringReaderResolver::MAX_CACHED_READ_META_STRINGS
        );
        assert_eq!(
            resolver.dynamic_read_id,
            MetaStringReaderResolver::MAX_DYNAMIC_READ_META_STRINGS
        );
        assert_eq!(
            resolver.dynamic_read.len(),
            MetaStringReaderResolver::MAX_DYNAMIC_READ_META_STRINGS
        );

        resolver.reset();
        assert_eq!(resolver.dynamic_read_id, 0);
        assert_eq!(
            resolver.dynamic_read.len(),
            MetaStringReaderResolver::INITIAL_DYNAMIC_READ_CAPACITY
        );
        assert!(resolver.dynamic_read.iter().all(Option::is_none));

        let mut reader = Reader::new(&bytes[rejected_start..]);
        resolver.read_meta_string_bytes(&mut reader).unwrap();
        assert_eq!(
            resolver.cached_meta_string_count(),
            MetaStringReaderResolver::MAX_CACHED_READ_META_STRINGS
        );
        assert_eq!(resolver.root_meta_string_bytes.len(), 1);
        resolver.reset();
        assert!(resolver.root_meta_string_bytes.is_empty());
    }

    #[test]
    fn root_owners_reset_safely() {
        let cached = vec![b'a'; MetaStringReaderResolver::MAX_CACHED_READ_META_STRING_LENGTH];
        let mut buffer = Vec::new();
        let mut writer = Writer::from_buffer(&mut buffer);
        write_big(&mut writer, &cached);
        let bytes = writer.dump();
        let mut reader = Reader::new(&bytes);
        let mut resolver = MetaStringReaderResolver::default();
        let cached_ptr = resolver.read_meta_string(&mut reader).unwrap() as *const MetaString;
        assert_eq!(resolver.hash_to_meta_string_bytes.len(), 1);
        assert_eq!(resolver.meta_string_bytes_to_string.len(), 1);
        resolver.reset();
        assert_eq!(resolver.hash_to_meta_string_bytes.len(), 1);
        assert_eq!(
            resolver
                .meta_string_bytes_to_string
                .values()
                .next()
                .unwrap() as *const MetaString,
            cached_ptr
        );

        let root_count = MetaStringReaderResolver::MAX_RETAINED_ROOT_CAPACITY + 1;
        let root_len = MetaStringReaderResolver::MAX_CACHED_READ_META_STRING_LENGTH + 1;
        let mut buffer = Vec::new();
        let mut writer = Writer::from_buffer(&mut buffer);
        for value in 0..root_count {
            let mut bytes = vec![b'a'; root_len];
            bytes[..8].copy_from_slice(format!("{value:08}").as_bytes());
            write_big(&mut writer, &bytes);
        }
        writer.write_var_u32(3);
        let bytes = writer.dump();
        let mut reader = Reader::new(&bytes);
        for _ in 0..root_count {
            resolver.read_meta_string(&mut reader).unwrap();
        }
        let root_ptr = resolver.root_meta_string_bytes[0].as_ref() as *const MetaStringBytes;
        let dynamic_ptr = resolver.read_meta_string_bytes(&mut reader).unwrap() as *const _;
        assert_eq!(dynamic_ptr, root_ptr);
        assert_eq!(resolver.hash_to_meta_string_bytes.len(), 1);
        assert_eq!(resolver.root_meta_string_bytes.len(), root_count);
        assert!(resolver.meta_string_bytes_to_string.contains_key(&root_ptr));
        assert!(resolver.dynamic_read.len() > MetaStringReaderResolver::MAX_RETAINED_ROOT_CAPACITY);

        resolver.reset();
        assert!(resolver.dynamic_read.iter().all(Option::is_none));
        assert_eq!(
            resolver.dynamic_read.len(),
            MetaStringReaderResolver::INITIAL_DYNAMIC_READ_CAPACITY
        );
        assert!(resolver.root_meta_string_bytes.is_empty());
        assert_eq!(resolver.root_meta_string_bytes.capacity(), 0);
        assert!(!resolver.meta_string_bytes_to_string.contains_key(&root_ptr));
        assert_eq!(resolver.meta_string_bytes_to_string.len(), 1);
        assert!(
            resolver.meta_string_bytes_to_string.capacity()
                <= MetaStringReaderResolver::MAX_RETAINED_ROOT_CAPACITY
        );
    }
}
