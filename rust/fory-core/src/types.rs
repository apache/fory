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
use anyhow::anyhow;
use num_enum::{IntoPrimitive, TryFromPrimitive};
use std::mem;

#[allow(dead_code)]
pub enum StringFlag {
    LATIN1 = 0,
    UTF8 = 1,
}

#[derive(TryFromPrimitive)]
#[repr(i8)]
pub enum RefFlag {
    Null = -3,
    // Ref indicates that object is a not-null value.
    // We don't use another byte to indicate REF, so that we can save one byte.
    Ref = -2,
    // NotNullValueFlag indicates that the object is a non-null value.
    NotNullValue = -1,
    // RefValueFlag indicates that the object is a referencable and first read.
    RefValue = 0,
}

#[derive(Clone, Copy, Debug, PartialEq, TryFromPrimitive, IntoPrimitive)]
#[allow(non_camel_case_types)]
#[repr(i16)]
pub enum TypeId {
    BOOL = 1,
    INT8 = 2,
    INT16 = 3,
    INT32 = 4,
    VAR_INT32 = 5,
    INT64 = 6,
    VAR_INT64 = 7,
    SLI_INT64 = 8,
    FLOAT16 = 9,
    FLOAT32 = 10,
    FLOAT64 = 11,
    STRING = 12,
    ENUM = 13,
    NAMED_ENUM = 14,
    STRUCT = 15,
    COMPATIBLE_STRUCT = 16,
    NAMED_STRUCT = 17,
    NAMED_COMPATIBLE_STRUCT = 18,
    EXT = 19,
    NAMED_EXT = 20,
    LIST = 21,
    SET = 22,
    MAP = 23,
    DURATION = 24,
    TIMESTAMP = 25,
    LOCAL_DATE = 26,
    DECIMAL = 27,
    BINARY = 28,
    ARRAY = 29,
    BOOL_ARRAY = 30,
    INT8_ARRAY = 31,
    INT16_ARRAY = 32,
    INT32_ARRAY = 33,
    INT64_ARRAY = 34,
    FLOAT16_ARRAY = 35,
    FLOAT32_ARRAY = 36,
    FLOAT64_ARRAY = 37,
    ARROW_RECORD_BATCH = 38,
    ARROW_TABLE = 39,
    UNKNOWN = 63,
    ForyAny = 256,
    // only used at receiver peer
    ForyNullable = 265,
}

const MAX_UNT32: u64 = (1 << 31) - 1;

// todo: struct hash
#[allow(dead_code)]
pub fn compute_string_hash(s: &str) -> u32 {
    let mut hash: u64 = 17;
    s.as_bytes().iter().for_each(|b| {
        hash = (hash * 31) + (*b as u64);
        while hash >= MAX_UNT32 {
            hash /= 7;
        }
    });
    hash as u32
}

pub static BASIC_TYPES: [TypeId; 18] = [
    TypeId::BOOL,
    TypeId::INT8,
    TypeId::INT16,
    TypeId::INT32,
    TypeId::INT64,
    TypeId::FLOAT32,
    TypeId::FLOAT64,
    TypeId::STRING,
    TypeId::LOCAL_DATE,
    TypeId::TIMESTAMP,
    TypeId::BOOL_ARRAY,
    TypeId::BINARY,
    TypeId::INT8_ARRAY,
    TypeId::INT16_ARRAY,
    TypeId::INT32_ARRAY,
    TypeId::INT64_ARRAY,
    TypeId::FLOAT32_ARRAY,
    TypeId::FLOAT64_ARRAY,
];

pub static PRIMITIVE_TYPES: [u32; 7] = [
    TypeId::BOOL as u32,
    TypeId::INT8 as u32,
    TypeId::INT16 as u32,
    TypeId::INT32 as u32,
    TypeId::INT64 as u32,
    TypeId::FLOAT32 as u32,
    TypeId::FLOAT64 as u32,
];

pub static FINAL_TYPES: [u32; 3] = [TypeId::STRING as u32, TypeId::LOCAL_DATE as u32, TypeId::TIMESTAMP as u32];

