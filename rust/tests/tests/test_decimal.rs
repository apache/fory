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
use fory_core::type_id::config_flags::IS_CROSS_LANGUAGE_FLAG;
use fory_core::{Decimal, Fory, RefFlag, TypeId};
use num_bigint::{BigInt, Sign};

const MAX_DECIMAL_MAGNITUDE_BYTES: usize = 10_000;
const MAX_DECIMAL_SCALE: i32 = 10_000;

fn decimal(unscaled: &str, scale: i32) -> Decimal {
    Decimal::new(
        BigInt::parse_bytes(unscaled.as_bytes(), 10).expect("invalid decimal test value"),
        scale,
    )
}

fn magnitude_bytes(len: usize) -> Vec<u8> {
    let mut bytes = vec![0; len];
    bytes[len - 1] = 1;
    bytes
}

fn decimal_payload(scale: i32, magnitude: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::new();
    let mut writer = Writer::from_buffer(&mut bytes);
    writer.write_u8(IS_CROSS_LANGUAGE_FLAG);
    writer.write_i8(RefFlag::NotNullValue as i8);
    writer.write_var_u32(TypeId::DECIMAL as u32);
    writer.write_var_i32(scale);
    let meta = (magnitude.len() as u64) << 1;
    writer.write_var_u64((meta << 1) | 1);
    writer.write_bytes(magnitude);
    bytes
}

#[test]
fn test_decimal_round_trip() {
    let fory = Fory::builder().xlang(true).compatible(false).build();
    let values = vec![
        Decimal::new(BigInt::from(0), 0),
        Decimal::new(BigInt::from(0), 3),
        Decimal::new(BigInt::from(1), 0),
        Decimal::new(BigInt::from(-1), 0),
        Decimal::new(BigInt::from(12_345), 2),
        Decimal::new(BigInt::from(i64::MAX), 0),
        Decimal::new(BigInt::from(i64::MIN), 0),
        Decimal::new(BigInt::from(i64::MAX) + BigInt::from(1), 0),
        Decimal::new(BigInt::from(i64::MIN) - BigInt::from(1), 0),
        decimal("123456789012345678901234567890123456789", 37),
        decimal("-123456789012345678901234567890123456789", -17),
    ];

    for value in values {
        let bytes = fory.serialize(&value).unwrap();
        let decoded: Decimal = fory.deserialize(&bytes).unwrap();
        assert_eq!(value, decoded);
    }
}

#[test]
fn test_decimal_wire_format() {
    let fory = Fory::builder().xlang(true).compatible(false).build();
    let bytes = fory.serialize(&Decimal::new(BigInt::from(0), 2)).unwrap();
    let mut reader = Reader::new(bytes.as_slice());
    assert_eq!(reader.read_u8().unwrap(), IS_CROSS_LANGUAGE_FLAG);
    assert_eq!(reader.read_i8().unwrap(), RefFlag::NotNullValue as i8);
    assert_eq!(reader.read_var_u32().unwrap(), TypeId::DECIMAL as u32);
    assert_eq!(reader.read_var_i32().unwrap(), 2);
    assert_eq!(reader.read_var_u64().unwrap(), 0);

    let bytes = fory.serialize(&decimal("9223372036854775808", 0)).unwrap();
    let mut reader = Reader::new(bytes.as_slice());
    assert_eq!(reader.read_u8().unwrap(), IS_CROSS_LANGUAGE_FLAG);
    assert_eq!(reader.read_i8().unwrap(), RefFlag::NotNullValue as i8);
    assert_eq!(reader.read_var_u32().unwrap(), TypeId::DECIMAL as u32);
    assert_eq!(reader.read_var_i32().unwrap(), 0);
    assert_eq!(reader.read_var_u64().unwrap() & 1, 1);
}

#[test]
fn test_decimal_rejects_non_canonical_big_payload() {
    let fory = Fory::builder().xlang(true).compatible(false).build();

    let payload = vec![
        IS_CROSS_LANGUAGE_FLAG,
        RefFlag::NotNullValue as i8 as u8,
        TypeId::DECIMAL as u8,
        0x00,
        0x01,
    ];
    assert!(fory.deserialize::<Decimal>(&payload).is_err());

    let payload = vec![
        IS_CROSS_LANGUAGE_FLAG,
        RefFlag::NotNullValue as i8 as u8,
        TypeId::DECIMAL as u8,
        0x00,
        0x09,
        0x01,
        0x00,
    ];
    let err = fory.deserialize::<Decimal>(&payload).unwrap_err();
    assert!(err.to_string().contains("trailing zero byte"));
}

#[test]
fn test_decimal_scale_limits() {
    let fory = Fory::builder().xlang(true).compatible(false).build();

    for scale in [-MAX_DECIMAL_SCALE, MAX_DECIMAL_SCALE] {
        let value = Decimal::new(BigInt::from(1), scale);
        let bytes = fory.serialize(&value).unwrap();
        let decoded: Decimal = fory.deserialize(&bytes).unwrap();
        assert_eq!(decoded.scale, scale);
        assert_eq!(decoded.unscaled, BigInt::from(1));
    }

    for scale in [
        -MAX_DECIMAL_SCALE - 1,
        MAX_DECIMAL_SCALE + 1,
        i32::MIN,
        i32::MAX,
    ] {
        let value = Decimal::new(BigInt::from(1), scale);
        let err = fory.serialize(&value).unwrap_err();
        assert!(err.to_string().contains("decimal scale"));

        let payload = decimal_payload(scale, &[1]);
        let err = fory.deserialize::<Decimal>(&payload).unwrap_err();
        assert!(err.to_string().contains("decimal scale"));
    }
}

#[test]
fn test_decimal_magnitude_limits() {
    let fory = Fory::builder().xlang(true).compatible(false).build();

    let boundary_bytes = magnitude_bytes(MAX_DECIMAL_MAGNITUDE_BYTES);
    let boundary = Decimal::new(BigInt::from_bytes_le(Sign::Plus, &boundary_bytes), 0);
    let bytes = fory.serialize(&boundary).unwrap();
    let decoded: Decimal = fory.deserialize(&bytes).unwrap();
    assert_eq!(
        decoded.unscaled.bits(),
        ((MAX_DECIMAL_MAGNITUDE_BYTES - 1) * 8 + 1) as u64
    );

    let oversized_bytes = magnitude_bytes(MAX_DECIMAL_MAGNITUDE_BYTES + 1);
    let oversized = Decimal::new(BigInt::from_bytes_le(Sign::Plus, &oversized_bytes), 0);
    let err = fory.serialize(&oversized).unwrap_err();
    assert!(err.to_string().contains("decimal magnitude"));

    let payload = decimal_payload(0, &oversized_bytes);
    let err = fory.deserialize::<Decimal>(&payload).unwrap_err();
    assert!(err.to_string().contains("decimal magnitude length"));
}
