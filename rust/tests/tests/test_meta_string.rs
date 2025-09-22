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

use std::iter;

use fory_core::meta::{
    Encoding, MetaStringDecoder, MetaStringEncoder, FIELD_NAME_DECODER, FIELD_NAME_ENCODER,
    NAMESPACE_DECODER, NAMESPACE_ENCODER, TYPE_NAME_DECODER, TYPE_NAME_ENCODER,
};

#[test]
fn test_encode_meta_string_lower_special() {
    let encoder = &TYPE_NAME_ENCODER;
    let bytes1 = encoder.encode_lower_special("abc_def").unwrap();
    assert_eq!(bytes1.len(), 5);
    let bytes2 = encoder
        .encode("org.apache.fory.benchmark.data")
        .unwrap()
        .bytes;
    assert_eq!(bytes2.len(), 19);
    let bytes3 = encoder.encode("MediaContent").unwrap().bytes;
    assert_eq!(bytes3.len(), 9);
    let decoder = &TYPE_NAME_DECODER;
    assert_eq!(
        decoder.decode(&bytes1, Encoding::LowerSpecial).unwrap(),
        "abc_def"
    );
    for i in 0..128 {
        let origin_string: String = iter::repeat_with(|| {
            let char_a = b'a';
            ((char_a + i as u8) % 26 + char_a) as char
        })
        .take(i)
        .collect();
        let encoded = encoder.encode_lower_special(&origin_string).unwrap();
        let decoded = decoder.decode(&encoded, Encoding::LowerSpecial).unwrap();
        assert_eq!(decoded, origin_string);
    }
}

fn create_string(length: usize, special_char1: char, special_char2: char) -> String {
    (0..length)
        .map(|j| {
            let n = j % 64;
            match n {
                0..=25 => (b'a' + n as u8) as char,
                26..=51 => (b'A' + (n - 26) as u8) as char,
                52..=61 => (b'0' + (n - 52) as u8) as char,
                62 => special_char1,
                63 => special_char2,
                _ => unreachable!(),
            }
        })
        .collect()
}

#[test]
fn test_encode_meta_string_lower_upper_digit_special() {
    let encoder = &NAMESPACE_ENCODER;
    let encoded = encoder
        .encode_lower_upper_digit_special("ExampleInput123")
        .unwrap();
    assert_eq!(encoded.len(), 12);

    let decoder = &NAMESPACE_DECODER;
    let decoded = decoder
        .decode(&encoded, Encoding::LowerUpperDigitSpecial)
        .unwrap();
    assert_eq!(decoded, "ExampleInput123");

    for i in 1..128 {
        let origin_string = create_string(
            i,
            NAMESPACE_ENCODER.special_char1,
            NAMESPACE_ENCODER.special_char2,
        );
        let encoded = encoder
            .encode_lower_upper_digit_special(&origin_string)
            .unwrap();
        let decoded = decoder
            .decode(&encoded, Encoding::LowerUpperDigitSpecial)
            .unwrap();
        assert_eq!(decoded, origin_string);
    }
}

#[test]
fn test_meta_string() {
    let special_chars = vec![('.', '_'), ('.', '$'), ('_', '$')];
    for (special_char1, special_char2) in special_chars {
        let encoder = MetaStringEncoder::new(special_char1, special_char2);
        for i in 1..=127 {
            let origin_string = create_string(i, special_char1, special_char2);
            let meta_string = encoder.encode(&origin_string).unwrap();
            assert_ne!(meta_string.encoding, Encoding::Utf8);
            assert_eq!(meta_string.original, origin_string);
            let decoder = MetaStringDecoder::new(special_char1, special_char2);
            let new_string = decoder
                .decode(&meta_string.bytes, meta_string.encoding)
                .unwrap();
            assert_eq!(new_string, origin_string);
        }
    }
}

#[test]
fn test_encode_empty_string() {
    let encoder = &TYPE_NAME_ENCODER;
    let decoder = &TYPE_NAME_DECODER;
    for encoding in [
        Encoding::LowerSpecial,
        Encoding::LowerUpperDigitSpecial,
        Encoding::FirstToLowerSpecial,
        Encoding::AllToLowerSpecial,
        Encoding::Utf8,
    ] {
        let meta_string = encoder.encode_with_encoding("", encoding).unwrap();
        assert_eq!(meta_string.bytes.len(), 0);
        let decoded = decoder
            .decode(&meta_string.bytes, meta_string.encoding)
            .unwrap();
        assert_eq!(decoded, "");
    }
}

