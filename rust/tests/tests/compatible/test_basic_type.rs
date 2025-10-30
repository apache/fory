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
    fory.serialize_to(&BOOL_VAL, &mut buf).unwrap();
    fory.serialize_to(&I8_VAL, &mut buf).unwrap();
    fory.serialize_to(&I16_VAL, &mut buf).unwrap();
    fory.serialize_to(&I32_VAL, &mut buf).unwrap();
    fory.serialize_to(&I64_VAL, &mut buf).unwrap();
    fory.serialize_to(&F32_VAL, &mut buf).unwrap();
    fory.serialize_to(&F64_VAL, &mut buf).unwrap();
    fory.serialize_to(&STR_LATIN1_VAL.to_string(), &mut buf)
        .unwrap();
    fory.serialize_to(&LOCAL_DATE_VAL, &mut buf).unwrap();
    fory.serialize_to(&TIMESTAMP_VAL, &mut buf).unwrap();
    fory.serialize_to(&BOOL_ARRAY.to_vec(), &mut buf).unwrap();
    fory.serialize_to(&INT8_ARRAY.to_vec(), &mut buf).unwrap();
    fory.serialize_to(&INT16_ARRAY.to_vec(), &mut buf).unwrap();
    fory.serialize_to(&INT32_ARRAY.to_vec(), &mut buf).unwrap();
    fory.serialize_to(&INT64_ARRAY.to_vec(), &mut buf).unwrap();
    fory.serialize_to(&FLOAT32_ARRAY.to_vec(), &mut buf)
        .unwrap();
    fory.serialize_to(&FLOAT64_ARRAY.to_vec(), &mut buf)
        .unwrap();
    buf
}

fn serialize_nullable(fory: &Fory) -> Vec<u8> {
    let mut buf = Vec::new();
    fory.serialize_to(&Some(BOOL_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(I8_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(I16_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(I32_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(I64_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(F32_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(F64_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(STR_LATIN1_VAL.to_string()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(LOCAL_DATE_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(TIMESTAMP_VAL), &mut buf).unwrap();
    fory.serialize_to(&Some(BOOL_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(INT8_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(INT16_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(INT32_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(INT64_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(FLOAT32_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Some(FLOAT64_ARRAY.to_vec()), &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<bool>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<i8>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<i16>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<i32>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<i64>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<f32>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<f64>::None, &mut buf).unwrap();
    fory.serialize_to(&Option::<String>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<NaiveDate>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<NaiveDateTime>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<bool>>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<i8>>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<i16>>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<i32>>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<i64>>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<f32>>::None, &mut buf)
        .unwrap();
    fory.serialize_to(&Option::<Vec<f64>>::None, &mut buf)
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
    let fory = Fory::default().compatible(true);
    // serialize
    let bins = serialize_non_null(&fory);
    // deserialize
    deserialize_non_null(&fory, bins, false);
}

// nullable <-> nullable
#[test]
fn basic_nullable() {
    let fory = Fory::default().compatible(true);
    // serialize
    let bins = serialize_nullable(&fory);
    // deserialize
    deserialize_nullable(&fory, bins, false);
}

// non-null -> nullable -> non-null
#[test]
fn auto_conv() {
    let fory = Fory::default().compatible(true);
    // serialize_non-null
    let bins = serialize_non_null(&fory);
    deserialize_nullable(&fory, bins, true);
    // serialize_nullable
    let bins = serialize_nullable(&fory);
    // deserialize_non-null
    deserialize_non_null(&fory, bins, true);
}
