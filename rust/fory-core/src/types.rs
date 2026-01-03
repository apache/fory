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
use num_enum::{IntoPrimitive, TryFromPrimitive};
use std::mem;

#[allow(dead_code)]
pub enum StringFlag {
    LATIN1 = 0,
    UTF8 = 1,
}

#[derive(Debug, TryFromPrimitive)]
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

/// Controls how reference and null flags are handled during serialization.
///
/// This enum combines nullable semantics and reference tracking into one parameter,
/// enabling fine-grained control per type and per field:
/// - `None` = non-nullable, no ref tracking (primitives)
/// - `NullOnly` = nullable, no circular ref tracking
/// - `Tracking` = nullable, with circular ref tracking (Rc/Arc/Weak)
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
#[repr(u8)]
pub enum RefMode {
    /// Skip ref handling entirely. No ref/null flags are written/read.
    /// Used for non-nullable primitives or when caller handles ref externally.
    #[default]
    None = 0,

    /// Only null check without reference tracking.
    /// Write: NullFlag (-3) for None, NotNullValueFlag (-1) for Some.
    /// Read: Read flag and return ForyDefault on null.
    NullOnly = 1,

    /// Full reference tracking with circular reference support.
    /// Write: Uses RefWriter which writes NullFlag, RefFlag+refId, or RefValueFlag.
    /// Read: Uses RefReader with full reference resolution.
    Tracking = 2,
}

impl RefMode {
    /// Create RefMode from nullable and track_ref flags.
    #[inline]
    pub const fn from_flags(nullable: bool, track_ref: bool) -> Self {
        match (nullable, track_ref) {
            (false, false) => RefMode::None,
            (true, false) => RefMode::NullOnly,
            (_, true) => RefMode::Tracking,
        }
    }

    /// Check if this mode reads/writes ref flags.
    #[inline]
    pub const fn has_ref_flag(self) -> bool {
        !matches!(self, RefMode::None)
    }

    /// Check if this mode tracks circular references.
    #[inline]
    pub const fn tracks_refs(self) -> bool {
        matches!(self, RefMode::Tracking)
    }

    /// Check if this mode handles nullable values.
    #[inline]
    pub const fn is_nullable(self) -> bool {
        !matches!(self, RefMode::None)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, TryFromPrimitive, IntoPrimitive)]
#[allow(non_camel_case_types)]
#[repr(i16)]
pub enum TypeId {
    // Unknown/polymorphic type marker.
    UNKNOWN = 0,
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
    // A tagged union type that can hold one of several alternative types.
    UNION = 38,
    // Represents an empty/unit value with no data.
    NONE = 39,
    U8 = 64,
    U16 = 65,
    U32 = 66,
    VAR_U32 = 67,
    U64 = 68,
    VAR_U64 = 69,
    SLI_U64 = 70,
    U128 = 71,
    INT128 = 72,
    // USIZE/ISIZE must have their own TypeId.
    // Although usize and u64 have the same size on 64-bit systems, they are
    // different Rust types.
    // When deserializing `Box<dyn Any>`, we need to create the exact type.
    // If we used U64's TypeId for usize, deserialization would create a u64 value,
    // and `result.downcast_ref::<usize>()` would return None.
    USIZE = 73,
    ISIZE = 74,
    U16_ARRAY = 75,
    U32_ARRAY = 76,
    U64_ARRAY = 77,
    U128_ARRAY = 78,
    INT128_ARRAY = 79,
    USIZE_ARRAY = 80,
    ISIZE_ARRAY = 81,
    // Bound value for range checks (types with id >= BOUND are not internal types).
    BOUND = 82,
}

