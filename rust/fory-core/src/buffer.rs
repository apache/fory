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

use byteorder::{ByteOrder, LittleEndian, WriteBytesExt};

#[derive(Default)]
pub struct Writer {
    bf: Vec<u8>,
    reserved: usize,
}

impl Writer {
    pub fn dump(&self) -> Vec<u8> {
        self.bf.clone()
    }

    pub fn len(&self) -> usize {
        self.bf.len()
    }

    pub fn is_empty(&self) -> bool {
        self.bf.is_empty()
    }

    pub fn reserve(&mut self, additional: usize) {
        self.reserved += additional;
        if self.bf.capacity() < self.reserved {
            self.bf.reserve(self.reserved);
        }
    }

    pub fn u8(&mut self, value: u8) {
        self.bf.write_u8(value).unwrap();
    }

    pub fn i8(&mut self, value: i8) {
        self.bf.write_i8(value).unwrap();
    }

    pub fn u16(&mut self, value: u16) {
        self.bf.write_u16::<LittleEndian>(value).unwrap();
    }

    pub fn i16(&mut self, value: i16) {
        self.bf.write_i16::<LittleEndian>(value).unwrap();
    }

    pub fn u32(&mut self, value: u32) {
        self.bf.write_u32::<LittleEndian>(value).unwrap();
    }

    pub fn skip(&mut self, len: usize) {
        self.bf.resize(self.bf.len() + len, 0);
    }

    pub fn i32(&mut self, value: i32) {
        self.bf.write_i32::<LittleEndian>(value).unwrap();
    }

    pub fn f32(&mut self, value: f32) {
        self.bf.write_f32::<LittleEndian>(value).unwrap();
    }

    pub fn i64(&mut self, value: i64) {
        self.bf.write_i64::<LittleEndian>(value).unwrap();
    }

    pub fn f64(&mut self, value: f64) {
        self.bf.write_f64::<LittleEndian>(value).unwrap();
    }

    pub fn u64(&mut self, value: u64) {
        self.bf.write_u64::<LittleEndian>(value).unwrap();
    }

    pub fn var_int32(&mut self, value: i32) {
        let zigzag = ((value as i64) << 1) ^ ((value as i64) >> 31);
        self.internal_var_uint32(zigzag as u32)
    }

    pub fn var_uint32(&mut self, value: u32) {
        self.internal_var_uint32(value)
    }

