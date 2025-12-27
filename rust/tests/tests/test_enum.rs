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

/// Test that struct with enum field serializes correctly.
#[test]
fn struct_with_enum_field() {
    use fory_core::serializer::Serializer;
    use fory_core::types::TypeId;

    // Define a simple enum
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    enum Color {
        Red,
        Green,
        Blue,
    }

    // Define a struct with enum field
    #[derive(ForyObject, Debug, PartialEq)]
    struct StructWithEnum {
        name: String,
        color: Color,
        value: i32,
    }

    // Verify Color is recognized as ENUM TypeId
    assert!(
        matches!(
            Color::fory_static_type_id(),
            TypeId::ENUM | TypeId::NAMED_ENUM
        ),
        "Color should have ENUM TypeId, got {:?}",
        Color::fory_static_type_id()
    );

    let mut fory = Fory::default().xlang(true).compatible(false);
    fory.register::<Color>(100).unwrap();
    fory.register::<StructWithEnum>(101).unwrap();

    let obj = StructWithEnum {
        name: "test".to_string(),
        color: Color::Green,
        value: 42,
    };

    let bin = fory.serialize(&obj).unwrap();
    let result: StructWithEnum = fory.deserialize(&bin).unwrap();
    assert_eq!(obj, result);
}

/// Test Union-compatible enum xlang serialization format.
/// This verifies that Rust enum writes: index + ref_flag + type_id + data
/// which should be compatible with Java's Union: index + xwriteRef(value)
#[test]
fn union_compatible_enum_xlang_format() {
    use fory_core::serializer::Serializer;
    use fory_core::types::TypeId;

    // Define a Union-compatible enum (each variant has exactly one field)
    #[derive(ForyObject, Debug, PartialEq, Clone)]
    enum StringOrLong {
        Text(String),
        Number(i64),
    }

    // Verify it's recognized as UNION TypeId
    assert_eq!(
        StringOrLong::fory_static_type_id(),
        TypeId::UNION,
        "Union-compatible enum should have UNION TypeId"
    );

    // Struct containing the Union-compatible enum
    #[derive(ForyObject, Debug, PartialEq)]
    struct StructWithUnion {
        union_field: StringOrLong,
    }

    // Test xlang mode serialization
    let mut fory = Fory::default().xlang(true).compatible(false);
    fory.register::<StringOrLong>(300).unwrap();
    fory.register::<StructWithUnion>(301).unwrap();

    // Test with String variant (index 0)
    let obj1 = StructWithUnion {
        union_field: StringOrLong::Text("hello".to_string()),
    };
    let bin1 = fory.serialize(&obj1).unwrap();
    let result1: StructWithUnion = fory.deserialize(&bin1).unwrap();
    assert_eq!(obj1, result1);

    // Test with Long variant (index 1)
    let obj2 = StructWithUnion {
        union_field: StringOrLong::Number(42),
    };
    let bin2 = fory.serialize(&obj2).unwrap();
    let result2: StructWithUnion = fory.deserialize(&bin2).unwrap();
    assert_eq!(obj2, result2);
}

/// Test explicit #[fory(nullable)] attribute on enum field
#[test]
fn struct_with_enum_field_explicit_nullable() {
    use fory_core::serializer::Serializer;
    use fory_core::types::TypeId;

    #[derive(ForyObject, Debug, PartialEq, Clone)]
    enum Status {
        Active,
        Inactive,
    }

    #[derive(ForyObject, Debug, PartialEq)]
    struct StructWithExplicitNullable {
        name: String,
        #[fory(id = 0, nullable = true)]
        status: Status,
    }

    assert!(
        matches!(
            Status::fory_static_type_id(),
            TypeId::ENUM | TypeId::NAMED_ENUM
        ),
        "Status should have ENUM TypeId"
    );

    let mut fory = Fory::default().xlang(true).compatible(false);
    fory.register::<Status>(200).unwrap();
    fory.register::<StructWithExplicitNullable>(201).unwrap();

    let obj = StructWithExplicitNullable {
        name: "explicit".to_string(),
        status: Status::Active,
    };

    let bin = fory.serialize(&obj).unwrap();
    let result: StructWithExplicitNullable = fory.deserialize(&bin).unwrap();
    assert_eq!(obj, result);
}