pub const BOOL: u32 = TypeId::BOOL as u32;
pub const INT8: u32 = TypeId::INT8 as u32;
pub const INT16: u32 = TypeId::INT16 as u32;
pub const INT32: u32 = TypeId::INT32 as u32;
pub const VAR_INT32: u32 = TypeId::VAR_INT32 as u32;
pub const INT64: u32 = TypeId::INT64 as u32;
pub const VAR_INT64: u32 = TypeId::VAR_INT64 as u32;
pub const SLI_INT64: u32 = TypeId::SLI_INT64 as u32;
pub const INT128: u32 = TypeId::INT128 as u32;
pub const FLOAT16: u32 = TypeId::FLOAT16 as u32;
pub const FLOAT32: u32 = TypeId::FLOAT32 as u32;
pub const FLOAT64: u32 = TypeId::FLOAT64 as u32;
pub const STRING: u32 = TypeId::STRING as u32;
pub const ENUM: u32 = TypeId::ENUM as u32;
pub const NAMED_ENUM: u32 = TypeId::NAMED_ENUM as u32;
pub const STRUCT: u32 = TypeId::STRUCT as u32;
pub const COMPATIBLE_STRUCT: u32 = TypeId::COMPATIBLE_STRUCT as u32;
pub const NAMED_STRUCT: u32 = TypeId::NAMED_STRUCT as u32;
pub const NAMED_COMPATIBLE_STRUCT: u32 = TypeId::NAMED_COMPATIBLE_STRUCT as u32;
pub const EXT: u32 = TypeId::EXT as u32;
pub const NAMED_EXT: u32 = TypeId::NAMED_EXT as u32;
pub const LIST: u32 = TypeId::LIST as u32;
pub const SET: u32 = TypeId::SET as u32;
pub const MAP: u32 = TypeId::MAP as u32;
pub const DURATION: u32 = TypeId::DURATION as u32;
pub const TIMESTAMP: u32 = TypeId::TIMESTAMP as u32;
pub const LOCAL_DATE: u32 = TypeId::LOCAL_DATE as u32;
pub const DECIMAL: u32 = TypeId::DECIMAL as u32;
pub const BINARY: u32 = TypeId::BINARY as u32;
pub const ARRAY: u32 = TypeId::ARRAY as u32;
pub const BOOL_ARRAY: u32 = TypeId::BOOL_ARRAY as u32;
pub const INT8_ARRAY: u32 = TypeId::INT8_ARRAY as u32;
pub const INT16_ARRAY: u32 = TypeId::INT16_ARRAY as u32;
pub const INT32_ARRAY: u32 = TypeId::INT32_ARRAY as u32;
pub const INT64_ARRAY: u32 = TypeId::INT64_ARRAY as u32;
pub const FLOAT16_ARRAY: u32 = TypeId::FLOAT16_ARRAY as u32;
pub const FLOAT32_ARRAY: u32 = TypeId::FLOAT32_ARRAY as u32;
pub const FLOAT64_ARRAY: u32 = TypeId::FLOAT64_ARRAY as u32;
pub const UNION: u32 = TypeId::UNION as u32;
pub const NONE: u32 = TypeId::NONE as u32;
pub const U8: u32 = TypeId::U8 as u32;
pub const U16: u32 = TypeId::U16 as u32;
pub const U32: u32 = TypeId::U32 as u32;
pub const U64: u32 = TypeId::U64 as u32;
pub const U128: u32 = TypeId::U128 as u32;
pub const VAR_U32: u32 = TypeId::VAR_U32 as u32;
pub const VAR_U64: u32 = TypeId::VAR_U64 as u32;
pub const SLI_U64: u32 = TypeId::SLI_U64 as u32;
pub const U16_ARRAY: u32 = TypeId::U16_ARRAY as u32;
pub const U32_ARRAY: u32 = TypeId::U32_ARRAY as u32;
pub const U64_ARRAY: u32 = TypeId::U64_ARRAY as u32;
pub const U128_ARRAY: u32 = TypeId::U128_ARRAY as u32;
pub const INT128_ARRAY: u32 = TypeId::INT128_ARRAY as u32;
pub const USIZE: u32 = TypeId::USIZE as u32;
pub const ISIZE: u32 = TypeId::ISIZE as u32;
pub const USIZE_ARRAY: u32 = TypeId::USIZE_ARRAY as u32;
pub const ISIZE_ARRAY: u32 = TypeId::ISIZE_ARRAY as u32;
pub const UNKNOWN: u32 = TypeId::UNKNOWN as u32;
pub const BOUND: u32 = TypeId::BOUND as u32;