    fn internal_var_uint32(&mut self, value: u32) {
        if value < 0x80 {
            self.u8(value as u8);
        } else if value < 0x4000 {
            // 2 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (value >> 7) as u8;
            self.u16(((u2 as u16) << 8) | u1 as u16);
        } else if value < 0x200000 {
            // 3 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (value >> 14) as u8;
            self.u16(((u2 as u16) << 8) | u1 as u16);
            self.u8(u3);
        } else if value < 0x10000000 {
            // 4 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (value >> 21) as u8;
            self.u32(((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32);
        } else {
            // 5 bytes
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (value >> 28) as u8;
            self.u32(((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32);
            self.u8(u5);
        }
    }

    pub fn var_int64(&mut self, value: i64) {
        let zigzag = ((value << 1) ^ (value >> 63)) as u64;
        self.internal_var_uint64(zigzag)
    }

    pub fn var_uint64(&mut self, value: u64) {
        self.internal_var_uint64(value)
    }

    fn internal_var_uint64(&mut self, value: u64) {
        if value < 0x80 {
            self.u8(value as u8);
        } else if value < 0x4000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (value >> 7) as u8;
            self.u16(((u2 as u16) << 8) | u1 as u16);
        } else if value < 0x200000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (value >> 14) as u8;
            self.u16(((u2 as u16) << 8) | u1 as u16);
            self.u8(u3);
        } else if value < 0x10000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (value >> 21) as u8;
            self.u32(((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32);
        } else if value < 0x800000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (value >> 28) as u8;
            self.u32(((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32);
            self.u8(u5);
        } else if value < 0x40000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (value >> 35) as u8;
            self.u32(((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32);
            self.u16(((u6 as u16) << 8) | u5 as u16);
        } else if value < 0x2000000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
            let u7 = (value >> 42) as u8;
            self.u32(((u4 as u32) << 24) | ((u3 as u32) << 16) | ((u2 as u32) << 8) | u1 as u32);
            self.u16(((u6 as u16) << 8) | u5 as u16);
            self.u8(u7);
        } else if value < 0x100000000000000 {
            let u1 = ((value as u8) & 0x7F) | 0x80;
            let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
            let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
            let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
            let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
            let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
            let u7 = (((value >> 42) as u8) & 0x7F) | 0x80;
            let u8 = (value >> 49) as u8;
            self.u64(
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
            self.u64(
                (u8 as u64) << 56
                    | (u7 as u64) << 48
                    | (u6 as u64) << 40
                    | (u5 as u64) << 32
                    | (u4 as u64) << 24
                    | (u3 as u64) << 16
                    | (u2 as u64) << 8
                    | (u1 as u64),
            );
            self.u8(u9);
        }
    }

    pub fn var_uint36_small(&mut self, value: u64) {
        assert!(value < (1u64 << 36), "value too large for 36-bit varint");
        if value < 0x80 {
            self.u8(value as u8);
        } else if value < 0x4000 {
            let b0 = ((value & 0x7F) as u8) | 0x80;
            let b1 = (value >> 7) as u8;
            let combined = ((b1 as u16) << 8) | (b0 as u16);
            self.u16(combined);
        } else if value < 0x200000 {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = value >> 14;
            let combined = b0 | (b1 << 8) | (b2 << 16);
            self.u32(combined as u32);
        } else if value < 0x10000000 {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = ((value >> 14) & 0x7F) | 0x80;
            let b3 = value >> 21;
            let combined = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
            self.u32(combined as u32);
        } else {
            let b0 = (value & 0x7F) | 0x80;
            let b1 = ((value >> 7) & 0x7F) | 0x80;
            let b2 = ((value >> 14) & 0x7F) | 0x80;
            let b3 = ((value >> 21) & 0x7F) | 0x80;
            let b4 = value >> 28;
            let combined = b0 | (b1 << 8) | (b2 << 16) | (b3 << 24) | (b4 << 32);
            self.u64(combined);
        }
    }

    pub fn latin1_string(&mut self, s: &str) -> usize {
        let mut count = 0;
        for c in s.chars() {
            let b = c as u32;
            assert!(b <= 0xFF, "Non-Latin1 character found");
            self.u8(b as u8);
            count += 1;
        }
        count
    }

    pub fn utf8_string(&mut self, s: &str) -> usize {
        let bytes = s.as_bytes();
        for &b in bytes {
            self.u8(b);
        }
        bytes.len()
    }

    pub fn utf16_string(&mut self, s: &str) -> usize {
        let mut count = 0;
        for u in s.encode_utf16() {
            self.u16(u);
            count += 1;
        }
        count * 2
    }

    pub fn bytes(&mut self, v: &[u8]) -> usize {
        self.reserve(v.len());
        self.bf.extend_from_slice(v);
        v.len()
    }

    pub fn set_bytes(&mut self, offset: usize, data: &[u8]) {
        self.bf
            .get_mut(offset..offset + data.len())
            .expect("//todo")
            .copy_from_slice(data);
    }
}

pub struct Reader<'de> {
    bf: &'de [u8],
    cursor: usize,
}

impl<'bf> Reader<'bf> {
    pub fn new(bf: &'bf [u8]) -> Reader<'bf> {
        Reader { bf, cursor: 0 }
    }

    fn move_next(&mut self, additional: usize) {
        self.cursor += additional;
    }

    pub fn slice_after_cursor(&self) -> &[u8] {
        &self.bf[self.cursor..self.bf.len()]
    }

    pub fn u8(&mut self) -> u8 {
        let result = self.bf[self.cursor];
        self.move_next(1);
        result
    }

    pub fn i8(&mut self) -> i8 {
        let result = self.bf[self.cursor];
        self.move_next(1);
        result as i8
    }

    pub fn u16(&mut self) -> u16 {
        let result = LittleEndian::read_u16(self.slice_after_cursor());
        self.move_next(2);
        result
    }

    pub fn i16(&mut self) -> i16 {
        let result = LittleEndian::read_i16(self.slice_after_cursor());
        self.move_next(2);
        result
    }

    pub fn u32(&mut self) -> u32 {
        let result = LittleEndian::read_u32(self.slice_after_cursor());
        self.move_next(4);
        result
    }

    pub fn i32(&mut self) -> i32 {
        let result = LittleEndian::read_i32(self.slice_after_cursor());
        self.move_next(4);
        result
    }

    pub fn u64(&mut self) -> u64 {
        let result = LittleEndian::read_u64(self.slice_after_cursor());
        self.move_next(8);
        result
    }

    pub fn i64(&mut self) -> i64 {
        let result = LittleEndian::read_i64(self.slice_after_cursor());
        self.move_next(8);
        result
    }

    pub fn f32(&mut self) -> f32 {
        let result = LittleEndian::read_f32(self.slice_after_cursor());
        self.move_next(4);
        result
    }

    pub fn f64(&mut self) -> f64 {
        let result = LittleEndian::read_f64(self.slice_after_cursor());
        self.move_next(8);
        result
    }

    pub fn var_uint32(&mut self) -> u32 {
        let start = self.cursor;
        let b0 = self.bf[start] as u32;
        if b0 < 0x80 {
            self.cursor += 1;
            return b0;
        }

        let mut encoded = b0 & 0x7F;
        let b1 = self.bf[start + 1] as u32;
        encoded |= (b1 & 0x7F) << 7;
        if b1 < 0x80 {
            self.cursor += 2;
            return encoded;
        }

        let b2 = self.bf[start + 2] as u32;
        encoded |= (b2 & 0x7F) << 14;
        if b2 < 0x80 {
            self.cursor += 3;
            return encoded;
        }

        let b3 = self.bf[start + 3] as u32;
        encoded |= (b3 & 0x7F) << 21;
        if b3 < 0x80 {
            self.cursor += 4;
            return encoded;
        }

        let b4 = self.bf[start + 4] as u32;
        encoded |= b4 << 28;
        self.cursor += 5;
        encoded
    }

    pub fn var_int32(&mut self) -> i32 {
        let encoded = self.var_uint32();
        ((encoded >> 1) as i32) ^ -((encoded & 1) as i32)
    }

    pub fn var_uint64(&mut self) -> u64 {
        let start = self.cursor;
        let b0 = self.bf[start] as u64;
        if b0 < 0x80 {
            self.cursor += 1;
            return b0;
        }

        let mut var64 = b0 & 0x7F;
        let b1 = self.bf[start + 1] as u64;
        var64 |= (b1 & 0x7F) << 7;
        if b1 < 0x80 {
            self.cursor += 2;
            return var64;
        }

        let b2 = self.bf[start + 2] as u64;
        var64 |= (b2 & 0x7F) << 14;
        if b2 < 0x80 {
            self.cursor += 3;
            return var64;
        }

        let b3 = self.bf[start + 3] as u64;
        var64 |= (b3 & 0x7F) << 21;
        if b3 < 0x80 {
            self.cursor += 4;
            return var64;
        }

        let b4 = self.bf[start + 4] as u64;
        var64 |= (b4 & 0x7F) << 28;
        if b4 < 0x80 {
            self.cursor += 5;
            return var64;
        }

        let b5 = self.bf[start + 5] as u64;
        var64 |= (b5 & 0x7F) << 35;
        if b5 < 0x80 {
            self.cursor += 6;
            return var64;
        }

        let b6 = self.bf[start + 6] as u64;
        var64 |= (b6 & 0x7F) << 42;
        if b6 < 0x80 {
            self.cursor += 7;
            return var64;
        }

        let b7 = self.bf[start + 7] as u64;
        var64 |= (b7 & 0x7F) << 49;
        if b7 < 0x80 {
            self.cursor += 8;
            return var64;
        }

        let b8 = self.bf[start + 8] as u64;
        var64 |= (b8 & 0xFF) << 56;
        self.cursor += 9;
        var64
    }

    pub fn var_int64(&mut self) -> i64 {
        let encoded = self.var_uint64();
        ((encoded >> 1) as i64) ^ -((encoded & 1) as i64)
    }

    pub fn latin1_string(&mut self, len: usize) -> String {
        let slice = &self.bf[self.cursor..self.cursor + len];
        let result: String = slice.iter().map(|&b| b as char).collect();
        self.move_next(len);
        result
    }

    pub fn utf8_string(&mut self, len: usize) -> String {
        let result = String::from_utf8_lossy(&self.bf[self.cursor..self.cursor + len]).to_string();
        self.move_next(len);
        result
    }

    pub fn utf16_string(&mut self, len: usize) -> String {
        assert!(len % 2 == 0, "UTF-16 length must be even");
        let slice = &self.bf[self.cursor..self.cursor + len];
        let units: Vec<u16> = slice
            .chunks(2)
            // little endian
            .map(|c| (c[0] as u16) | ((c[1] as u16) << 8))
            .collect();
        let result = String::from_utf16(&units)
            // lossy
            .unwrap_or_else(|_| String::from("�"));
        self.move_next(len);
        result
    }

    pub fn var_uint36_small(&mut self) -> u64 {
        let start = self.cursor;
        // fast path
        if self.slice_after_cursor().len() >= 8 {
            let bulk = self.u64();
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
            return result;
        }
        // slow path
        let mut result = 0u64;
        let mut shift = 0;
        while self.cursor < self.bf.len() {
            let b = self.u8();
            result |= ((b & 0x7F) as u64) << shift;
            if (b & 0x80) == 0 {
                break;
            }
            shift += 7;
        }
        result
    }

    pub fn skip(&mut self, len: u32) {
        self.move_next(len as usize);
    }

    pub fn slice(&self) -> &[u8] {
        self.bf
    }

    pub fn bytes(&mut self, len: usize) -> &'bf [u8] {
        let result = &self.bf[self.cursor..self.cursor + len];
        self.move_next(len);
        result
    }

    pub fn reset_cursor_to_here(&self) -> impl FnOnce(&mut Self) {
        let raw_cursor = self.cursor;
        move |this: &mut Self| {
            this.cursor = raw_cursor;
        }
    }

    pub fn aligned<T>(&self) -> bool {
        unsafe { (self.bf.as_ptr().add(self.cursor) as usize) % std::mem::align_of::<T>() == 0 }
    }
}
