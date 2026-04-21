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

use crate::float16::float16;

/// # Safety
#[inline(always)]
pub unsafe fn put_bool_at(ptr: *mut u8, value: bool) -> usize {
    *ptr = value as u8;
    1
}

/// # Safety
#[inline(always)]
pub unsafe fn put_i8_at(ptr: *mut u8, value: i8) -> usize {
    *ptr = value as u8;
    1
}

/// # Safety
#[inline(always)]
pub unsafe fn put_u8_at(ptr: *mut u8, value: u8) -> usize {
    *ptr = value;
    1
}

/// # Safety
#[inline(always)]
pub unsafe fn put_i16_at(ptr: *mut u8, value: i16) -> usize {
    put_u16_at(ptr, value as u16)
}

/// # Safety
#[inline(always)]
pub unsafe fn put_u16_at(ptr: *mut u8, value: u16) -> usize {
    (ptr as *mut u16).write_unaligned(value.to_le());
    2
}

/// # Safety
#[inline(always)]
pub unsafe fn put_i32_at(ptr: *mut u8, value: i32) -> usize {
    put_u32_at(ptr, value as u32)
}

/// # Safety
#[inline(always)]
pub unsafe fn put_u32_at(ptr: *mut u8, value: u32) -> usize {
    (ptr as *mut u32).write_unaligned(value.to_le());
    4
}

/// # Safety
#[inline(always)]
pub unsafe fn put_i64_at(ptr: *mut u8, value: i64) -> usize {
    put_u64_at(ptr, value as u64)
}

/// # Safety
#[inline(always)]
pub unsafe fn put_u64_at(ptr: *mut u8, value: u64) -> usize {
    (ptr as *mut u64).write_unaligned(value.to_le());
    8
}

/// # Safety
#[inline(always)]
pub unsafe fn put_f16_at(ptr: *mut u8, value: float16) -> usize {
    put_u16_at(ptr, value.to_bits())
}

/// # Safety
#[inline(always)]
pub unsafe fn put_f32_at(ptr: *mut u8, value: f32) -> usize {
    (ptr as *mut f32).write_unaligned(value);
    4
}

/// # Safety
#[inline(always)]
pub unsafe fn put_f64_at(ptr: *mut u8, value: f64) -> usize {
    (ptr as *mut f64).write_unaligned(value);
    8
}

/// # Safety
#[inline(always)]
pub unsafe fn put_i128_at(ptr: *mut u8, value: i128) -> usize {
    put_u128_at(ptr, value as u128)
}

/// # Safety
#[inline(always)]
pub unsafe fn put_u128_at(ptr: *mut u8, value: u128) -> usize {
    (ptr as *mut u128).write_unaligned(value.to_le());
    16
}

/// # Safety
#[inline(always)]
pub unsafe fn put_varint32_at(ptr: *mut u8, value: i32) -> usize {
    let zigzag = ((value as i64) << 1) ^ ((value as i64) >> 31);
    put_var_uint32_at(ptr, zigzag as u32)
}

/// # Safety
#[inline(always)]
pub unsafe fn put_var_uint32_at(ptr: *mut u8, value: u32) -> usize {
    if value < 0x80 {
        *ptr = value as u8;
        1
    } else if value < 0x4000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (value >> 7) as u8;
        (ptr as *mut u16).write_unaligned(u16::from_ne_bytes([u1, u2]));
        2
    } else if value < 0x200000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (value >> 14) as u8;
        (ptr as *mut u16).write_unaligned(u16::from_ne_bytes([u1, u2]));
        *ptr.add(2) = u3;
        3
    } else if value < 0x10000000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (value >> 21) as u8;
        (ptr as *mut u32).write_unaligned(u32::from_ne_bytes([u1, u2, u3, u4]));
        4
    } else {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
        let u5 = (value >> 28) as u8;
        (ptr as *mut u32).write_unaligned(u32::from_ne_bytes([u1, u2, u3, u4]));
        *ptr.add(4) = u5;
        5
    }
}

/// # Safety
#[inline(always)]
pub unsafe fn put_varint64_at(ptr: *mut u8, value: i64) -> usize {
    let zigzag = ((value << 1) ^ (value >> 63)) as u64;
    put_var_uint64_at(ptr, zigzag)
}

