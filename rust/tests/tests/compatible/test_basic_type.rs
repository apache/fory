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

use chrono::{NaiveDate, NaiveDateTime};
use fory_core::{fory::Fory, Reader};

// primitive_val
const BOOL_VAL: bool = true;
const I8_VAL: i8 = 42;
const I16_VAL: i16 = 43;
const I32_VAL: i32 = 44;
const I64_VAL: i64 = 45;
const F32_VAL: f32 = 46.0;
const F64_VAL: f64 = 47.0;
// string
const STR_LATIN1_VAL: &str = "Çüéâäàåçêëèïî";
// time
#[allow(deprecated)]
const TIMESTAMP_VAL: NaiveDateTime = NaiveDateTime::from_timestamp(100, 0);
#[allow(deprecated)]
const LOCAL_DATE_VAL: NaiveDate = NaiveDate::from_ymd(2021, 11, 23);

const BOOL_ARRAY: [bool; 1] = [true];
const INT8_ARRAY: [i8; 1] = [48];
const INT16_ARRAY: [i16; 1] = [49];
const INT32_ARRAY: [i32; 1] = [50];
const INT64_ARRAY: [i64; 1] = [51];
const FLOAT32_ARRAY: [f32; 1] = [52.0];
const FLOAT64_ARRAY: [f64; 1] = [53.0];

fn serialize_non_null(fory: &Fory) -> Vec<u8> {
    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &BOOL_VAL).unwrap();
    fory.serialize_to(&mut buf, &I8_VAL).unwrap();
    fory.serialize_to(&mut buf, &I16_VAL).unwrap();
    fory.serialize_to(&mut buf, &I32_VAL).unwrap();
    fory.serialize_to(&mut buf, &I64_VAL).unwrap();
    fory.serialize_to(&mut buf, &F32_VAL).unwrap();
    fory.serialize_to(&mut buf, &F64_VAL).unwrap();
    fory.serialize_to(&mut buf, &STR_LATIN1_VAL.to_string())
        .unwrap();
    fory.serialize_to(&mut buf, &LOCAL_DATE_VAL).unwrap();
    fory.serialize_to(&mut buf, &TIMESTAMP_VAL).unwrap();
    fory.serialize_to(&mut buf, &BOOL_ARRAY.to_vec()).unwrap();
    fory.serialize_to(&mut buf, &INT8_ARRAY.to_vec()).unwrap();
    fory.serialize_to(&mut buf, &INT16_ARRAY.to_vec()).unwrap();
    fory.serialize_to(&mut buf, &INT32_ARRAY.to_vec()).unwrap();
    fory.serialize_to(&mut buf, &INT64_ARRAY.to_vec()).unwrap();
    fory.serialize_to(&mut buf, &FLOAT32_ARRAY.to_vec())
        .unwrap();
    fory.serialize_to(&mut buf, &FLOAT64_ARRAY.to_vec())
        .unwrap();
    buf
}

