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

use fory_core::meta::is_latin;
use fory_core::util::to_utf8;

#[test]
fn test_to_utf8() {
    let s = "Hé€lo, 世界!😀";
    let is_little_endian = false;
    let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
    println!("==========init utf16:");
    let utf16_strings: Vec<String> = utf16_bytes
        .iter()
        .map(|&byte| format!("0x{byte:04x}"))
        .collect();
    println!("{}", utf16_strings.join(","));
    let utf8_bytes = to_utf8(&utf16_bytes, is_little_endian).unwrap();
    println!("==========utf8:");
    let utf8_strings: Vec<String> = utf8_bytes
        .iter()
        .map(|&byte| format!("0x{byte:02x}"))
        .collect();
    println!("{}", utf8_strings.join(","));
    // final UTF-8 string
    let final_string = String::from_utf8(utf8_bytes.clone()).unwrap();
    println!("final string: {final_string}");
    assert_eq!(s, final_string);
}

// For test
fn swap_endian(value: u16) -> u16 {
    ((value & 0xff) << 8) | ((value & 0xff00) >> 8)
}

#[test]
fn test_to_utf8_3byte() {
    let s = "é₫l₪₮";
    let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
    let utf8_bytes = to_utf8(&utf16_bytes, false).unwrap();
    assert_eq!(String::from_utf8(utf8_bytes.clone()).unwrap(), s);
    let utf16_bytes_le = s
        .encode_utf16()
        .collect::<Vec<u16>>()
        .iter()
        .map(|&byte| swap_endian(byte))
        .collect::<Vec<u16>>();
    let utf8_bytes_le = to_utf8(&utf16_bytes_le, true).unwrap();
    assert_eq!(String::from_utf8(utf8_bytes_le.clone()).unwrap(), s);
}

#[test]
fn test_to_utf8_endian() {
    let utf16 = &[0x6100, 0x6200]; // 'ab' in UTF-16 little endian
    let expected = b"ab";
    let result = to_utf8(utf16, true).unwrap();
    assert_eq!(result, expected, "Little endian test failed");
    let utf16 = &[0x0061, 0x0062]; // 'ab' in UTF-16 big endian
    let expected = b"ab";
    let result = to_utf8(utf16, false).unwrap();
    assert_eq!(result, expected, "Big endian test failed");
}

#[test]
fn test_to_utf8_surrogate_pair() {
    let s = "𝄞💡😀🎻";
    let utf16_bytes = s.encode_utf16().collect::<Vec<u16>>();
    let result_be = to_utf8(&utf16_bytes, false);
    assert!(result_be.is_ok());
    assert_eq!(String::from_utf8(result_be.unwrap().clone()).unwrap(), s);
    // test little endian
    let utf16_bytes_le = s
        .encode_utf16()
        .collect::<Vec<u16>>()
        .iter()
        .map(|&byte| swap_endian(byte))
        .collect::<Vec<u16>>();
    let result_le = to_utf8(&utf16_bytes_le, true);
    assert!(result_le.is_ok());
    assert_eq!(String::from_utf8(result_le.unwrap().clone()).unwrap(), s);
}

#[test]
fn test_to_utf8_missing_surrogate_pair() {
    let utf16 = &[0x00D8]; // Missing second surrogate
    let result = to_utf8(utf16, true);
    assert!(result.is_err());
    assert_eq!(
        result.unwrap_err(),
        "Invalid UTF-16 string: missing surrogate pair"
    );

    let utf16 = &[0x00D8, 0x00DA]; // Wrong second surrogate
    let result = to_utf8(utf16, true);
    assert!(result.is_err());
    assert_eq!(
        result.unwrap_err(),
        "Invalid UTF-16 string: wrong surrogate pair"
    );
}

#[test]
fn test_is_latin() {
    assert!(is_latin("Çüéâäàåçêëèïî"));
}