/// Returns true if the given TypeId represents an enum type.
///
/// This is used during fingerprint computation to match Java/C++ behavior
/// where enum fields are always treated as nullable (since Java enums are
/// reference types that can be null).
///
/// **NOTE**: ENUM, NAMED_ENUM, and UNION are all considered enum types since Rust enums
/// can be represented as Union in xlang mode when they have data-carrying variants.
#[inline]
pub const fn is_enum_type_id(type_id: TypeId) -> bool {
    matches!(type_id, TypeId::ENUM | TypeId::NAMED_ENUM | TypeId::UNION)
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

pub static BASIC_TYPES: [TypeId; 29] = [
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
    TypeId::U8,
    TypeId::U16,
    TypeId::U32,
    TypeId::U64,
    TypeId::U128,
    TypeId::U16_ARRAY,
    TypeId::U32_ARRAY,
    TypeId::U64_ARRAY,
    TypeId::U128_ARRAY,
    TypeId::INT128,
    TypeId::INT128_ARRAY,
];

pub static PRIMITIVE_TYPES: [u32; 12] = [
    TypeId::BOOL as u32,
    TypeId::INT8 as u32,
    TypeId::INT16 as u32,
    TypeId::INT32 as u32,
    TypeId::INT64 as u32,
    TypeId::FLOAT32 as u32,
    TypeId::FLOAT64 as u32,
    TypeId::U8 as u32,
    TypeId::U16 as u32,
    TypeId::U32 as u32,
    TypeId::U64 as u32,
    TypeId::U128 as u32,
];

pub static PRIMITIVE_ARRAY_TYPES: [u32; 13] = [
    TypeId::BOOL_ARRAY as u32,
    TypeId::BINARY as u32,
    TypeId::INT8_ARRAY as u32,
    TypeId::INT16_ARRAY as u32,
    TypeId::INT32_ARRAY as u32,
    TypeId::INT64_ARRAY as u32,
    TypeId::FLOAT32_ARRAY as u32,
    TypeId::FLOAT64_ARRAY as u32,
    TypeId::U16_ARRAY as u32,
    TypeId::U32_ARRAY as u32,
    TypeId::U64_ARRAY as u32,
    TypeId::U128_ARRAY as u32,
    TypeId::INT128_ARRAY as u32,
];

pub static BASIC_TYPE_NAMES: [&str; 18] = [
    "bool",
    "i8",
    "i16",
    "i32",
    "i64",
    "i128",
    "f32",
    "f64",
    "String",
    "NaiveDate",
    "NaiveDateTime",
    "u8",
    "u16",
    "u32",
    "u64",
    "u128",
    "usize",
    "isize",
];

pub static CONTAINER_TYPES: [TypeId; 3] = [TypeId::LIST, TypeId::SET, TypeId::MAP];

pub static CONTAINER_TYPE_NAMES: [&str; 3] = ["Vec", "HashSet", "HashMap"];

pub static PRIMITIVE_ARRAY_TYPE_MAP: &[(&str, u32, &str)] = &[
    ("u8", TypeId::BINARY as u32, "Vec<u8>"),
    ("bool", TypeId::BOOL_ARRAY as u32, "Vec<bool>"),
    ("i8", TypeId::INT8_ARRAY as u32, "Vec<i8>"),
    ("i16", TypeId::INT16_ARRAY as u32, "Vec<i16>"),
    ("i32", TypeId::INT32_ARRAY as u32, "Vec<i32>"),
    ("i64", TypeId::INT64_ARRAY as u32, "Vec<i64>"),
    ("i128", TypeId::INT128_ARRAY as u32, "Vec<i128>"),
    ("f32", TypeId::FLOAT32_ARRAY as u32, "Vec<f32>"),
    ("f64", TypeId::FLOAT64_ARRAY as u32, "Vec<f64>"),
    ("u16", TypeId::U16_ARRAY as u32, "Vec<u16>"),
    ("u32", TypeId::U32_ARRAY as u32, "Vec<u32>"),
    ("u64", TypeId::U64_ARRAY as u32, "Vec<u64>"),
    ("u128", TypeId::U128_ARRAY as u32, "Vec<u128>"),
    ("usize", TypeId::USIZE_ARRAY as u32, "Vec<usize>"),
    ("isize", TypeId::ISIZE_ARRAY as u32, "Vec<isize>"),
];

/// Keep as const fn for compile time evaluation or constant folding
#[inline(always)]
pub const fn is_primitive_type_id(type_id: TypeId) -> bool {
    matches!(
        type_id,
        TypeId::BOOL
            | TypeId::INT8
            | TypeId::INT16
            | TypeId::INT32
            | TypeId::INT64
            | TypeId::INT128
            | TypeId::FLOAT32
            | TypeId::FLOAT64
            | TypeId::U8
            | TypeId::U16
            | TypeId::U32
            | TypeId::U64
            | TypeId::U128
            | TypeId::USIZE
            | TypeId::ISIZE
    )
}

/// Keep as const fn for compile time evaluation or constant folding
/// Internal types are all types in `0 < id < BOUND` that are not struct/ext/enum types.
#[inline(always)]
pub const fn is_internal_type(type_id: u32) -> bool {
    if type_id == UNKNOWN || type_id >= BOUND {
        return false;
    }
    !matches!(
        type_id,
        ENUM | NAMED_ENUM
            | STRUCT
            | COMPATIBLE_STRUCT
            | NAMED_STRUCT
            | NAMED_COMPATIBLE_STRUCT
            | EXT
            | NAMED_EXT
    )
}

/// Keep as const fn for compile time evaluation or constant folding.
/// Returns true if this type needs type info written in compatible mode.
/// Only user-defined types (struct, ext, unknown) need type info.
/// Internal types (primitives, strings, collections, enums) don't need type info.
#[inline(always)]
pub const fn need_to_write_type_for_field(type_id: TypeId) -> bool {
    matches!(
        type_id,
        TypeId::STRUCT
            | TypeId::COMPATIBLE_STRUCT
            | TypeId::NAMED_STRUCT
            | TypeId::NAMED_COMPATIBLE_STRUCT
            | TypeId::EXT
            | TypeId::NAMED_EXT
            | TypeId::UNKNOWN
    )
}

/// Keep as const fn for compile time evaluation or constant folding
#[inline(always)]
pub const fn is_user_type(type_id: u32) -> bool {
    matches!(
        type_id,
        ENUM | NAMED_ENUM
            | UNION
            | STRUCT
            | COMPATIBLE_STRUCT
            | NAMED_STRUCT
            | NAMED_COMPATIBLE_STRUCT
            | EXT
            | NAMED_EXT
    )
}

pub fn compute_field_hash(hash: u32, id: i16) -> u32 {
    let mut new_hash: u64 = (hash as u64) * 31 + (id as u64);
    while new_hash >= MAX_UNT32 {
        new_hash /= 7;
    }
    new_hash as u32
}

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
            _ => Err(Error::invalid_data(format!(
                "Unsupported language code, value:{num}"
            ))),
        }
    }
}

