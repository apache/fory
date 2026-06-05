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

use fory_core::fory::Fory;
use fory_core::{Decimal, Error, RefFlag};
use fory_derive::ForyStruct;
use num_bigint::BigInt;

fn compatible_fory<T>(type_id: u32) -> Fory
where
    T: fory_core::Serializer + fory_core::StructSerializer + fory_core::ForyDefault,
{
    let mut fory = Fory::builder().xlang(false).compatible(true).build();
    fory.register::<T>(type_id).unwrap();
    fory
}

fn convert<W, R>(type_id: u32, value: &W) -> Result<R, Error>
where
    W: fory_core::Serializer + fory_core::StructSerializer + fory_core::ForyDefault,
    R: fory_core::Serializer + fory_core::StructSerializer + fory_core::ForyDefault,
{
    let writer = compatible_fory::<W>(type_id);
    let reader = compatible_fory::<R>(type_id);
    let bytes = writer.serialize(value)?;
    reader.deserialize(&bytes)
}

#[test]
fn bool_string() {
    #[derive(ForyStruct, Debug)]
    struct StringFlag {
        flag: String,
    }

    #[derive(ForyStruct, Debug)]
    struct BoolFlag {
        flag: bool,
    }

    let decoded: BoolFlag = convert(
        12_001,
        &StringFlag {
            flag: "true".to_string(),
        },
    )
    .unwrap();
    assert!(decoded.flag);

    let decoded: StringFlag = convert(12_002, &BoolFlag { flag: false }).unwrap();
    assert_eq!(decoded.flag, "false");
}

#[test]
fn bool_number() {
    #[derive(ForyStruct, Debug)]
    struct NumberFlag {
        flag: i32,
    }

    #[derive(ForyStruct, Debug)]
    struct BoolFlag {
        flag: bool,
    }

    #[derive(ForyStruct, Debug)]
    struct UnsignedFlag {
        flag: u64,
    }

    let decoded: BoolFlag = convert(12_011, &NumberFlag { flag: 1 }).unwrap();
    assert!(decoded.flag);

    let decoded: UnsignedFlag = convert(12_012, &BoolFlag { flag: true }).unwrap();
    assert_eq!(decoded.flag, 1);

    let err = convert::<NumberFlag, BoolFlag>(12_013, &NumberFlag { flag: 2 }).unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
}

#[test]
fn number_number() {
    #[derive(ForyStruct, Debug)]
    struct Wide {
        value: i16,
    }

    #[derive(ForyStruct, Debug)]
    struct Narrow {
        value: i8,
    }

    #[derive(ForyStruct, Debug)]
    struct UnsignedWide {
        value: u64,
    }

    #[derive(ForyStruct, Debug)]
    struct SignedWide {
        value: i64,
    }

    let decoded: Narrow = convert(12_021, &Wide { value: 127 }).unwrap();
    assert_eq!(decoded.value, 127);

    let err =
        convert::<UnsignedWide, SignedWide>(12_022, &UnsignedWide { value: u64::MAX }).unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
}

#[test]
fn number_string() {
    #[derive(ForyStruct, Debug)]
    struct TextValue {
        value: String,
    }

    #[derive(ForyStruct, Debug)]
    struct IntValue {
        value: i32,
    }

    #[derive(ForyStruct, Debug)]
    struct FloatValue {
        value: f32,
    }

    #[derive(ForyStruct, Debug)]
    struct DecimalValue {
        value: Decimal,
    }

    let decoded: IntValue = convert(
        12_031,
        &TextValue {
            value: "123".to_string(),
        },
    )
    .unwrap();
    assert_eq!(decoded.value, 123);

    let decoded: FloatValue = convert(
        12_032,
        &TextValue {
            value: "-0.0".to_string(),
        },
    )
    .unwrap();
    assert_eq!(decoded.value.to_bits(), (-0.0f32).to_bits());

    let decoded: TextValue = convert(12_033, &FloatValue { value: 0.5 }).unwrap();
    assert_eq!(decoded.value, "0.5");

    let err = convert::<TextValue, IntValue>(
        12_034,
        &TextValue {
            value: "01".to_string(),
        },
    )
    .unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
    assert!(
        err.to_string().contains("compatible field 'value'"),
        "{err}"
    );

    for value in [
        "1e1000000".to_string(),
        "1e256".to_string(),
        "1".repeat(257),
        format!("0.{}", "0".repeat(319)),
    ] {
        let err = convert::<TextValue, DecimalValue>(
            12_036,
            &TextValue {
                value: value.clone(),
            },
        )
        .unwrap_err();
        assert!(matches!(err, Error::InvalidData(_)), "{err}");
        assert!(
            err.to_string().contains("compatible field 'value'"),
            "{err}"
        );
    }

    let err = convert::<FloatValue, TextValue>(
        12_035,
        &FloatValue {
            value: f32::INFINITY,
        },
    )
    .unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
}

