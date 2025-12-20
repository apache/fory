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

// RUSTFLAGS="-Awarnings" cargo expand -p tests --test test_enum

use fory_core::Fory;
use fory_derive::ForyObject;
use std::collections::HashMap;

#[test]
fn basic() {
    #[derive(ForyObject, Debug, PartialEq)]
    enum Token {
        Plus,
        Number(i64),
        Ident(String),
        Assign { target: String, value: i32 },
        Other(Option<i64>),
        Child(Box<Token>),
        Map(HashMap<String, Token>),
    }

    let mut fory = Fory::default().xlang(false);
    fory.register::<Token>(1000).unwrap();

    let mut map = HashMap::new();
    map.insert("one".to_string(), Token::Number(1));
    map.insert("plus".to_string(), Token::Plus);
    map.insert(
        "nested".to_string(),
        Token::Child(Box::new(Token::Ident("deep".to_string()))),
    );

    let tokens = vec![
        Token::Plus,
        Token::Number(1),
        Token::Ident("foo".to_string()),
        Token::Assign {
            target: "bar".to_string(),
            value: 42,
        },
        Token::Other(Some(42)),
        Token::Other(None),
        Token::Child(Box::from(Token::Child(Box::from(Token::Other(None))))),
        Token::Map(map),
    ];
    let bin = fory.serialize(&tokens).unwrap();
    let new_tokens = fory.deserialize::<Vec<Token>>(&bin).unwrap();
    assert_eq!(tokens, new_tokens);
}

#[test]
fn named_enum() {
    #[derive(ForyObject, Debug, PartialEq)]
    enum Token1 {
        Assign { target: String, value: i32 },
    }

    #[derive(ForyObject, Debug, PartialEq)]
    enum Token2 {
        Assign { value: i32, target: String },
    }

    let mut fory1 = Fory::default().xlang(false);
    fory1.register::<Token1>(1000).unwrap();

    let mut fory2 = Fory::default().xlang(false);
    fory2.register::<Token2>(1000).unwrap();

    let token = Token1::Assign {
        target: "bar".to_string(),
        value: 42,
    };
    let bin = fory1.serialize(&token).unwrap();
    let new_token = fory2.deserialize::<Token2>(&bin).unwrap();

    let Token1::Assign {
        target: target1,
        value: value1,
    } = token;
    let Token2::Assign {
        target: target2,
        value: value2,
    } = new_token;
    assert_eq!(target1, target2);
    assert_eq!(value1, value2);
}

/// Test that simple enum (all unit variants) uses ENUM type ID in xlang mode
/// while tagged enum (some variants with data) uses UNION type ID
#[test]
fn xlang_simple_enum_uses_enum_type_id() {
    use fory_core::buffer::Reader;
    use fory_core::types::TypeId;

    #[derive(ForyObject, Debug, PartialEq, Default)]
    enum SimpleEnum {
        #[default]
        A,
        B,
        C,
    }

    let mut fory = Fory::default().xlang(true);
    fory.register::<SimpleEnum>(1000).unwrap();

    let value = SimpleEnum::B;
    let bytes = fory.serialize(&value).unwrap();

    // Fory header:
    // - 2 bytes: magic number (0x62d4)
    // - 1 byte: bitmap flags
    // - 1 byte: language
    // Total header: 4 bytes

    let mut reader = Reader::new(&bytes);

    // Read magic number
    let _magic = reader.read_u16().unwrap();

    // Read bitmap
    let _bitmap = reader.read_u8().unwrap();

    // Read language
    let _language = reader.read_u8().unwrap();

    // Read ref flag (-1 = NotNullValue)
    let ref_flag = reader.read_i8().unwrap();
    assert_eq!(ref_flag, -1, "Expected NotNullValue ref flag");

    // Read type id - should be ENUM for simple enum in xlang mode
    // The type ID format is: (registered_type_id << 8) + TypeId::ENUM
    let type_id = reader.read_varuint32().unwrap();
    let base_type_id = type_id & 0xff;
    assert_eq!(
        base_type_id,
        TypeId::ENUM as u32,
        "Expected ENUM type id ({}) in xlang mode for simple enum, got {}",
        TypeId::ENUM as u32,
        base_type_id
    );

    // Read variant index (B = 1)
    let variant_index = reader.read_varuint32().unwrap();
    assert_eq!(
        variant_index, 1,
        "Expected variant index 1 for SimpleEnum::B"
    );

    // Verify roundtrip still works
    let deserialized: SimpleEnum = fory.deserialize(&bytes).unwrap();
    assert_eq!(deserialized, value);
}

/// Test that tagged enum (has variants with data) uses UNION type ID in xlang mode
#[test]
fn xlang_tagged_enum_uses_union_type_id() {
    use fory_core::buffer::Reader;
    use fory_core::types::TypeId;

    #[derive(ForyObject, Debug, PartialEq, Default)]
    enum TaggedEnum {
        #[default]
        Empty,
        Value(i32),
        Name(String),
    }

    let mut fory = Fory::default().xlang(true);
    fory.register::<TaggedEnum>(1001).unwrap();

    let value = TaggedEnum::Empty;
    let bytes = fory.serialize(&value).unwrap();

    let mut reader = Reader::new(&bytes);

    // Skip header
    let _magic = reader.read_u16().unwrap();
    let _bitmap = reader.read_u8().unwrap();
    let _language = reader.read_u8().unwrap();

    // Read ref flag
    let _ref_flag = reader.read_i8().unwrap();

    // Read type id - should be UNION (38) in xlang mode for tagged enum
    let type_id = reader.read_varuint32().unwrap();
    assert_eq!(
        type_id,
        TypeId::UNION as u32,
        "Expected UNION type id ({}) in xlang mode for tagged enum, got {}",
        TypeId::UNION as u32,
        type_id
    );

    // Verify roundtrip still works
    let deserialized: TaggedEnum = fory.deserialize(&bytes).unwrap();
    assert_eq!(deserialized, value);
}

/// Test xlang roundtrip with tagged enum (enum with data variants)
/// Note: This test only tests unit variants of a tagged enum.
/// Testing data variants in xlang mode is a separate concern.
#[test]
fn xlang_complex_tagged_enum_roundtrip() {
    #[derive(ForyObject, Debug, PartialEq, Default)]
    enum TaggedColor {
        #[default]
        Red,
        Green,
        Blue,
        Value(i32),
    }

    let mut fory = Fory::default().xlang(true);
    fory.register::<TaggedColor>(1002).unwrap();

    // Test unit variants - these should work even though the enum is tagged
    let colors = vec![TaggedColor::Red, TaggedColor::Green, TaggedColor::Blue];

    for color in colors {
        let bytes = fory.serialize(&color).unwrap();
        let deserialized: TaggedColor = fory.deserialize(&bytes).unwrap();
        assert_eq!(deserialized, color);
    }
}