#[test]
fn test_encode_characters_outside_of_lower_special() {
    let encoder = &TYPE_NAME_ENCODER;
    let test_string = "abcdefABCDEF1234!@#";
    let meta_string = encoder.encode(test_string).unwrap();
    assert_eq!(meta_string.encoding, Encoding::Utf8);
}

#[test]
fn test_all_to_upper_special_encoding() {
    let encoder = &TYPE_NAME_ENCODER;
    let decoder = &TYPE_NAME_DECODER;
    let test_string = "ABC_DEF";
    let meta_string = encoder.encode(test_string).unwrap();
    assert_eq!(meta_string.encoding, Encoding::LowerUpperDigitSpecial);
    let decoded_string = decoder
        .decode(&meta_string.bytes, meta_string.encoding)
        .unwrap();
    assert_eq!(decoded_string, test_string);
}

#[test]
fn test_first_to_lower_special_encoding() {
    let encoder = &TYPE_NAME_ENCODER;
    let decoder = &TYPE_NAME_DECODER;
    let test_string = "Aabcdef";
    let meta_string = encoder.encode(test_string).unwrap();
    assert_eq!(meta_string.encoding, Encoding::FirstToLowerSpecial);
    let decoded_string = decoder
        .decode(&meta_string.bytes, meta_string.encoding)
        .unwrap();
    assert_eq!(decoded_string, test_string);
}

#[test]
fn test_utf8_encoding() {
    let encoder = &NAMESPACE_ENCODER;
    let test_string = "你好，世界";
    let meta_string = encoder.encode(test_string).unwrap();
    assert_eq!(meta_string.encoding, Encoding::Utf8);
    let decoder = &TYPE_NAME_DECODER;
    let decoded_string = decoder
        .decode(&meta_string.bytes, meta_string.encoding)
        .unwrap();
    assert_eq!(decoded_string, test_string);
    let test_string = "aA$";
    let meta_string = encoder.encode(test_string).unwrap();
    assert_eq!(meta_string.encoding, Encoding::Utf8);
    let decoder = &TYPE_NAME_DECODER;
    let decoded_string = decoder
        .decode(&meta_string.bytes, meta_string.encoding)
        .unwrap();
    assert_eq!(decoded_string, test_string);
}

#[test]
fn test_strip_last_char() {
    let encoder = &TYPE_NAME_ENCODER;
    let test_string = "abc";
    let encoded_meta_string = encoder.encode(test_string).unwrap();
    assert!(!encoded_meta_string.strip_last_char);

    let test_string = "abcde";
    let encoded_meta_string = encoder.encode(test_string).unwrap();
    assert!(encoded_meta_string.strip_last_char);
}

#[test]
fn test_empty_string() {
    let encoder = &TYPE_NAME_ENCODER;
    let decoder = &TYPE_NAME_DECODER;
    let meta_string = encoder.encode("").unwrap();
    assert!(meta_string.bytes.is_empty());
    let decoded = decoder
        .decode(&meta_string.bytes, meta_string.encoding)
        .unwrap();
    assert_eq!(decoded, "");
}

#[test]
fn test_ascii_encoding() {
    let encoder = &TYPE_NAME_ENCODER;
    let test_string = "asciiOnly";
    let encoded_meta_string = encoder.encode(test_string).unwrap();
    assert_ne!(encoded_meta_string.encoding, Encoding::Utf8);
    assert_eq!(encoded_meta_string.encoding, Encoding::AllToLowerSpecial);
}

#[test]
fn test_non_ascii_encoding() {
    let encoder = &TYPE_NAME_ENCODER;
    let test_string = "こんにちは";
    let encoded_meta_string = encoder.encode(test_string).unwrap();
    assert_eq!(encoded_meta_string.encoding, Encoding::Utf8);
}

#[test]
fn test_non_ascii_encoding_and_non_utf8() {
    let encoder = &TYPE_NAME_ENCODER;
    let non_ascii_string = "こんにちは";

    match encoder.encode_with_encoding(non_ascii_string, Encoding::LowerSpecial) {
        Err(err) => {
            assert_eq!(
                err.to_string(),
                "Non-ASCII characters in meta string are not allowed"
            );
        }
        Ok(_) => panic!("Expected an error due to non-ASCII character with non-UTF8 encoding"),
    }
}
