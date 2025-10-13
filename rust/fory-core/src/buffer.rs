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

use crate::ensure;
use crate::error::Error;
use crate::meta::buffer_rw_string::{
    read_latin1_simd, read_utf16_simd, read_utf8_simd, write_latin1_simd, write_utf16_simd,
    write_utf8_simd,
};
use byteorder::{ByteOrder, LittleEndian, WriteBytesExt};
use std::slice;

#[derive(Default)]
pub struct Writer {
    pub(crate) bf: Vec<u8>,
    reserved: usize,
}

impl Writer {
    #[inline(always)]
    pub fn reset(&mut self) {
        // keep capacity and reset len to 0
        self.bf.clear();
    }

    #[inline(always)]
    pub fn dump(&self) -> Vec<u8> {
        self.bf.clone()
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
        self.reserved += additional;
        if self.bf.capacity() < self.reserved {
            self.bf.reserve(self.reserved);
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
        self.reserve(v.len());
        self.bf.extend_from_slice(v);
        v.len()
    }

    #[inline(always)]
    pub fn write_u8(&mut self, value: u8) -> Result<(), Error> {
        Ok(self.bf.write_u8(value)?)
    }

    #[inline(always)]
    pub fn write_i8(&mut self, value: i8) -> Result<(), Error> {
        Ok(self.bf.write_i8(value)?)
    }

    #[inline(always)]
    pub fn write_u16(&mut self, value: u16) -> Result<(), Error> {
        Ok(self.bf.write_u16::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_i16(&mut self, value: i16) -> Result<(), Error> {
        Ok(self.bf.write_i16::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_u32(&mut self, value: u32) -> Result<(), Error> {
        Ok(self.bf.write_u32::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_i32(&mut self, value: i32) -> Result<(), Error> {
        Ok(self.bf.write_i32::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_f32(&mut self, value: f32) -> Result<(), Error> {
        Ok(self.bf.write_f32::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_i64(&mut self, value: i64) -> Result<(), Error> {
        Ok(self.bf.write_i64::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_f64(&mut self, value: f64) -> Result<(), Error> {
        Ok(self.bf.write_f64::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_u64(&mut self, value: u64) -> Result<(), Error> {
        Ok(self.bf.write_u64::<LittleEndian>(value)?)
    }

    #[inline(always)]
    pub fn write_varint32(&mut self, value: i32) -> Result<(), Error> {
        let zigzag = ((value as i64) << 1) ^ ((value as i64) >> 31);
        self._write_varuint32(zigzag as u32)
    }

    #[inline(always)]
    pub fn write_varuint32(&mut self, value: u32) -> Result<(), Error> {
        self._write_varuint32(value)
    }

    #[inline(always)]
    fn _write_varuint32(&mut self, value: u32) -> Result<(), Error> {
        if value < 0x80 {
            self.write_u8(value as u8)?;
        } else if value < 0x4000 {
            // 2 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (value >> 7) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16)?;
        } else if value < 0x200000 {
            // 3 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (value >> 14) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16)?;
            self.write_u8(u3)?;
        } else if value < 0x10000000 {
            // 4 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (value >> 21) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            )?;
        } else {
            // 5 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (value >> 28) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            )?;
            self.write_u8(u5)?;
        }
        Ok(())
    }

    #[inline(always)]
    pub fn write_varint64(&mut self, value: i64) -> Result<(), Error> {
        let zigzag = ((value << 1) ^ (value >> 63)) as u64;
        self._write_varuint64(zigzag)
    }

    #[inline(always)]
    pub fn write_varuint64(&mut self, value: u64) -> Result<(), Error> {
        self._write_varuint64(value)
    }

    #[inline(always)]
    fn _write_varuint64(&mut self, value: u64) -> Result<(), Error> {
        if value < 0x80 {
            self.write_u8(value as u8)?;
        } else if value < 0x4000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (value >> 7) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16)?;
        } else if value < 0x200000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (value >> 14) as u8;
            self.write_u16(((u2 as u16) << 8) | u1 as u16)?;
            self.write_u8(u3)?;
        } else if value < 0x10000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (value >> 21) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            )?;
        } else if value < 0x800000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (value >> 28) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            )?;
            self.write_u8(u5)?;
        } else if value < 0x40000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (value >> 35) as u8;
            self.write_u32(
                ((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32,
            )?;
            self.write_u16(((u6 as u16) << 8) | u5 as u16)?;
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
            )?;
            self.write_u16(((u6 as u16) << 8) | u5 as u16)?;
            self.write_u8(u7)?;
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
            )?;
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
            )?;
            self.write_u8(u9)?;
        }
        Ok(())
    }

    #[inline(always)]
    pub fn write_varuint36_small(&mut self, value: u64) -> Result<(), Error> {
        assert!(value < (1u64 << 36), "value too large for 36-bit varint");
        if value < 0x80 {
            self.write_u8(value as u8)?;
        } else if value < 0x4000 {
            let b0 = ((value & 0x7F) as u8) | 0x80;
            let b1 = (value >> 7) as u8;
            let combined = ((b1 as u16) << 8) | (b0 as u16);
            self.write_u16(combined)?;
        } else if value < 0x200000 {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = value >> 14;
            let combined = b0 | (b1 << 8) | (b2 << 16);
            self.write_u32(combined as u32)?;
        } else if value < 0x10000000 {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = ((value >> 14) & 0x7F) | 0x80;
            let b3 = value >> 21;
            let combined = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            self.write_u32(combined as u32)?;
        } else {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = ((value >> 14) & 0x7F) | 0x80;
            let b3 = ((value >> 21) & 0x7F) | 0x80;
            let b4 = value >> 28;
            let combined = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32);
            self.write_u64(combined)?;
        }
        Ok(())
    }

    #[inline(always)]
    pub fn write_latin1_string(&mut self, s: &str) {
        write_latin1_simd(self, s);
    }

    #[inline(always)]
    pub fn write_utf8_string(&mut self, s: &str) {
        write_utf8_simd(self, s);
    }

    #[inline(always)]
    pub fn write_utf16_bytes(&mut self, bytes: &[u16]) {
        write_utf16_simd(self, bytes);
    }
}

pub struct Reader {
    pub(crate) bf: *const u8,
    len: usize,
    pub(crate) cursor: usize,
}

impl Reader {
    #[inline(always)]
    pub fn new(bf: &[u8]) -> Reader {
        Reader {
            bf: bf.as_ptr(),
            len: bf.len(),
            cursor: 0,
        }
    }

    #[inline(always)]
    pub fn init(&mut self, bf: &[u8]) {
        self.bf = bf.as_ptr();
        self.len = bf.len();
        self.cursor = 0;
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.bf = std::ptr::null();
        self.len = 0;
        self.cursor = 0;
    }

    #[inline(always)]
    pub(crate) fn move_next(&mut self, additional: usize) -> Result<(), Error> {
        ensure!(
            self.cursor + additional <= self.len,
            "cursor out of bounds: {} + {} > {}",
            self.cursor,
            additional,
            self.len
        );
        self.cursor += additional;
        Ok(())
    }

    #[inline(always)]
    pub(crate) fn ptr_at(&self, offset: usize) -> Result<*const u8, Error> {
        ensure!(
            offset < self.len,
            "offset {} out of bounds for length {}",
            offset,
            self.len
        );
        Ok(unsafe { self.bf.add(offset) })
    }

    #[inline(always)]
    pub fn slice_after_cursor(&self) -> Result<&[u8], Error> {
        if self.bf.is_null() {
            return Err(Error::msg("buffer pointer is null"));
        }
        if self.cursor > self.len {
            return Err(Error::msg(format!(
                "cursor out of bounds: {} > {}",
                self.cursor, self.len
            )));
        }
        let remaining = self.len - self.cursor;
        let ptr = unsafe { self.bf.add(self.cursor) };
        Ok(unsafe { std::slice::from_raw_parts(ptr, remaining) })
    }

    #[inline(always)]
    pub fn get_cursor(&self) -> usize {
        self.cursor
    }

    #[inline(always)]
    pub fn read_bool(&mut self) -> Result<bool, Error> {
        let ptr = self.ptr_at(self.cursor)?;
        self.move_next(1)?;
        let result = unsafe { *ptr };
        Ok(result != 0)
    }

    #[inline(always)]
    pub fn read_u8(&mut self) -> Result<u8, Error> {
        let ptr = self.ptr_at(self.cursor)?;
        self.move_next(1)?;
        let result = unsafe { *ptr };
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i8(&mut self) -> Result<i8, Error> {
        Ok(self.read_u8()? as i8)
    }

    #[inline(always)]
    pub fn read_u16(&mut self) -> Result<u16, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_u16(slice);
        self.move_next(2)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i16(&mut self) -> Result<i16, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_i16(slice);
        self.move_next(2)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_u32(&mut self) -> Result<u32, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_u32(slice);
        self.move_next(4)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i32(&mut self) -> Result<i32, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_i32(slice);
        self.move_next(4)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_u64(&mut self) -> Result<u64, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_u64(slice);
        self.move_next(8)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_i64(&mut self) -> Result<i64, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_i64(slice);
        self.move_next(8)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_f32(&mut self) -> Result<f32, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_f32(slice);
        self.move_next(4)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_f64(&mut self) -> Result<f64, Error> {
        let slice = self.slice_after_cursor()?;
        let result = LittleEndian::read_f64(slice);
        self.move_next(8)?;
        Ok(result)
    }

    #[inline(always)]
    pub fn read_varuint32(&mut self) -> Result<u32, Error> {
        let slice = self.slice_after_cursor()?;

        let b0 = *slice
            .first()
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u32;
        if b0 < 0x80 {
            self.move_next(1)?;
            return Ok(b0);
        }

        let b1 = *slice
            .get(1)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u32;
        let mut encoded = (b0 & 0x7F) | ((b1 & 0x7F) << 7);
        if b1 < 0x80 {
            self.move_next(2)?;
            return Ok(encoded);
        }

        let b2 = *slice
            .get(2)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u32;
        encoded |= (b2 & 0x7F) << 14;
        if b2 < 0x80 {
            self.move_next(3)?;
            return Ok(encoded);
        }

        let b3 = *slice
            .get(3)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u32;
        encoded |= (b3 & 0x7F) << 21;
        if b3 < 0x80 {
            self.move_next(4)?;
            return Ok(encoded);
        }

        let b4 = *slice
            .get(4)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u32;
        encoded |= b4 << 28;
        self.move_next(5)?;
        Ok(encoded)
    }

    #[inline(always)]
    pub fn read_varint32(&mut self) -> Result<i32, Error> {
        let encoded = self.read_varuint32()?;
        Ok(((encoded >> 1) as i32) ^ -((encoded & 1) as i32))
    }

    #[inline(always)]
    pub fn read_varuint64(&mut self) -> Result<u64, Error> {
        let slice = self.slice_after_cursor()?;

        let b0 = *slice
            .first()
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        if b0 < 0x80 {
            self.move_next(1)?;
            return Ok(b0);
        }

        let b1 = *slice
            .get(1)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        let mut var64 = (b0 & 0x7F) | ((b1 & 0x7F) << 7);
        if b1 < 0x80 {
            self.move_next(2)?;
            return Ok(var64);
        }

        let b2 = *slice
            .get(2)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b2 & 0x7F) << 14;
        if b2 < 0x80 {
            self.move_next(3)?;
            return Ok(var64);
        }

        let b3 = *slice
            .get(3)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b3 & 0x7F) << 21;
        if b3 < 0x80 {
            self.move_next(4)?;
            return Ok(var64);
        }

        let b4 = *slice
            .get(4)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b4 & 0x7F) << 28;
        if b4 < 0x80 {
            self.move_next(5)?;
            return Ok(var64);
        }

        let b5 = *slice
            .get(5)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b5 & 0x7F) << 35;
        if b5 < 0x80 {
            self.move_next(6)?;
            return Ok(var64);
        }

        let b6 = *slice
            .get(6)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b6 & 0x7F) << 42;
        if b6 < 0x80 {
            self.move_next(7)?;
            return Ok(var64);
        }

        let b7 = *slice
            .get(7)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b7 & 0x7F) << 49;
        if b7 < 0x80 {
            self.move_next(8)?;
            return Ok(var64);
        }

        let b8 = *slice
            .get(8)
            .ok_or_else(|| Error::msg("unexpected end of buffer"))? as u64;
        var64 |= (b8 & 0xFF) << 56;
        self.move_next(9)?;
        Ok(var64)
    }