fn serialize_nullable(fory: &Fory) -> Vec<u8> {
    let mut buf = Vec::new();
    fory.serialize_to(&mut buf, &Some(BOOL_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(I8_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(I16_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(I32_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(I64_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(F32_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(F64_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(STR_LATIN1_VAL.to_string()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(LOCAL_DATE_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(TIMESTAMP_VAL)).unwrap();
    fory.serialize_to(&mut buf, &Some(BOOL_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(INT8_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(INT16_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(INT32_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(INT64_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(FLOAT32_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Some(FLOAT64_ARRAY.to_vec()))
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<bool>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<i8>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<i16>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<i32>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<i64>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<f32>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<f64>::None).unwrap();
    fory.serialize_to(&mut buf, &Option::<String>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<NaiveDate>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<NaiveDateTime>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<bool>>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<i8>>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<i16>>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<i32>>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<i64>>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<f32>>::None)
        .unwrap();
    fory.serialize_to(&mut buf, &Option::<Vec<f64>>::None)
        .unwrap();
    buf
}

fn deserialize_non_null(fory: &Fory, bins: Vec<u8>, auto_conv: bool) {
    let mut reader = Reader::new(bins.as_slice());
    assert_eq!(
        BOOL_VAL,
        fory.deserialize_from::<bool>(&mut reader).unwrap()
    );
    assert_eq!(I8_VAL, fory.deserialize_from::<i8>(&mut reader).unwrap());
    assert_eq!(I16_VAL, fory.deserialize_from::<i16>(&mut reader).unwrap());
    assert_eq!(I32_VAL, fory.deserialize_from::<i32>(&mut reader).unwrap());
    assert_eq!(I64_VAL, fory.deserialize_from::<i64>(&mut reader).unwrap());
    assert_eq!(F32_VAL, fory.deserialize_from::<f32>(&mut reader).unwrap());
    assert_eq!(F64_VAL, fory.deserialize_from::<f64>(&mut reader).unwrap());
    assert_eq!(
        STR_LATIN1_VAL.to_string(),
        fory.deserialize_from::<String>(&mut reader).unwrap()
    );
    assert_eq!(
        LOCAL_DATE_VAL,
        fory.deserialize_from::<NaiveDate>(&mut reader).unwrap()
    );
    assert_eq!(
        TIMESTAMP_VAL,
        fory.deserialize_from::<NaiveDateTime>(&mut reader).unwrap()
    );

    assert_eq!(
        BOOL_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<bool>>(&mut reader).unwrap()
    );
    assert_eq!(
        INT8_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<i8>>(&mut reader).unwrap()
    );
    assert_eq!(
        INT16_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<i16>>(&mut reader).unwrap()
    );
    assert_eq!(
        INT32_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<i32>>(&mut reader).unwrap()
    );
    assert_eq!(
        INT64_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<i64>>(&mut reader).unwrap()
    );
    assert_eq!(
        FLOAT32_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<f32>>(&mut reader).unwrap()
    );
    assert_eq!(
        FLOAT64_ARRAY.to_vec(),
        fory.deserialize_from::<Vec<f64>>(&mut reader).unwrap()
    );
    if auto_conv {
        assert_eq!(
            bool::default(),
            fory.deserialize_from::<bool>(&mut reader).unwrap()
        );
        assert_eq!(
            i8::default(),
            fory.deserialize_from::<i8>(&mut reader).unwrap()
        );
        assert_eq!(
            i16::default(),
            fory.deserialize_from::<i16>(&mut reader).unwrap()
        );
        assert_eq!(
            i32::default(),
            fory.deserialize_from::<i32>(&mut reader).unwrap()
        );
        assert_eq!(
            i64::default(),
            fory.deserialize_from::<i64>(&mut reader).unwrap()
        );
        assert_eq!(
            f32::default(),
            fory.deserialize_from::<f32>(&mut reader).unwrap()
        );
        assert_eq!(
            f64::default(),
            fory.deserialize_from::<f64>(&mut reader).unwrap()
        );
        assert_eq!(
            String::default(),
            fory.deserialize_from::<String>(&mut reader).unwrap()
        );
        assert_eq!(
            NaiveDate::default(),
            fory.deserialize_from::<NaiveDate>(&mut reader).unwrap()
        );
        assert_eq!(
            NaiveDateTime::default(),
            fory.deserialize_from::<NaiveDateTime>(&mut reader).unwrap()
        );

        assert_eq!(
            Vec::<bool>::default(),
            fory.deserialize_from::<Vec<bool>>(&mut reader).unwrap()
        );
        assert_eq!(
            Vec::<i8>::default(),
            fory.deserialize_from::<Vec<i8>>(&mut reader).unwrap()
        );
        assert_eq!(
            Vec::<i16>::default(),
            fory.deserialize_from::<Vec<i16>>(&mut reader).unwrap()
        );
        assert_eq!(
            Vec::<i32>::default(),
            fory.deserialize_from::<Vec<i32>>(&mut reader).unwrap()
        );
        assert_eq!(
            Vec::<i64>::default(),
            fory.deserialize_from::<Vec<i64>>(&mut reader).unwrap()
        );
        assert_eq!(
            Vec::<f32>::default(),
            fory.deserialize_from::<Vec<f32>>(&mut reader).unwrap()
        );
        assert_eq!(
            Vec::<f64>::default(),
            fory.deserialize_from::<Vec<f64>>(&mut reader).unwrap()
        );
    }
}

fn deserialize_nullable(fory: &Fory, bins: Vec<u8>, auto_conv: bool) {
    let mut reader = Reader::new(bins.as_slice());
    assert_eq!(
        Some(BOOL_VAL),
        fory.deserialize_from::<Option<bool>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(I8_VAL),
        fory.deserialize_from::<Option<i8>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(I16_VAL),
        fory.deserialize_from::<Option<i16>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(I32_VAL),
        fory.deserialize_from::<Option<i32>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(I64_VAL),
        fory.deserialize_from::<Option<i64>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(F32_VAL),
        fory.deserialize_from::<Option<f32>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(F64_VAL),
        fory.deserialize_from::<Option<f64>>(&mut reader).unwrap()
    );
    assert_eq!(
        Some(STR_LATIN1_VAL.to_string()),
        fory.deserialize_from::<Option<String>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(LOCAL_DATE_VAL),
        fory.deserialize_from::<Option<NaiveDate>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(TIMESTAMP_VAL),
        fory.deserialize_from::<Option<NaiveDateTime>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(BOOL_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<bool>>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(INT8_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<i8>>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(INT16_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<i16>>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(INT32_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<i32>>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(INT64_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<i64>>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(FLOAT32_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<f32>>>(&mut reader)
            .unwrap()
    );
    assert_eq!(
        Some(FLOAT64_ARRAY.to_vec()),
        fory.deserialize_from::<Option<Vec<f64>>>(&mut reader)
            .unwrap()
    );
    if !auto_conv {
        assert_eq!(
            None,
            fory.deserialize_from::<Option<bool>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<i8>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<i16>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<i32>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<i64>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<f32>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<f64>>(&mut reader).unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<String>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<NaiveDate>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<NaiveDateTime>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<bool>>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<i8>>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<i16>>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<i32>>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<i64>>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<f32>>>(&mut reader)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_from::<Option<Vec<f64>>>(&mut reader)
                .unwrap()
        );
    }
}

// non-null <-> non-null
#[test]
fn basic() {
    let fory = Fory::builder().compatible(true).build();
    // serialize
    let bins = serialize_non_null(&fory);
    // deserialize
    deserialize_non_null(&fory, bins, false);
}

// nullable <-> nullable
#[test]
fn basic_nullable() {
    let fory = Fory::builder().compatible(true).build();
    // serialize
    let bins = serialize_nullable(&fory);
    // deserialize
    deserialize_nullable(&fory, bins, false);
}

// non-null -> nullable -> non-null
#[test]
fn auto_conv() {
    let fory = Fory::builder().compatible(true).build();
    // serialize_non-null
    let bins = serialize_non_null(&fory);
    deserialize_nullable(&fory, bins, true);
    // serialize_nullable
    let bins = serialize_nullable(&fory);
    // deserialize_non-null
    deserialize_non_null(&fory, bins, true);
}
