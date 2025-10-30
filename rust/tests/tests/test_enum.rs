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
    }

    let mut fory = Fory::default().xlang(false);
    fory.register::<Token>(1000).unwrap();
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
    ];
    let bin = fory.serialize(&tokens).unwrap();
    let new_tokens = fory.deserialize::<Vec<Token>>(&bin).unwrap();
    assert_eq!(tokens, new_tokens);
}