    #[inline(always)]
    pub fn read_varint64(&mut self) -> Result<i64, Error> {
        let encoded = self.read_varuint64()?;
        Ok(((encoded >> 1) as i64) ^ -((encoded & 1) as i64))
    }

    #[inline(always)]
    pub fn read_latin1_string(&mut self, len: usize) -> Result<String, Error> {
        read_latin1_simd(self, len)
    }

    #[inline(always)]
    pub fn read_utf8_string(&mut self, len: usize) -> Result<String, Error> {
        read_utf8_simd(self, len)
    }

    #[inline(always)]
    pub fn read_utf16_string(&mut self, len: usize) -> Result<String, Error> {
        read_utf16_simd(self, len)
    }

    #[inline(always)]
    pub fn read_varuint36small(&mut self) -> Result<u64, Error> {
        let start = self.cursor;
        let slice = self.slice_after_cursor()?;

        if slice.len() >= 8 {
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
        while self.cursor < self.len {
            let b = self.read_u8()?;
            result |= ((b & 0x7F) as u64) << shift;
            if (b & 0x80) == 0 {
                break;
            }
            shift += 7;
            if shift >= 36 {
                return Err(Error::msg("varuint36small overflow"));
            }
        }
        Ok(result)
    }

    #[inline(always)]
    pub fn skip(&mut self, len: u32) -> Result<(), Error> {
        self.move_next(len as usize)
    }

    #[inline(always)]
    pub fn get_slice(&self) -> Result<&[u8], Error> {
        if self.bf.is_null() || self.len == 0 {
            Ok(&[])
        } else {
            Ok(unsafe { slice::from_raw_parts(self.bf, self.len) })
        }
    }

    #[inline(always)]
    pub fn read_bytes(&mut self, len: usize) -> Result<&[u8], Error> {
        ensure!(
            self.cursor + len <= self.len,
            "read_bytes out of bounds: {} + {} > {}",
            self.cursor,
            len,
            self.len
        );
        let s = unsafe { slice::from_raw_parts(self.bf.add(self.cursor), len) };
        self.move_next(len)?;
        Ok(s)
    }

    #[inline(always)]
    pub fn reset_cursor_to_here(&self) -> impl FnOnce(&mut Self) {
        let raw_cursor = self.cursor;
        move |this: &mut Self| {
            this.cursor = raw_cursor;
        }
    }

    #[inline(always)]
    pub fn aligned<T>(&self) -> Result<bool, Error> {
        ensure!(!self.bf.is_null(), "buffer pointer is null");
        Ok(unsafe { (self.bf.add(self.cursor) as usize) % align_of::<T>() == 0 })
    }
}

unsafe impl Send for Reader {}
unsafe impl Sync for Reader {}
