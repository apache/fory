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

use crate::error::Error;
use crate::meta::buffer_rw_string::read_latin1_simd;
use byteorder::{ByteOrder, LittleEndian, WriteBytesExt};
use std::cmp::max;

/// Threshold for using SIMD optimizations in string operations.
/// For buffers smaller than this, direct copy is faster than SIMD setup overhead.
const SIMD_THRESHOLD: usize = 128;

pub struct Writer<'a> {
    pub(crate) bf: &'a mut Vec<u8>,
}
impl<'a> Writer<'a> {
    #[inline(always)]
    pub fn from_buffer(bf: &'a mut Vec<u8>) -> Writer<'a> {
        Writer { bf }
    }

    #[inline(always)]
    pub fn dump(&self) -> Vec<u8> {
        self.bf.clone()
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.bf.clear();
    }

    #[inline(always)]
    pub fn len(&self) -> usize {
        self.bf.len()
    }

    #[inline(always)]
    pub fn is_empty(&self) -> bool {
        self.bf.is_empty()
    }

    #[inline(always)]
    pub fn reserve(&mut self, additional: usize) {
        if self.bf.capacity() - self.len() < additional {
            self.bf.reserve(max(additional * 2, self.bf.capacity()));
        }
    }

    #[inline(always)]
    pub fn skip(&mut self, len: usize) {
        self.bf.resize(self.bf.len() + len, 0);
    }

    #[inline(always)]
    pub fn set_bytes(&mut self, offset: usize, data: &[u8]) {
        self.bf
            .get_mut(offset..offset + data.len())
            .unwrap()
            .copy_from_slice(data);
    }

    #[inline(always)]
    pub fn write_bytes(&mut self, v: &[u8]) -> usize {
        self.bf.extend_from_slice(v);
        v.len()
    }

    #[inline(always)]
    pub fn write_u8(&mut self, value: u8) {
        self.bf.write_u8(value).unwrap();
    }

    #[inline(always)]
    pub fn write_i8(&mut self, value: i8) {
        self.bf.write_i8(value).unwrap();
    }

    #[inline(always)]
    pub fn write_u16(&mut self, value: u16) {
        self.bf.write_u16::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_i16(&mut self, value: i16) {
        self.bf.write_i16::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_u32(&mut self, value: u32) {
        self.bf.write_u32::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_i32(&mut self, value: i32) {
        self.bf.write_i32::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_f32(&mut self, value: f32) {
        self.bf.write_f32::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_i64(&mut self, value: i64) {
        self.bf.write_i64::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_f64(&mut self, value: f64) {
        self.bf.write_f64::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_u64(&mut self, value: u64) {
        self.bf.write_u64::<LittleEndian>(value).unwrap();
    }

    #[inline(always)]
    pub fn write_varint32(&mut self, value: i32) {
        let zigzag = ((value as i64) << 1) ^ ((value as i64) >> 31);
        self._write_varuint32(zigzag as u32)
    }

    #[inline(always)]
    pub fn write_varuint32(&mut self, value: u32) {
        self._write_varuint32(value)
    }

    #[inline(always)]
    fn _write_varuint32(&mut self, value: u32) {
        if value < 0x80 {
            self.write_u8(value as u8);
        } else if value < 0x4000 {
            // 2 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (value >> 7) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16);
        } else if value < 0x200000 {
            // 3 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (value >> 14) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16);
            self.write_u8(u3);
        } else if value < 0x10000000 {
            // 4 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (value >> 21) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            );
        } else {
            // 5 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (value >> 28) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            );
            self.write_u8(u5);
        }
    }

    #[inline(always)]
    pub fn write_varint64(&mut self, value: i64) {
        let zigzag = ((value << 1) ^ (value >> 63)) as u64;
        self._write_varuint64(zigzag);
    }

    #[inline(always)]
    pub fn write_varuint64(&mut self, value: u64) {
        self._write_varuint64(value);
    }

    #[inline(always)]
    fn _write_varuint64(&mut self, value: u64) {
        if value < 0x80 {
            self.write_u8(value as u8);
        } else if value < 0x4000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (value >> 7) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16);
        } else if value < 0x200000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (value >> 14) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16);
            self.write_u8(u3);
        } else if value < 0x10000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (value >> 21) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            );
        } else if value < 0x800000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (value >> 28) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            );
            self.write_u8(u5);
        } else if value < 0x40000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (value >> 35) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            );
            self.write_u16(((u6 as u16) << 8) | u5 as u16);
        } else if value < 0x2000000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
            let u7 = (value >> 42) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            );
            self.write_u16(((u6 as u16) << 8) | u5 as u16);
            self.write_u8(u7);
        } else if value < 0x100000000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
            let u7 = (((value >> 42) as u8) & 0x7F) | 0x80;
            let u8 = (value >> 49) as u8;
            self.write_u64(
                (u8 as u64) << 56
                    | (u7 as u64) << 48
                    | (u6 as u64) << 40
                    | (u5 as u64) << 32
                    | (u4 as u64) << 24
                    | (u3 as u64) << 16
                    | (u2 as u64) << 8
                    | (u1 as u64),
            );
        } else {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
            let u7 = (((value >> 42) as u8) & 0x7F) | 0x80;
            let u8 = (((value >> 49) as u8) & 0x7F) | 0x80;
            let u9 = (value >> 56) as u8;
            self.write_u64(
                (u8 as u64) << 56
                    | (u7 as u64) << 48
                    | (u6 as u64) << 40
                    | (u5 as u64) << 32
                    | (u4 as u64) << 24
                    | (u3 as u64) << 16
                    | (u2 as u64) << 8
                    | (u1 as u64),
            );
            self.write_u8(u9);
        }
    }

    #[inline(always)]
    pub fn write_varuint36_small(&mut self, value: u64) {
        assert!(value < (1u64 << 36), "value too large for 36-bit varint");
        if value < 0x80 {
            self.write_u8(value as u8);
        } else if value < 0x4000 {
            let b0 = ((value & 0x7F) as u8) | 0x80;
            let b1 = (value >> 7) as u8;
            let combined = ((b1 as u16) << 8) | (b0 as u16);
            self.write_u16(combined);
        } else if value < 0x200000 {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = value >> 14;
            let combined = b0 | (b1 << 8) | (b2 << 16);
            self.write_u32(combined as u32);
        } else if value < 0x10000000 {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = ((value >> 14) & 0x7F) | 0x80;
            let b3 = value >> 21;
            let combined = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            self.write_u32(combined as u32);
        } else {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = ((value >> 14) & 0x7F) | 0x80;
            let b3 = ((value >> 21) & 0x7F) | 0x80;
            let b4 = value >> 28;
            let combined = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32);
            self.write_u64(combined);
        }
    }

    #[inline(always)]
    pub fn write_utf8_string(&mut self, s: &str) {
        let bytes = s.as_bytes();
        let len = bytes.len();
        self.bf.reserve(len);
        self.bf.extend_from_slice(bytes);
    }
}