// every object start with i8 i16 reference flag and type flag
pub const SIZE_OF_REF_AND_TYPE: usize = mem::size_of::<i8>() + mem::size_of::<i16>();

pub const MAGIC_NUMBER: u16 = 0x62d4;

/// Formats a combined type ID into a human-readable string.
///
/// Combined type IDs have the format: `(registered_id << 8) + internal_type_id`.
/// This function extracts both parts and formats them for debugging.
///
/// For internal types (type_id < BOUND), returns just the type name.
/// For user-registered types, returns format like "registered_id=3(STRUCT)".
///
/// # Examples
/// ```
/// use fory_core::types::format_type_id;
///
/// // Internal type (e.g., BOOL = 1)
/// assert_eq!(format_type_id(1), "BOOL");
///
/// // User registered struct with id=3: (3 << 8) + 15 = 783
/// assert_eq!(format_type_id(783), "registered_id=3(STRUCT)");
/// ```
pub fn format_type_id(type_id: u32) -> String {
    let internal_type_id = type_id & 0xff;
    let registered_id = type_id >> 8;

    let type_name = match internal_type_id {
        0 => "UNKNOWN",
        1 => "BOOL",
        2 => "INT8",
        3 => "INT16",
        4 => "INT32",
        5 => "VAR_INT32",
        6 => "INT64",
        7 => "VAR_INT64",
        8 => "SLI_INT64",
        9 => "FLOAT16",
        10 => "FLOAT32",
        11 => "FLOAT64",
        12 => "STRING",
        13 => "ENUM",
        14 => "NAMED_ENUM",
        15 => "STRUCT",
        16 => "COMPATIBLE_STRUCT",
        17 => "NAMED_STRUCT",
        18 => "NAMED_COMPATIBLE_STRUCT",
        19 => "EXT",
        20 => "NAMED_EXT",
        21 => "LIST",
        22 => "SET",
        23 => "MAP",
        24 => "DURATION",
        25 => "TIMESTAMP",
        26 => "LOCAL_DATE",
        27 => "DECIMAL",
        28 => "BINARY",
        29 => "ARRAY",
        30 => "BOOL_ARRAY",
        31 => "INT8_ARRAY",
        32 => "INT16_ARRAY",
        33 => "INT32_ARRAY",
        34 => "INT64_ARRAY",
        35 => "FLOAT16_ARRAY",
        36 => "FLOAT32_ARRAY",
        37 => "FLOAT64_ARRAY",
        38 => "UNION",
        39 => "NONE",
        64 => "U8",
        65 => "U16",
        66 => "U32",
        67 => "VAR_U32",
        68 => "U64",
        69 => "VAR_U64",
        70 => "SLI_U64",
        71 => "U128",
        72 => "INT128",
        73 => "USIZE",
        74 => "ISIZE",
        75 => "U16_ARRAY",
        76 => "U32_ARRAY",
        77 => "U64_ARRAY",
        78 => "U128_ARRAY",
        79 => "INT128_ARRAY",
        80 => "USIZE_ARRAY",
        81 => "ISIZE_ARRAY",
        _ => "UNKNOWN_TYPE",
    };

    // If it's a pure internal type (no registered_id), just return the type name
    if registered_id == 0 {
        type_name.to_string()
    } else {
        // For user-registered types, show both the registered ID and internal type
        format!("registered_id={}({})", registered_id, type_name)
    }
}

/// Computes the actual type ID for extension types.
///
/// Extension types combine a user-registered type ID with an internal EXT or NAMED_EXT marker.
/// The format is: `(type_id << 8) + internal_type_id`.
pub fn get_ext_actual_type_id(type_id: u32, register_by_name: bool) -> u32 {
    (type_id << 8)
        + if register_by_name {
            TypeId::NAMED_EXT as u32
        } else {
            TypeId::EXT as u32
        }
}
