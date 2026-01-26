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
    VARINT32 = 5,
    INT64 = 6,
    VARINT64 = 7,
    TAGGED_INT64 = 8,
    UINT8 = 9,
    UINT16 = 10,
    UINT32 = 11,
    VAR_UINT32 = 12,
    UINT64 = 13,
    VAR_UINT64 = 14,
    TAGGED_UINT64 = 15,
    FLOAT16 = 16,
    FLOAT32 = 17,
    FLOAT64 = 18,
    STRING = 19,
    LIST = 20,
    SET = 21,
    MAP = 22,
    ENUM = 23,
    NAMED_ENUM = 24,
    STRUCT = 25,
    COMPATIBLE_STRUCT = 26,
    NAMED_STRUCT = 27,
    NAMED_COMPATIBLE_STRUCT = 28,
    EXT = 29,
    NAMED_EXT = 30,
    // A tagged union value whose schema identity is not embedded.
    UNION = 31,
    // A union value with embedded numeric union type ID.
    TYPED_UNION = 32,
    // A union value with embedded union type name/TypeDef.
    NAMED_UNION = 33,
    // Represents an empty/unit value with no data.
    NONE = 34,
    DURATION = 35,
    TIMESTAMP = 36,
    DATE = 37,
    DECIMAL = 38,
    BINARY = 39,
    ARRAY = 40,
    BOOL_ARRAY = 41,
    INT8_ARRAY = 42,
    INT16_ARRAY = 43,
    INT32_ARRAY = 44,
    INT64_ARRAY = 45,
    UINT8_ARRAY = 46,
    UINT16_ARRAY = 47,
    UINT32_ARRAY = 48,
    UINT64_ARRAY = 49,
    FLOAT16_ARRAY = 50,
    FLOAT32_ARRAY = 51,
    FLOAT64_ARRAY = 52,
    // Rust-specific types (not part of xlang spec, for internal use)
    U128 = 64,
    INT128 = 65,
    // USIZE/ISIZE must have their own TypeId.
    // Although usize and u64 have the same size on 64-bit systems, they are
    // different Rust types.
    // When deserializing `Box<dyn Any>`, we need to create the exact type.
    // If we used UINT64's TypeId for usize, deserialization would create a u64 value,
    // and `result.downcast_ref::<usize>()` would return None.
    USIZE = 66,
    ISIZE = 67,
    U128_ARRAY = 68,
    INT128_ARRAY = 69,
    USIZE_ARRAY = 70,
    ISIZE_ARRAY = 71,
    // Bound value for range checks (types with id >= BOUND are not internal types).
    BOUND = 72,
}