#[derive(Default)]
#[allow(clippy::needless_lifetimes)]
pub struct Reader<'a> {
    pub(crate) bf: &'a [u8],
    pub(crate) cursor: usize,
}

#[allow(clippy::needless_lifetimes)]
impl<'a> Reader<'a> {
    #[inline(always)]
    pub fn new(bf: &[u8]) -> Reader<'_> {
        Reader { bf, cursor: 0 }
    }

    #[inline(always)]
    pub(crate) fn move_next(&mut self, additional: usize) {
        self.cursor += additional;
    }

    #[inline(always)]
    pub(crate) fn move_back(&mut self, additional: usize) {
        self.cursor -= additional;
    }

    #[inline(always)]
    pub fn sub_slice(&self, start: usize, end: usize) -> Result<&[u8], Error> {
        if start >= self.bf.len() || end > self.bf.len() || end < start {
            Err(Error::buffer_out_of_bound(
                start,
                self.bf.len(),
                self.bf.len(),
            ))
        } else {
            Ok(&self.bf[start..end])
        }
    }

    #[inline(always)]
    pub fn slice_after_cursor(&self) -> &[u8] {
        &self.bf[self.cursor..]
    }

    #[inline(always)]
    pub fn get_cursor(&self) -> usize {
        self.cursor
    }

    #[inline(always)]
    fn value_at(&self, index: usize) -> Result<u8, Error> {
        match self.bf.get(index) {
            None => Err(Error::buffer_out_of_bound(
                index,
                self.bf.len(),
                self.bf.len(),
            )),
            Some(v) => Ok(*v),
        }
    }

    #[inline(always)]
    fn check_bound(&self, n: usize) -> Result<(), Error> {
        // The upper layer guarantees it is non-null
        // if self.bf.is_null() {
        //     return Err(Error::invalid_data("buffer pointer is null"));
        // }
        if self.cursor + n > self.bf.len() {
            Err(Error::buffer_out_of_bound(self.cursor, n, self.bf.len()))
        } else {
            Ok(())
        }
    }

    #[inline(always)]
    pub fn read_bool(&mut self) -> Result<bool, Error> {
        Ok(self.read_u8()? != 0)
    }

    #[inline(always)]
    pub fn read_u8_uncheck(&mut self) -> u8 {
        let result = unsafe { self.bf.get_unchecked(self.cursor) };
        self.move_next(1);
        *result
    }

    #[inline(always)]
    pub fn read_u8(&mut self) -> Result<u8, Error> {
        let result = self.value_at(self.cursor)?;
        self.move_next(1);
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i8(&mut self) -> Result<i8, Error> {
        Ok(self.read_u8()? as i8)
    }

    #[inline(always)]
    pub fn read_u16(&mut self) -> Result<u16, Error> {
        let slice = self.slice_after_cursor();
        let result = LittleEndian::read_u16(slice);
        self.move_next(2);
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i16(&mut self) -> Result<i16, Error> {
        Ok(self.read_u16()? as i16)
    }

    #[inline(always)]
    pub fn read_u32(&mut self) -> Result<u32, Error> {
        let slice = self.slice_after_cursor();
        let result = LittleEndian::read_u32(slice);
        self.move_next(4);
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i32(&mut self) -> Result<i32, Error> {
        Ok(self.read_u32()? as i32)
    }

    #[inline(always)]
    pub fn read_u64(&mut self) -> Result<u64, Error> {
        let slice = self.slice_after_cursor();
        let result = LittleEndian::read_u64(slice);
        self.move_next(8);
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i64(&mut self) -> Result<i64, Error> {
        Ok(self.read_u64()? as i64)
    }

    #[inline(always)]
    pub fn read_f32(&mut self) -> Result<f32, Error> {
        let slice = self.slice_after_cursor();
        let result = LittleEndian::read_f32(slice);
        self.move_next(4);
        Ok(result)
    }

    #[inline(always)]
    pub fn read_f64(&mut self) -> Result<f64, Error> {
        let slice = self.slice_after_cursor();
        let result = LittleEndian::read_f64(slice);
        self.move_next(8);
        Ok(result)
    }

    #[inline(always)]
    pub fn read_varuint32(&mut self) -> Result<u32, Error> {
        let b0 = self.value_at(self.cursor)? as u32;
        if b0 < 0x80 {
            self.move_next(1);
            return Ok(b0);
        }

        let b1 = self.value_at(self.cursor + 1)? as u32;
        let mut encoded = (b0 & 0x7F) | ((b1 & 0x7F) << 7);
        if b1 < 0x80 {
            self.move_next(2);
            return Ok(encoded);
        }

        let b2 = self.value_at(self.cursor + 2)? as u32;
        encoded |= (b2 & 0x7F) << 14;
        if b2 < 0x80 {
            self.move_next(3);
            return Ok(encoded);
        }

        let b3 = self.value_at(self.cursor + 3)? as u32;
        encoded |= (b3 & 0x7F) << 21;
        if b3 < 0x80 {
            self.move_next(4);
            return Ok(encoded);
        }

        let b4 = self.value_at(self.cursor + 4)? as u32;
        encoded |= b4 << 28;
        self.move_next(5);
        Ok(encoded)
    }

    #[inline(always)]
    pub fn read_varint32(&mut self) -> Result<i32, Error> {
        let encoded = self.read_varuint32()?;
        Ok(((encoded >> 1) as i32) ^ -((encoded & 1) as i32))
    }

    #[inline(always)]
    pub fn read_varuint64(&mut self) -> Result<u64, Error> {
        let b0 = self.value_at(self.cursor)? as u64;
        if b0 < 0x80 {
            self.move_next(1);
            return Ok(b0);
        }

        let b1 = self.value_at(self.cursor + 1)? as u64;
        let mut var64 = (b0 & 0x7F) | ((b1 & 0x7F) << 7);
        if b1 < 0x80 {
            self.move_next(2);
            return Ok(var64);
        }

        let b2 = self.value_at(self.cursor + 2)? as u64;
        var64 |= (b2 & 0x7F) << 14;
        if b2 < 0x80 {
            self.move_next(3);
            return Ok(var64);
        }

        let b3 = self.value_at(self.cursor + 3)? as u64;
        var64 |= (b3 & 0x7F) << 21;
        if b3 < 0x80 {
            self.move_next(4);
            return Ok(var64);
        }

        let b4 = self.value_at(self.cursor + 4)? as u64;
        var64 |= (b4 & 0x7F) << 28;
        if b4 < 0x80 {
            self.move_next(5);
            return Ok(var64);
        }

        let b5 = self.value_at(self.cursor + 5)? as u64;
        var64 |= (b5 & 0x7F) << 35;
        if b5 < 0x80 {
            self.move_next(6);
            return Ok(var64);
        }

        let b6 = self.value_at(self.cursor + 6)? as u64;
        var64 |= (b6 & 0x7F) << 42;
        if b6 < 0x80 {
            self.move_next(7);
            return Ok(var64);
        }

        let b7 = self.value_at(self.cursor + 7)? as u64;
        var64 |= (b7 & 0x7F) << 49;
        if b7 < 0x80 {
            self.move_next(8);
            return Ok(var64);
        }

        let b8 = self.value_at(self.cursor + 8)? as u64;
        var64 |= (b8 & 0xFF) << 56;
        self.move_next(9);
        Ok(var64)
    }

    #[inline(always)]
    pub fn read_varint64(&mut self) -> Result<i64, Error> {
        let encoded = self.read_varuint64()?;
        Ok(((encoded >> 1) as i64) ^ -((encoded & 1) as i64))
    }

    #[inline(always)]
    pub fn read_latin1_string(&mut self, len: usize) -> Result<String, Error> {
        self.check_bound(len)?;
        if len < SIMD_THRESHOLD {
            // Fast path for small buffers
            unsafe {
                let src = self.sub_slice(self.cursor, self.cursor + len)?;

                // Check if all bytes are ASCII (< 0x80)
                let is_ascii = src.iter().all(|&b| b < 0x80);

                if is_ascii {
                    // ASCII fast path: Latin1 == UTF-8, direct copy
                    let mut vec = Vec::with_capacity(len);
                    let dst = vec.as_mut_ptr();
                    std::ptr::copy_nonoverlapping(src.as_ptr(), dst, len);
                    vec.set_len(len);
                    self.move_next(len);
                    Ok(String::from_utf8_unchecked(vec))
                } else {
                    // Contains Latin1 bytes (0x80-0xFF): must convert to UTF-8
                    let mut out: Vec<u8> = Vec::with_capacity(len * 2);
                    let out_ptr = out.as_mut_ptr();
                    let mut out_len = 0;

                    for &b in src {
                        if b < 0x80 {
                            *out_ptr.add(out_len) = b;
                            out_len += 1;
                        } else {
                            // Latin1 -> UTF-8 encoding
                            *out_ptr.add(out_len) = 0xC0 | (b >> 6);
                            *out_ptr.add(out_len + 1) = 0x80 | (b & 0x3F);
                            out_len += 2;
                        }
                    }

                    out.set_len(out_len);
                    self.move_next(len);
                    Ok(String::from_utf8_unchecked(out))
                }
            }
        } else {
            // Use SIMD for larger strings where the overhead is amortized
            read_latin1_simd(self, len)
        }
    }

    #[inline(always)]
    pub fn read_utf8_string(&mut self, len: usize) -> Result<String, Error> {
        self.check_bound(len)?;
        // don't use simd for memory copy, copy_non_overlapping is faster
        unsafe {
            let mut vec = Vec::with_capacity(len);
            let src = self.bf.as_ptr().add(self.cursor);
            let dst = vec.as_mut_ptr();
            // Use fastest possible copy - copy_nonoverlapping compiles to memcpy
            std::ptr::copy_nonoverlapping(src, dst, len);
            vec.set_len(len);
            self.move_next(len);
            // SAFETY: Assuming valid UTF-8 bytes (responsibility of serialization protocol)
            Ok(String::from_utf8_unchecked(vec))
        }
    }

    #[inline(always)]
    pub fn read_utf16_string(&mut self, len: usize) -> Result<String, Error> {
        self.check_bound(len)?;
        let slice = self.sub_slice(self.cursor, self.cursor + len)?;
        let units: Vec<u16> = slice
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .collect();
        self.move_next(len);
        Ok(String::from_utf16_lossy(&units))
    }

    #[inline(always)]
    pub fn read_varuint36small(&mut self) -> Result<u64, Error> {
        let start = self.cursor;
        let slice = self.slice_after_cursor();

        if slice.len() >= 8 {
            // here already check bound
            let bulk = self.read_u64()?;
            let mut result = bulk & 0x7F;
            let mut read_idx = start;

            if (bulk & 0x80) != 0 {
                read_idx += 1;
                result |= (bulk >> 1) & 0x3F80;
                if (bulk & 0x8000) != 0 {
                    read_idx += 1;
                    result |= (bulk >> 2) & 0x1FC000;
                    if (bulk & 0x800000) != 0 {
                        read_idx += 1;
                        result |= (bulk >> 3) & 0xFE00000;
                        if (bulk & 0x80000000) != 0 {
                            read_idx += 1;
                            result |= (bulk >> 4) & 0xFF0000000;
                        }
                    }
                }
            }
            self.cursor = read_idx + 1;
            return Ok(result);
        }

        let mut result = 0u64;
        let mut shift = 0;
        while self.cursor < self.bf.len() {
            let b = self.read_u8_uncheck();
            result |= ((b & 0x7F) as u64) << shift;
            if (b & 0x80) == 0 {
                break;
            }
            shift += 7;
            if shift >= 36 {
                return Err(Error::encode_error("varuint36small overflow"));
            }
        }
        Ok(result)
    }

    #[inline(always)]
    pub fn skip(&mut self, len: usize) -> Result<(), Error> {
        self.check_bound(len)?;
        self.move_next(len);
        Ok(())
    }

    #[inline(always)]
    pub fn read_bytes(&mut self, len: usize) -> Result<&[u8], Error> {
        self.check_bound(len)?;
        let result = &self.bf[self.cursor..self.cursor + len];
        self.move_next(len);
        Ok(result)
    }

    #[inline(always)]
    pub fn reset_cursor_to_here(&self) -> impl FnOnce(&mut Self) {
        let raw_cursor = self.cursor;
        move |this: &mut Self| {
            this.cursor = raw_cursor;
        }
    }

    pub fn set_cursor(&mut self, cursor: usize) {
        self.cursor = cursor;
    }
}

#[allow(clippy::needless_lifetimes)]
unsafe impl<'a> Send for Reader<'a> {}
#[allow(clippy::needless_lifetimes)]
unsafe impl<'a> Sync for Reader<'a> {}
