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

use fory_core::buffer::{Reader, Writer};
use fory_core::Fory;

#[test]
fn test_var_i32() {
    let test_data: Vec<i32> = vec![
        // 1 byte(0..127)
        0,
        1,
        127,
        // 2 byte(128..16_383)
        128,
        300,
        16_383,
        // 3 byte(16_384..2_097_151)
        16_384,
        20_000,
        2_097_151,
        // 4 byte(2_097_152..268_435_455)
        2_097_152,
        100_000_000,
        268_435_455,
        // 5 byte(268_435_456..i32::MAX)
        268_435_456,
        i32::MAX,
    ];
    for &data in &test_data {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        writer.write_var_i32(data);
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let res = reader.read_var_i32().unwrap();
        assert_eq!(res, data);
    }
    for &data in &test_data {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        writer.write_var_u32(data as u32);
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let res = reader.read_var_u32().unwrap();
        assert_eq!(res, data as u32);
    }
}

#[test]
fn test_var_u36_small() {
    let cases: &[(u64, &[u8])] = &[
        (0, &[0x00]),
        (127, &[0x7f]),
        (128, &[0x80, 0x01]),
        (16_383, &[0xff, 0x7f]),
        (16_384, &[0x80, 0x80, 0x01]),
        (2_097_151, &[0xff, 0xff, 0x7f]),
        (2_097_152, &[0x80, 0x80, 0x80, 0x01]),
        (268_435_455, &[0xff, 0xff, 0xff, 0x7f]),
        (268_435_456, &[0x80, 0x80, 0x80, 0x80, 0x01]),
        ((1_u64 << 35) - 1, &[0xff, 0xff, 0xff, 0xff, 0x7f]),
        (1_u64 << 35, &[0x80, 0x80, 0x80, 0x80, 0x80, 0x01]),
        ((1_u64 << 36) - 1, &[0xff, 0xff, 0xff, 0xff, 0xff, 0x01]),
    ];

    for &(value, expected) in cases {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        writer.write_var_u36_small(value);
        assert_eq!(writer.dump(), expected, "wrong wire bytes for {value}");

        let mut short_reader = Reader::new(expected);
        assert_eq!(short_reader.read_var_u36_small().unwrap(), value);
        assert_eq!(short_reader.get_cursor(), expected.len());

        let mut framed = expected.to_vec();
        framed.push(0x2a);
        framed.extend_from_slice(&[0; 8]);
        let mut long_reader = Reader::new(&framed);
        assert_eq!(long_reader.read_var_u36_small().unwrap(), value);
        assert_eq!(long_reader.read_u8().unwrap(), 0x2a);
    }
}

#[test]
fn test_var_u36_bad_framing() {
    for len in 1..=5 {
        let bytes = vec![0x80; len];
        let mut reader = Reader::new(&bytes);
        assert!(reader.read_var_u36_small().is_err());
    }

    for bytes in [
        [0x80, 0x80, 0x80, 0x80, 0x80, 0x02],
        [0x80, 0x80, 0x80, 0x80, 0x80, 0x80],
    ] {
        let mut reader = Reader::new(&bytes);
        assert!(reader.read_var_u36_small().is_err());
    }

    let padded = [0x80, 0x80, 0x80, 0x80, 0x80, 0x02, 0x2a, 0x2b];
    let mut reader = Reader::new(&padded);
    assert!(reader.read_var_u36_small().is_err());
    assert_eq!(reader.get_cursor(), 0);
}

#[test]
fn test_fixed_width_read_bounds_checks() {
    let mut empty = Reader::new(&[]);
    assert!(empty.read_u16().is_err());
    assert!(empty.read_u32().is_err());
    assert!(empty.read_u64().is_err());
    assert!(empty.read_f16().is_err());
    assert!(empty.read_f32().is_err());
    assert!(empty.read_f64().is_err());
    assert!(empty.read_u128().is_err());

    let mut short = Reader::new(&[1, 2, 3]);
    assert!(short.read_u32().is_err());

    let mut bad_cursor = Reader::new(&[1, 2, 3, 4]);
    bad_cursor.set_cursor(10);
    assert!(bad_cursor.read_u16().is_err());
    assert!(bad_cursor.read_var_u36_small().is_err());
}

#[test]
fn test_utf8_string_read_rejects_invalid_payload() {
    let mut reader = Reader::new(&[0xff]);
    let err = reader.read_utf8_string(1).unwrap_err();
    assert!(
        err.to_string().contains("invalid UTF-8 string"),
        "unexpected error: {err}"
    );
    assert_eq!(reader.get_cursor(), 0);
}

#[test]
fn test_fory_rejects_invalid_utf8_string_by_default() {
    let fory = Fory::builder().xlang(false).compatible(false).build();
    assert!(fory.is_check_string_read());
    let mut bytes = fory.serialize(&"a".to_string()).unwrap();
    *bytes.last_mut().unwrap() = 0xff;

    let err = fory.deserialize::<String>(&bytes).unwrap_err();
    assert!(
        err.to_string().contains("invalid UTF-8 string"),
        "unexpected error: {err}"
    );
}

#[test]
fn test_fory_can_disable_checked_string_read_for_trusted_data() {
    let fory = Fory::builder()
        .xlang(false)
        .check_string_read(false)
        .compatible(false)
        .build();
    assert!(!fory.is_check_string_read());

    let bytes = fory.serialize(&"valid".to_string()).unwrap();
    let value = fory.deserialize::<String>(&bytes).unwrap();
    assert_eq!(value, "valid");
}