/// # Safety
#[inline(always)]
pub unsafe fn put_var_uint64_at(ptr: *mut u8, value: u64) -> usize {
    if value < 0x80 {
        *ptr = value as u8;
        return 1;
    }
    if value < 0x4000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (value >> 7) as u8;
        (ptr as *mut u16).write_unaligned(u16::from_ne_bytes([u1, u2]));
        return 2;
    }
    if value < 0x200000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (value >> 14) as u8;
        (ptr as *mut u16).write_unaligned(u16::from_ne_bytes([u1, u2]));
        *ptr.add(2) = u3;
        return 3;
    }
    if value < 0x10000000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (value >> 21) as u8;
        (ptr as *mut u32).write_unaligned(u32::from_ne_bytes([u1, u2, u3, u4]));
        return 4;
    }
    if value < 0x800000000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
        let u5 = (value >> 28) as u8;
        (ptr as *mut u32).write_unaligned(u32::from_ne_bytes([u1, u2, u3, u4]));
        *ptr.add(4) = u5;
        return 5;
    }
    if value < 0x40000000000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
        let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
        let u6 = (value >> 35) as u8;
        (ptr as *mut u32).write_unaligned(u32::from_ne_bytes([u1, u2, u3, u4]));
        (ptr.add(4) as *mut u16).write_unaligned(u16::from_ne_bytes([u5, u6]));
        return 6;
    }
    if value < 0x2000000000000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
        let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
        let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
        let u7 = (value >> 42) as u8;
        (ptr as *mut u32).write_unaligned(u32::from_ne_bytes([u1, u2, u3, u4]));
        (ptr.add(4) as *mut u16).write_unaligned(u16::from_ne_bytes([u5, u6]));
        *ptr.add(6) = u7;
        return 7;
    }
    if value < 0x100000000000000 {
        let u1 = ((value as u8) & 0x7F) | 0x80;
        let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
        let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
        let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
        let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
        let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
        let u7 = (((value >> 42) as u8) & 0x7F) | 0x80;
        let u8v = (value >> 49) as u8;
        (ptr as *mut u64).write_unaligned(u64::from_ne_bytes([u1, u2, u3, u4, u5, u6, u7, u8v]));
        return 8;
    }
    let u1 = ((value as u8) & 0x7F) | 0x80;
    let u2 = (((value >> 7) as u8) & 0x7F) | 0x80;
    let u3 = (((value >> 14) as u8) & 0x7F) | 0x80;
    let u4 = (((value >> 21) as u8) & 0x7F) | 0x80;
    let u5 = (((value >> 28) as u8) & 0x7F) | 0x80;
    let u6 = (((value >> 35) as u8) & 0x7F) | 0x80;
    let u7 = (((value >> 42) as u8) & 0x7F) | 0x80;
    let u8v = (((value >> 49) as u8) & 0x7F) | 0x80;
    let u9 = (value >> 56) as u8;
    (ptr as *mut u64).write_unaligned(u64::from_ne_bytes([u1, u2, u3, u4, u5, u6, u7, u8v]));
    *ptr.add(8) = u9;
    9
}

/// # Safety
#[inline(always)]
pub unsafe fn put_tagged_i64_at(ptr: *mut u8, value: i64) -> usize {
    const HALF_MIN: i64 = i32::MIN as i64 / 2;
    const HALF_MAX: i64 = i32::MAX as i64 / 2;
    if (HALF_MIN..=HALF_MAX).contains(&value) {
        let v = (value as i32) << 1;
        (ptr as *mut i32).write_unaligned(v.to_le());
        4
    } else {
        *ptr = 0b1;
        (ptr.add(1) as *mut i64).write_unaligned(value.to_le());
        9
    }
}

/// # Safety
#[inline(always)]
pub unsafe fn put_tagged_u64_at(ptr: *mut u8, value: u64) -> usize {
    if value <= i32::MAX as u64 {
        let v = (value as u32) << 1;
        (ptr as *mut u32).write_unaligned(v.to_le());
        4
    } else {
        *ptr = 0b1;
        (ptr.add(1) as *mut u64).write_unaligned(value.to_le());
        9
    }
}

/// # Safety
#[inline(always)]
pub unsafe fn put_usize_at(ptr: *mut u8, value: usize) -> usize {
    const SIZE: usize = std::mem::size_of::<usize>();
    match SIZE {
        2 => put_u16_at(ptr, value as u16),
        4 => put_var_uint32_at(ptr, value as u32),
        8 => put_var_uint64_at(ptr, value as u64),
        _ => unreachable!(),
    }
}