pub const BOOL: u32 = TypeId::BOOL as u32;
pub const INT8: u32 = TypeId::INT8 as u32;
pub const INT16: u32 = TypeId::INT16 as u32;
pub const INT32: u32 = TypeId::INT32 as u32;
pub const VARINT32: u32 = TypeId::VARINT32 as u32;
pub const INT64: u32 = TypeId::INT64 as u32;
pub const VARINT64: u32 = TypeId::VARINT64 as u32;
pub const TAGGED_INT64: u32 = TypeId::TAGGED_INT64 as u32;
pub const UINT8: u32 = TypeId::UINT8 as u32;
pub const UINT16: u32 = TypeId::UINT16 as u32;
pub const UINT32: u32 = TypeId::UINT32 as u32;
pub const VAR_UINT32: u32 = TypeId::VAR_UINT32 as u32;
pub const UINT64: u32 = TypeId::UINT64 as u32;
pub const VAR_UINT64: u32 = TypeId::VAR_UINT64 as u32;
pub const TAGGED_UINT64: u32 = TypeId::TAGGED_UINT64 as u32;
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
pub const DATE: u32 = TypeId::DATE as u32;
pub const DECIMAL: u32 = TypeId::DECIMAL as u32;
pub const BINARY: u32 = TypeId::BINARY as u32;
pub const ARRAY: u32 = TypeId::ARRAY as u32;
pub const BOOL_ARRAY: u32 = TypeId::BOOL_ARRAY as u32;
pub const INT8_ARRAY: u32 = TypeId::INT8_ARRAY as u32;
pub const INT16_ARRAY: u32 = TypeId::INT16_ARRAY as u32;
pub const INT32_ARRAY: u32 = TypeId::INT32_ARRAY as u32;
pub const INT64_ARRAY: u32 = TypeId::INT64_ARRAY as u32;
pub const UINT8_ARRAY: u32 = TypeId::UINT8_ARRAY as u32;
pub const UINT16_ARRAY: u32 = TypeId::UINT16_ARRAY as u32;
pub const UINT32_ARRAY: u32 = TypeId::UINT32_ARRAY as u32;
pub const UINT64_ARRAY: u32 = TypeId::UINT64_ARRAY as u32;
pub const FLOAT16_ARRAY: u32 = TypeId::FLOAT16_ARRAY as u32;
pub const FLOAT32_ARRAY: u32 = TypeId::FLOAT32_ARRAY as u32;
pub const FLOAT64_ARRAY: u32 = TypeId::FLOAT64_ARRAY as u32;
pub const UNION: u32 = TypeId::UNION as u32;
pub const TYPED_UNION: u32 = TypeId::TYPED_UNION as u32;
pub const NAMED_UNION: u32 = TypeId::NAMED_UNION as u32;
pub const NONE: u32 = TypeId::NONE as u32;
// Rust-specific types
pub const U128: u32 = TypeId::U128 as u32;
pub const INT128: u32 = TypeId::INT128 as u32;
pub const USIZE: u32 = TypeId::USIZE as u32;
pub const ISIZE: u32 = TypeId::ISIZE as u32;
pub const U128_ARRAY: u32 = TypeId::U128_ARRAY as u32;
pub const INT128_ARRAY: u32 = TypeId::INT128_ARRAY as u32;
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

pub static BASIC_TYPES: [TypeId; 33] = [
    TypeId::BOOL,
    TypeId::INT8,
    TypeId::INT16,
    TypeId::INT32,
    TypeId::INT64,
    TypeId::UINT8,
    TypeId::UINT16,
    TypeId::UINT32,
    TypeId::UINT64,
    TypeId::FLOAT32,
    TypeId::FLOAT64,
    TypeId::STRING,
    TypeId::DATE,
    TypeId::TIMESTAMP,
    TypeId::BOOL_ARRAY,
    TypeId::BINARY,
    TypeId::INT8_ARRAY,
    TypeId::INT16_ARRAY,
    TypeId::INT32_ARRAY,
    TypeId::INT64_ARRAY,
    TypeId::UINT8_ARRAY,
    TypeId::UINT16_ARRAY,
    TypeId::UINT32_ARRAY,
    TypeId::UINT64_ARRAY,
    TypeId::FLOAT32_ARRAY,
    TypeId::FLOAT64_ARRAY,
    // Rust-specific types
    TypeId::U128,
    TypeId::INT128,
    TypeId::U128_ARRAY,
    TypeId::INT128_ARRAY,
    TypeId::USIZE,
    TypeId::ISIZE,
    TypeId::USIZE_ARRAY,
];

pub static PRIMITIVE_TYPES: [u32; 22] = [
    TypeId::BOOL as u32,
    TypeId::INT8 as u32,
    TypeId::INT16 as u32,
    TypeId::INT32 as u32,
    TypeId::VARINT32 as u32,
    TypeId::INT64 as u32,
    TypeId::VARINT64 as u32,
    TypeId::TAGGED_INT64 as u32,
    TypeId::UINT8 as u32,
    TypeId::UINT16 as u32,
    TypeId::UINT32 as u32,
    TypeId::VAR_UINT32 as u32,
    TypeId::UINT64 as u32,
    TypeId::VAR_UINT64 as u32,
    TypeId::TAGGED_UINT64 as u32,
    TypeId::FLOAT16 as u32,
    TypeId::FLOAT32 as u32,
    TypeId::FLOAT64 as u32,
    // Rust-specific
    TypeId::U128 as u32,
    TypeId::INT128 as u32,
    TypeId::USIZE as u32,
    TypeId::ISIZE as u32,
];

