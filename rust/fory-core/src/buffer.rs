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
        if value >> 7 == 0 {
            self.u8(value as u8);
        } else if value >> 14 == 0 {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = value >> 7;
            self.u16(((u2 << 8) | u1) as u16);
        } else if value >> 21 == 0 {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = ((value >> 7) & 0x7F) | 0x80;
            self.u16(((u2 << 8) | u1) as u16);
            self.u8((value >> 14) as u8);
        } else if value >> 28 == 0 {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = ((value >> 7) & 0x7F) | 0x80;
            let u3 = ((value >> 14) & 0x7F) | 0x80;
            let u4 = value >> 21;
            self.u32(((u4 << 24) | (u3 << 16) | (u2 << 8) | u1) as u32);
        } else {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = ((value >> 7) & 0x7F) | 0x80;
            let u3 = ((value >> 14) & 0x7F) | 0x80;
            let u4 = ((value >> 21) & 0x7F) | 0x80;
            self.u32(((u4 << 24) | (u3 << 16) | (u2 << 8) | u1) as u32);
            self.u8((value >> 28) as u8);
        }
    }

    pub fn var_uint32(&mut self, value: u32) {
        if value >> 7 == 0 {
            self.u8(value as u8);
        } else if value >> 14 == 0 {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = value >> 7;
            self.u16(((u2 << 8) | u1) as u16);
        } else if value >> 21 == 0 {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = ((value >> 7) & 0x7F) | 0x80;
            let u3 = value >> 14;
            self.u8(u1 as u8);
            self.u8(u2 as u8);
            self.u8(u3 as u8);
        } else if value >> 28 == 0 {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = ((value >> 7) & 0x7F) | 0x80;
            let u3 = ((value >> 14) & 0x7F) | 0x80;
            let u4 = value >> 21;
            self.u32((u4 << 24) | (u3 << 16) | (u2 << 8) | u1);
        } else {
            let u1 = (value & 0x7F) | 0x80;
            let u2 = ((value >> 7) & 0x7F) | 0x80;
            let u3 = ((value >> 14) & 0x7F) | 0x80;
            let u4 = ((value >> 21) & 0x7F) | 0x80;
            let u5 = value >> 28;
            self.u8(u1 as u8);
            self.u8(u2 as u8);
            self.u8(u3 as u8);
            self.u8(u4 as u8);
            self.u8(u5 as u8);
        }
    }

    pub fn bytes(&mut self, v: &[u8]) {
        self.reserve(v.len());
        self.bf.extend_from_slice(v);
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

    fn slice_after_cursor(&self) -> &[u8] {
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

    pub fn var_int32(&mut self) -> i32 {
        let mut byte_ = self.i8() as i32;
        let mut result = byte_ & 0x7F;
        if (byte_ & 0x80) != 0 {
            byte_ = self.i8() as i32;
            result |= (byte_ & 0x7F) << 7;
            if (byte_ & 0x80) != 0 {
                byte_ = self.i8() as i32;
                result |= (byte_ & 0x7F) << 14;
                if (byte_ & 0x80) != 0 {
                    byte_ = self.i8() as i32;
                    result |= (byte_ & 0x7F) << 21;
                    if (byte_ & 0x80) != 0 {
                        byte_ = self.i8() as i32;
                        result |= (byte_ & 0x7F) << 28;
                    }
                }
            }
        }
        result
    }

    pub fn var_uint32(&mut self) -> u32 {
        let mut byte_ = self.i8() as u32;
        let mut result = byte_ & 0x7F;
        if (byte_ & 0x80) != 0 {
            byte_ = self.i8() as u32;
            result |= (byte_ & 0x7F) << 7;
            if (byte_ & 0x80) != 0 {
                byte_ = self.i8() as u32;
                result |= (byte_ & 0x7F) << 14;
                if (byte_ & 0x80) != 0 {
                    byte_ = self.i8() as u32;
                    result |= (byte_ & 0x7F) << 21;
                    if (byte_ & 0x80) != 0 {
                        byte_ = self.i8() as u32;
                        result |= (byte_ & 0x7F) << 28;
                    }
                }
            }
        }
        result
    }

    pub fn string(&mut self, len: usize) -> String {
        let result = String::from_utf8_lossy(&self.bf[self.cursor..self.cursor + len]).to_string();
        self.move_next(len);
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
