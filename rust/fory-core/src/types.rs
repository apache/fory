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
#[repr(u8)]
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
    FLOAT8 = 16,
    FLOAT16 = 17,
    BFLOAT16 = 18,
    FLOAT32 = 19,
    FLOAT64 = 20,
    STRING = 21,
    LIST = 22,
    SET = 23,
    MAP = 24,
    ENUM = 25,
    NAMED_ENUM = 26,
    STRUCT = 27,
    COMPATIBLE_STRUCT = 28,
    NAMED_STRUCT = 29,
    NAMED_COMPATIBLE_STRUCT = 30,
    EXT = 31,
    NAMED_EXT = 32,
    // A tagged union value whose schema identity is not embedded.
    UNION = 33,
    // A union value with embedded numeric union type ID.
    TYPED_UNION = 34,
    // A union value with embedded union type name/TypeDef.
    NAMED_UNION = 35,
    // Represents an empty/unit value with no data.
    NONE = 36,
    DURATION = 37,
    TIMESTAMP = 38,
    DATE = 39,
    DECIMAL = 40,
    BINARY = 41,
    ARRAY = 42,
    BOOL_ARRAY = 43,
    INT8_ARRAY = 44,
    INT16_ARRAY = 45,
    INT32_ARRAY = 46,
    INT64_ARRAY = 47,
    UINT8_ARRAY = 48,
    UINT16_ARRAY = 49,
    UINT32_ARRAY = 50,
    UINT64_ARRAY = 51,
    FLOAT8_ARRAY = 52,
    FLOAT16_ARRAY = 53,
    BFLOAT16_ARRAY = 54,
    FLOAT32_ARRAY = 55,
    FLOAT64_ARRAY = 56,
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
pub const FLOAT8: u32 = TypeId::FLOAT8 as u32;
pub const FLOAT16: u32 = TypeId::FLOAT16 as u32;
pub const BFLOAT16: u32 = TypeId::BFLOAT16 as u32;
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
pub const FLOAT8_ARRAY: u32 = TypeId::FLOAT8_ARRAY as u32;
pub const FLOAT16_ARRAY: u32 = TypeId::FLOAT16_ARRAY as u32;
pub const BFLOAT16_ARRAY: u32 = TypeId::BFLOAT16_ARRAY as u32;
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

pub static BASIC_TYPES: [TypeId; 34] = [
    TypeId::BOOL,
    TypeId::INT8,
    TypeId::INT16,
    TypeId::INT32,
    TypeId::INT64,
    TypeId::UINT8,
    TypeId::UINT16,
    TypeId::UINT32,
    TypeId::UINT64,
    TypeId::FLOAT16,
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

pub static PRIMITIVE_TYPES: [u32; 24] = [
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
    TypeId::FLOAT8 as u32,
    TypeId::FLOAT16 as u32,
    TypeId::BFLOAT16 as u32,
    TypeId::FLOAT32 as u32,
    TypeId::FLOAT64 as u32,
    // Rust-specific
    TypeId::U128 as u32,
    TypeId::INT128 as u32,
    TypeId::USIZE as u32,
    TypeId::ISIZE as u32,
];

pub static PRIMITIVE_ARRAY_TYPES: [u32; 19] = [
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
    TypeId::FLOAT8_ARRAY as u32,
    TypeId::FLOAT16_ARRAY as u32,
    TypeId::BFLOAT16_ARRAY as u32,
    TypeId::FLOAT32_ARRAY as u32,
    TypeId::FLOAT64_ARRAY as u32,
    // Rust-specific
    TypeId::U128_ARRAY as u32,
    TypeId::INT128_ARRAY as u32,
    TypeId::USIZE_ARRAY as u32,
    TypeId::ISIZE_ARRAY as u32,
];
pub static BASIC_TYPE_NAMES: [&str; 19] = [
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
    "float16",
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
    ("float16", TypeId::FLOAT16_ARRAY as u32, "Vec<float16>"),
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
            | TypeId::FLOAT8
            | TypeId::FLOAT16
            | TypeId::BFLOAT16
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

#[inline(always)]
pub const fn needs_user_type_id(type_id: u32) -> bool {
    matches!(
        type_id,
        ENUM | STRUCT | COMPATIBLE_STRUCT | EXT | TYPED_UNION
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
pub enum Mode {
    // Type declaration must be consistent between serialization peer and deserialization peer.
    SchemaConsistent,
    // Type declaration can be different between serialization peer and deserialization peer.
    // They can add/delete fields independently.
    Compatible,
}

// every object start with i8 i16 reference flag and type flag
pub const SIZE_OF_REF_AND_TYPE: usize = mem::size_of::<i8>() + mem::size_of::<i16>();

/// Formats a type ID into a human-readable string.
///
/// Type IDs are internal Fory type IDs in the range 0..=255.
pub fn format_type_id(type_id: u32) -> String {
    let type_name = match type_id {
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
        16 => "FLOAT8",
        17 => "FLOAT16",
        18 => "BFLOAT16",
        19 => "FLOAT32",
        20 => "FLOAT64",
        21 => "STRING",
        22 => "LIST",
        23 => "SET",
        24 => "MAP",
        25 => "ENUM",
        26 => "NAMED_ENUM",
        27 => "STRUCT",
        28 => "COMPATIBLE_STRUCT",
        29 => "NAMED_STRUCT",
        30 => "NAMED_COMPATIBLE_STRUCT",
        31 => "EXT",
        32 => "NAMED_EXT",
        33 => "UNION",
        34 => "TYPED_UNION",
        35 => "NAMED_UNION",
        36 => "NONE",
        37 => "DURATION",
        38 => "TIMESTAMP",
        39 => "DATE",
        40 => "DECIMAL",
        41 => "BINARY",
        42 => "ARRAY",
        43 => "BOOL_ARRAY",
        44 => "INT8_ARRAY",
        45 => "INT16_ARRAY",
        46 => "INT32_ARRAY",
        47 => "INT64_ARRAY",
        48 => "UINT8_ARRAY",
        49 => "UINT16_ARRAY",
        50 => "UINT32_ARRAY",
        51 => "UINT64_ARRAY",
        52 => "FLOAT8_ARRAY",
        53 => "FLOAT16_ARRAY",
        54 => "BFLOAT16_ARRAY",
        55 => "FLOAT32_ARRAY",
        56 => "FLOAT64_ARRAY",
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

    type_name.to_string()
}

/// Returns the internal type ID for extension types.
pub fn get_ext_actual_type_id(_type_id: u32, register_by_name: bool) -> u32 {
    if register_by_name {
        TypeId::NAMED_EXT as u32
    } else {
        TypeId::EXT as u32
    }
}