pub static PRIMITIVE_ARRAY_TYPES: [u32; 15] = [
    TypeId::BOOL_ARRAY as u32,
    TypeId::BINARY as u32,
    TypeId::INT8_ARRAY as u32,
    TypeId::INT16_ARRAY as u32,
    TypeId::INT32_ARRAY as u32,
    TypeId::INT64_ARRAY as u32,
    TypeId::UINT8_ARRAY as u32,
    TypeId::UINT16_ARRAY as u32,
    TypeId::UINT32_ARRAY as u32,
    TypeId::UINT64_ARRAY as u32,
    TypeId::FLOAT32_ARRAY as u32,
    TypeId::FLOAT64_ARRAY as u32,
    // Rust-specific
    TypeId::U128_ARRAY as u32,
    TypeId::INT128_ARRAY as u32,
    TypeId::USIZE_ARRAY as u32,
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
    ("u16", TypeId::UINT16_ARRAY as u32, "Vec<u16>"),
    ("u32", TypeId::UINT32_ARRAY as u32, "Vec<u32>"),
    ("u64", TypeId::UINT64_ARRAY as u32, "Vec<u64>"),
    ("f32", TypeId::FLOAT32_ARRAY as u32, "Vec<f32>"),
    ("f64", TypeId::FLOAT64_ARRAY as u32, "Vec<f64>"),
    // Rust-specific
    ("i128", TypeId::INT128_ARRAY as u32, "Vec<i128>"),
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
            | TypeId::UINT8
            | TypeId::UINT16
            | TypeId::UINT32
            | TypeId::UINT64
            | TypeId::FLOAT32
            | TypeId::FLOAT64
            // Rust-specific
            | TypeId::U128
            | TypeId::INT128
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
            | TYPED_UNION
            | NAMED_UNION
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
            | TYPED_UNION
            | NAMED_UNION
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
    pub const IS_CROSS_LANGUAGE_FLAG: u8 = 1 << 1;
    pub const IS_OUT_OF_BAND_FLAG: u8 = 1 << 2;
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
/// // User registered struct with id=3: (3 << 8) + 25 = 793
/// assert_eq!(format_type_id(793), "registered_id=3(STRUCT)");
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
        5 => "VARINT32",
        6 => "INT64",
        7 => "VARINT64",
        8 => "TAGGED_INT64",
        9 => "UINT8",
        10 => "UINT16",
        11 => "UINT32",
        12 => "VAR_UINT32",
        13 => "UINT64",
        14 => "VAR_UINT64",
        15 => "TAGGED_UINT64",
        16 => "FLOAT16",
        17 => "FLOAT32",
        18 => "FLOAT64",
        19 => "STRING",
        20 => "LIST",
        21 => "SET",
        22 => "MAP",
        23 => "ENUM",
        24 => "NAMED_ENUM",
        25 => "STRUCT",
        26 => "COMPATIBLE_STRUCT",
        27 => "NAMED_STRUCT",
        28 => "NAMED_COMPATIBLE_STRUCT",
        29 => "EXT",
        30 => "NAMED_EXT",
        31 => "UNION",
        32 => "TYPED_UNION",
        33 => "NAMED_UNION",
        34 => "NONE",
        35 => "DURATION",
        36 => "TIMESTAMP",
        37 => "DATE",
        38 => "DECIMAL",
        39 => "BINARY",
        40 => "ARRAY",
        41 => "BOOL_ARRAY",
        42 => "INT8_ARRAY",
        43 => "INT16_ARRAY",
        44 => "INT32_ARRAY",
        45 => "INT64_ARRAY",
        46 => "UINT8_ARRAY",
        47 => "UINT16_ARRAY",
        48 => "UINT32_ARRAY",
        49 => "UINT64_ARRAY",
        50 => "FLOAT16_ARRAY",
        51 => "FLOAT32_ARRAY",
        52 => "FLOAT64_ARRAY",
        // Rust-specific types
        64 => "U128",
        65 => "INT128",
        66 => "USIZE",
        67 => "ISIZE",
        68 => "U128_ARRAY",
        69 => "INT128_ARRAY",
        70 => "USIZE_ARRAY",
        71 => "ISIZE_ARRAY",
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
