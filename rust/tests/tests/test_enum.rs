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