#[test]
fn decimal_conversion() {
    #[derive(ForyStruct, Debug)]
    struct DecimalValue {
        value: Decimal,
    }

    #[derive(ForyStruct, Debug)]
    struct TextValue {
        value: String,
    }

    #[derive(ForyStruct, Debug)]
    struct IntValue {
        value: i32,
    }

    let decoded: TextValue = convert(
        12_041,
        &DecimalValue {
            value: Decimal::new(BigInt::from(125), 2),
        },
    )
    .unwrap();
    assert_eq!(decoded.value, "1.25");

    let decoded: DecimalValue = convert(
        12_042,
        &TextValue {
            value: "1.25".to_string(),
        },
    )
    .unwrap();
    assert_eq!(decoded.value, Decimal::new(BigInt::from(125), 2));

    let decoded: IntValue = convert(
        12_043,
        &DecimalValue {
            value: Decimal::new(BigInt::from(120), 1),
        },
    )
    .unwrap();
    assert_eq!(decoded.value, 12);
}

#[test]
fn decimal_guardrails() {
    #[derive(ForyStruct, Debug)]
    struct DecimalValue {
        value: Decimal,
    }

    #[derive(ForyStruct, Debug)]
    struct TextValue {
        value: String,
    }

    let digits_256 = "1".repeat(256);
    let decoded: DecimalValue = convert(
        12_071,
        &TextValue {
            value: digits_256.clone(),
        },
    )
    .unwrap();
    assert_eq!(decoded.value.unscaled.to_string(), digits_256);
    assert_eq!(decoded.value.scale, 0);

    let decoded: DecimalValue = convert(
        12_072,
        &TextValue {
            value: "1e255".to_string(),
        },
    )
    .unwrap();
    assert_eq!(decoded.value.unscaled.to_string().len(), 256);
    assert_eq!(decoded.value.scale, 0);

    let invalid_texts = [
        "1".repeat(257),
        format!("0.{}", "0".repeat(319)),
        "1e1000000".to_string(),
        "1e256".to_string(),
    ];
    for (index, value) in invalid_texts.into_iter().enumerate() {
        let err = convert::<TextValue, DecimalValue>(12_073 + index as u32, &TextValue { value })
            .unwrap_err();
        assert!(matches!(err, Error::InvalidData(_)), "{err}");
    }

    let err = convert::<DecimalValue, TextValue>(
        12_078,
        &DecimalValue {
            value: Decimal::new(BigInt::from(1), -256),
        },
    )
    .unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
}

#[test]
fn option_composition() {
    #[derive(ForyStruct, Debug)]
    struct OptionalText {
        value: Option<String>,
    }

    #[derive(ForyStruct, Debug)]
    struct BoolValue {
        value: bool,
    }

    #[derive(ForyStruct, Debug)]
    struct OptionalBool {
        value: Option<bool>,
    }

    let decoded: BoolValue = convert(
        12_051,
        &OptionalText {
            value: Some("1".to_string()),
        },
    )
    .unwrap();
    assert!(decoded.value);

    let decoded: BoolValue = convert(12_052, &OptionalText { value: None }).unwrap();
    assert!(!decoded.value);

    let decoded: OptionalBool = convert(
        12_053,
        &OptionalText {
            value: Some("false".to_string()),
        },
    )
    .unwrap();
    assert_eq!(decoded.value, Some(false));

    let writer = compatible_fory::<OptionalText>(12_054);
    let reader = compatible_fory::<BoolValue>(12_054);
    let mut bytes = writer
        .serialize(&OptionalText {
            value: Some("1".to_string()),
        })
        .unwrap();
    let flag = bytes
        .iter()
        .rposition(|byte| *byte == RefFlag::NotNullValue as i8 as u8)
        .unwrap();
    bytes[flag] = RefFlag::RefValue as i8 as u8;
    let err = reader.deserialize::<BoolValue>(&bytes).unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");

    let writer = compatible_fory::<OptionalBool>(12_055);
    let reader = compatible_fory::<BoolValue>(12_055);
    let mut bytes = writer
        .serialize(&OptionalBool { value: Some(true) })
        .unwrap();
    let flag = bytes
        .iter()
        .rposition(|byte| *byte == RefFlag::NotNullValue as i8 as u8)
        .unwrap();
    bytes[flag] = RefFlag::RefValue as i8 as u8;
    let err = reader.deserialize::<BoolValue>(&bytes).unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");

    let mut bytes = writer
        .serialize(&OptionalBool { value: Some(true) })
        .unwrap();
    let last = bytes.len() - 1;
    bytes[last] = 2;
    let err = reader.deserialize::<BoolValue>(&bytes).unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
}

#[test]
fn same_schema_string_preserved() {
    #[derive(ForyStruct, Debug)]
    struct TextValue {
        value: String,
    }

    #[derive(ForyStruct, Debug)]
    struct BoolValue {
        value: bool,
    }

    let decoded: TextValue = convert(
        12_061,
        &TextValue {
            value: "not a bool".to_string(),
        },
    )
    .unwrap();
    assert_eq!(decoded.value, "not a bool");

    let err = convert::<TextValue, BoolValue>(
        12_062,
        &TextValue {
            value: "not a bool".to_string(),
        },
    )
    .unwrap_err();
    assert!(matches!(err, Error::InvalidData(_)), "{err}");
}
