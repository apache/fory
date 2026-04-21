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

use fory_core::buffer::Reader;
use fory_core::type_id::config_flags::IS_CROSS_LANGUAGE_FLAG;
use fory_core::{Decimal, Fory, RefFlag, TypeId};
use num_bigint::BigInt;

fn decimal(unscaled: &str, scale: i32) -> Decimal {
    Decimal::new(
        BigInt::parse_bytes(unscaled.as_bytes(), 10).expect("invalid decimal test value"),
        scale,
    )
}

#[test]
fn test_decimal_round_trip() {
    let fory = Fory::builder().xlang(true).build();
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
    let fory = Fory::builder().xlang(true).build();
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
    let fory = Fory::builder().xlang(true).build();

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
