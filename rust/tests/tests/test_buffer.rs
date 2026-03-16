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

#[test]
fn test_varint32() {
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
        writer.write_varint32(data);
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let res = reader.read_varint32().unwrap();
        assert_eq!(res, data);
    }
    for &data in &test_data {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        writer.write_var_uint32(data as u32);
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let res = reader.read_varuint32().unwrap();
        assert_eq!(res, data as u32);
    }
}

#[test]
fn test_varuint36_small() {
    let test_data: Vec<u64> = vec![
        // 1 byte
        0,
        1,
        127,
        // 2 bytes
        128,
        300,
        16_383,
        // 3 bytes
        16_384,
        20_000,
        2_097_151,
        // 4 bytes
        2_097_152,
        100_000_000,
        268_435_455,
        // 5 bytes (36-bit max)
        268_435_456,
        1_000_000_000,
        68_719_476_735, // max 36-bit
    ];

    for &data in &test_data {
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        writer.write_var_uint36_small(data);
        let buf = writer.dump();

        let mut reader = Reader::new(buf.as_slice());
        let value = reader.read_varuint36small().unwrap();
        assert_eq!(value, data, "failed for data {}", data);
    }
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
    assert!(bad_cursor.read_varuint36small().is_err());
}