pub static PRIMITIVE_ARRAY_TYPES: [u32; 8] = [
    TypeId::BOOL_ARRAY as u32,
    TypeId::BINARY as u32,
    TypeId::INT8_ARRAY as u32,
    TypeId::INT16_ARRAY as u32,
    TypeId::INT32_ARRAY as u32,
    TypeId::INT64_ARRAY as u32,
    TypeId::FLOAT32_ARRAY as u32,
    TypeId::FLOAT64_ARRAY as u32,
];

pub static BASIC_TYPE_NAMES: [&str; 10] = [
    "bool",
    "i8",
    "i16",
    "i32",
    "i64",
    "f32",
    "f64",
    "String",
    "NaiveDate",
    "NaiveDateTime",
];

pub static CONTAINER_TYPES: [TypeId; 3] = [TypeId::LIST, TypeId::SET, TypeId::MAP];

pub static CONTAINER_TYPE_NAMES: [&str; 3] = ["Vec", "HashSet", "HashMap"];

pub static PRIMITIVE_ARRAY_TYPE_MAP: &[(&str, u32, &str)] = &[
    // ("_binary", TypeId::BINARY as u32),
    ("bool", TypeId::BOOL_ARRAY as u32, "Vec<bool>"),
    ("i8", TypeId::INT8_ARRAY as u32, "Vec<i8>"),
    ("i16", TypeId::INT16_ARRAY as u32, "Vec<i16>"),
    ("i32", TypeId::INT32_ARRAY as u32, "Vec<i32>"),
    ("i64", TypeId::INT64_ARRAY as u32, "Vec<i64>"),
    ("f32", TypeId::FLOAT32_ARRAY as u32, "Vec<f32>"),
    ("f64", TypeId::FLOAT64_ARRAY as u32, "Vec<f64>"),
];

pub fn compute_field_hash(hash: u32, id: i16) -> u32 {
    let mut new_hash: u64 = (hash as u64) * 31 + (id as u64);
    while new_hash >= MAX_UNT32 {
        new_hash /= 7;
    }
    new_hash as u32
}

// pub fn compute_struct_hash(props: Vec<(&str, FieldType)>) -> u32 {
//     let mut hash = 17;
//     props.iter().for_each(|prop| {
//         let (_name, ty) = prop;
//         hash = match ty {
//             FieldType::ARRAY | FieldType::MAP => compute_field_hash(hash, *ty as i16),
//             _ => hash,
//         };
//         let is_basic_type = BASIC_TYPES.contains(ty);
//         if is_basic_type {
//             hash = compute_field_hash(hash, *ty as i16);
//         }
//     });
//     hash
// }

pub mod config_flags {
    pub const IS_NULL_FLAG: u8 = 1 << 0;
    pub const IS_LITTLE_ENDIAN_FLAG: u8 = 2;
    pub const IS_CROSS_LANGUAGE_FLAG: u8 = 4;
    pub const IS_OUT_OF_BAND_FLAG: u8 = 8;
}

#[derive(Debug, PartialEq)]
pub enum Language {
    Xlang = 0,
    Java = 1,
    Python = 2,
    Cpp = 3,
    Go = 4,
    Javascript = 5,
    Rust = 6,
    Dart = 7,
}

#[derive(PartialEq)]
pub enum Mode {
    // Type declaration must be consistent between serialization peer and deserialization peer.
    SchemaConsistent,
    // Type declaration can be different between serialization peer and deserialization peer.
    // They can add/delete fields independently.
    Compatible,
}

impl TryFrom<u8> for Language {
    type Error = Error;

    fn try_from(num: u8) -> Result<Self, Error> {
        match num {
            0 => Ok(Language::Xlang),
            1 => Ok(Language::Java),
            2 => Ok(Language::Python),
            3 => Ok(Language::Cpp),
            4 => Ok(Language::Go),
            5 => Ok(Language::Javascript),
            6 => Ok(Language::Rust),
            _ => Err(anyhow!("Unsupported language code, value:{num}"))?,
        }
    }
}

// every object start with i8 i16 reference flag and type flag
pub const SIZE_OF_REF_AND_TYPE: usize = mem::size_of::<i8>() + mem::size_of::<i16>();

pub const MAGIC_NUMBER: u16 = 0x62d4;
