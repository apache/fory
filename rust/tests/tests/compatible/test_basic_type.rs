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
use fory_core::buffer::{Reader, Writer};
use fory_core::fory::Fory;
use fory_core::resolver::context::{ReadContext, WriteContext};
use fory_core::types::Mode::Compatible;

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

fn serialize_non_null(fory: &Fory, context: &mut WriteContext) {
    fory.serialize_with_context(&BOOL_VAL, context);
    fory.serialize_with_context(&I8_VAL, context);
    fory.serialize_with_context(&I16_VAL, context);
    fory.serialize_with_context(&I32_VAL, context);
    fory.serialize_with_context(&I64_VAL, context);
    fory.serialize_with_context(&F32_VAL, context);
    fory.serialize_with_context(&F64_VAL, context);
    fory.serialize_with_context(&STR_LATIN1_VAL.to_string(), context);
    fory.serialize_with_context(&LOCAL_DATE_VAL, context);
    fory.serialize_with_context(&TIMESTAMP_VAL, context);

    fory.serialize_with_context(&BOOL_ARRAY.to_vec(), context);
    fory.serialize_with_context(&INT8_ARRAY.to_vec(), context);
    fory.serialize_with_context(&INT16_ARRAY.to_vec(), context);
    fory.serialize_with_context(&INT32_ARRAY.to_vec(), context);
    fory.serialize_with_context(&INT64_ARRAY.to_vec(), context);
    fory.serialize_with_context(&FLOAT32_ARRAY.to_vec(), context);
    fory.serialize_with_context(&FLOAT64_ARRAY.to_vec(), context);
}

fn serialize_nullable(fory: &Fory, context: &mut WriteContext) {
    fory.serialize_with_context(&Some(BOOL_VAL), context);
    fory.serialize_with_context(&Some(I8_VAL), context);
    fory.serialize_with_context(&Some(I16_VAL), context);
    fory.serialize_with_context(&Some(I32_VAL), context);
    fory.serialize_with_context(&Some(I64_VAL), context);
    fory.serialize_with_context(&Some(F32_VAL), context);
    fory.serialize_with_context(&Some(F64_VAL), context);
    fory.serialize_with_context(&Some(STR_LATIN1_VAL.to_string()), context);
    fory.serialize_with_context(&Some(LOCAL_DATE_VAL), context);
    fory.serialize_with_context(&Some(TIMESTAMP_VAL), context);

    fory.serialize_with_context(&Some(BOOL_ARRAY.to_vec()), context);
    fory.serialize_with_context(&Some(INT8_ARRAY.to_vec()), context);
    fory.serialize_with_context(&Some(INT16_ARRAY.to_vec()), context);
    fory.serialize_with_context(&Some(INT32_ARRAY.to_vec()), context);
    fory.serialize_with_context(&Some(INT64_ARRAY.to_vec()), context);
    fory.serialize_with_context(&Some(FLOAT32_ARRAY.to_vec()), context);
    fory.serialize_with_context(&Some(FLOAT64_ARRAY.to_vec()), context);

    fory.serialize_with_context(&Option::<bool>::None, context);
    fory.serialize_with_context(&Option::<i8>::None, context);
    fory.serialize_with_context(&Option::<i16>::None, context);
    fory.serialize_with_context(&Option::<i32>::None, context);
    fory.serialize_with_context(&Option::<i64>::None, context);
    fory.serialize_with_context(&Option::<f32>::None, context);
    fory.serialize_with_context(&Option::<f64>::None, context);
    fory.serialize_with_context(&Option::<String>::None, context);
    fory.serialize_with_context(&Option::<NaiveDate>::None, context);
    fory.serialize_with_context(&Option::<NaiveDateTime>::None, context);

    fory.serialize_with_context(&Option::<Vec<bool>>::None, context);
    fory.serialize_with_context(&Option::<Vec<i8>>::None, context);
    fory.serialize_with_context(&Option::<Vec<i16>>::None, context);
    fory.serialize_with_context(&Option::<Vec<i32>>::None, context);
    fory.serialize_with_context(&Option::<Vec<i64>>::None, context);
    fory.serialize_with_context(&Option::<Vec<f32>>::None, context);
    fory.serialize_with_context(&Option::<Vec<f64>>::None, context);
}

fn deserialize_non_null(fory: &Fory, context: &mut ReadContext, auto_conv: bool, to_end: bool) {
    assert_eq!(
        BOOL_VAL,
        fory.deserialize_with_context::<bool>(context).unwrap()
    );
    assert_eq!(
        I8_VAL,
        fory.deserialize_with_context::<i8>(context).unwrap()
    );
    assert_eq!(
        I16_VAL,
        fory.deserialize_with_context::<i16>(context).unwrap()
    );
    assert_eq!(
        I32_VAL,
        fory.deserialize_with_context::<i32>(context).unwrap()
    );
    assert_eq!(
        I64_VAL,
        fory.deserialize_with_context::<i64>(context).unwrap()
    );
    assert_eq!(
        F32_VAL,
        fory.deserialize_with_context::<f32>(context).unwrap()
    );
    assert_eq!(
        F64_VAL,
        fory.deserialize_with_context::<f64>(context).unwrap()
    );
    assert_eq!(
        STR_LATIN1_VAL.to_string(),
        fory.deserialize_with_context::<String>(context).unwrap()
    );
    assert_eq!(
        LOCAL_DATE_VAL,
        fory.deserialize_with_context::<NaiveDate>(context).unwrap()
    );
    assert_eq!(
        TIMESTAMP_VAL,
        fory.deserialize_with_context::<NaiveDateTime>(context)
            .unwrap()
    );

    assert_eq!(
        BOOL_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<bool>>(context).unwrap()
    );
    assert_eq!(
        INT8_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<i8>>(context).unwrap()
    );
    assert_eq!(
        INT16_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<i16>>(context).unwrap()
    );
    assert_eq!(
        INT32_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<i32>>(context).unwrap()
    );
    assert_eq!(
        INT64_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<i64>>(context).unwrap()
    );
    assert_eq!(
        FLOAT32_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<f32>>(context).unwrap()
    );
    assert_eq!(
        FLOAT64_ARRAY.to_vec(),
        fory.deserialize_with_context::<Vec<f64>>(context).unwrap()
    );
    if auto_conv {
        assert_eq!(
            bool::default(),
            fory.deserialize_with_context::<bool>(context).unwrap()
        );
        assert_eq!(
            i8::default(),
            fory.deserialize_with_context::<i8>(context).unwrap()
        );
        assert_eq!(
            i16::default(),
            fory.deserialize_with_context::<i16>(context).unwrap()
        );
        assert_eq!(
            i32::default(),
            fory.deserialize_with_context::<i32>(context).unwrap()
        );
        assert_eq!(
            i64::default(),
            fory.deserialize_with_context::<i64>(context).unwrap()
        );
        assert_eq!(
            f32::default(),
            fory.deserialize_with_context::<f32>(context).unwrap()
        );
        assert_eq!(
            f64::default(),
            fory.deserialize_with_context::<f64>(context).unwrap()
        );
        assert_eq!(
            String::default(),
            fory.deserialize_with_context::<String>(context).unwrap()
        );
        assert_eq!(
            NaiveDate::default(),
            fory.deserialize_with_context::<NaiveDate>(context).unwrap()
        );
        assert_eq!(
            NaiveDateTime::default(),
            fory.deserialize_with_context::<NaiveDateTime>(context)
                .unwrap()
        );

        assert_eq!(
            Vec::<bool>::default(),
            fory.deserialize_with_context::<Vec<bool>>(context).unwrap()
        );
        assert_eq!(
            Vec::<i8>::default(),
            fory.deserialize_with_context::<Vec<i8>>(context).unwrap()
        );
        assert_eq!(
            Vec::<i16>::default(),
            fory.deserialize_with_context::<Vec<i16>>(context).unwrap()
        );
        assert_eq!(
            Vec::<i32>::default(),
            fory.deserialize_with_context::<Vec<i32>>(context).unwrap()
        );
        assert_eq!(
            Vec::<i64>::default(),
            fory.deserialize_with_context::<Vec<i64>>(context).unwrap()
        );
        assert_eq!(
            Vec::<f32>::default(),
            fory.deserialize_with_context::<Vec<f32>>(context).unwrap()
        );
        assert_eq!(
            Vec::<f64>::default(),
            fory.deserialize_with_context::<Vec<f64>>(context).unwrap()
        );
    }
    if to_end {
        assert_eq!(context.reader.slice_after_cursor().len(), 0);
    }
}

fn deserialize_nullable(fory: &Fory, context: &mut ReadContext, auto_conv: bool, to_end: bool) {
    assert_eq!(
        Some(BOOL_VAL),
        fory.deserialize_with_context::<Option<bool>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(I8_VAL),
        fory.deserialize_with_context::<Option<i8>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(I16_VAL),
        fory.deserialize_with_context::<Option<i16>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(I32_VAL),
        fory.deserialize_with_context::<Option<i32>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(I64_VAL),
        fory.deserialize_with_context::<Option<i64>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(F32_VAL),
        fory.deserialize_with_context::<Option<f32>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(F64_VAL),
        fory.deserialize_with_context::<Option<f64>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(STR_LATIN1_VAL.to_string()),
        fory.deserialize_with_context::<Option<String>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(LOCAL_DATE_VAL),
        fory.deserialize_with_context::<Option<NaiveDate>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(TIMESTAMP_VAL),
        fory.deserialize_with_context::<Option<NaiveDateTime>>(context)
            .unwrap()
    );

    assert_eq!(
        Some(BOOL_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<bool>>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(INT8_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<i8>>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(INT16_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<i16>>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(INT32_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<i32>>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(INT64_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<i64>>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(FLOAT32_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<f32>>>(context)
            .unwrap()
    );
    assert_eq!(
        Some(FLOAT64_ARRAY.to_vec()),
        fory.deserialize_with_context::<Option<Vec<f64>>>(context)
            .unwrap()
    );
    if !auto_conv {
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<bool>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<i8>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<i16>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<i32>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<i64>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<f32>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<f64>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<String>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<NaiveDate>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<NaiveDateTime>>(context)
                .unwrap()
        );

        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<bool>>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<i8>>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<i16>>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<i32>>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<i64>>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<f32>>>(context)
                .unwrap()
        );
        assert_eq!(
            None,
            fory.deserialize_with_context::<Option<Vec<f64>>>(context)
                .unwrap()
        );
    }
    if to_end {
        assert_eq!(context.reader.slice_after_cursor().len(), 0);
    }
}

// non-null <-> non-null
#[test]
fn basic() {
    let fory = Fory::default().mode(Compatible);
    // serialize
    let mut writer = Writer::default();
    let mut write_context = WriteContext::new(writer);
    serialize_non_null(&fory, &mut write_context);
    // deserialize
    let bytes = write_context.writer.dump();
    let reader = Reader::new(bytes.as_slice());
    let mut read_context = ReadContext::new(reader, 5);
    deserialize_non_null(&fory, &mut read_context, false, true);
}

// nullable <-> nullable
#[test]
fn basic_nullable() {
    let fory = Fory::default().mode(Compatible);
    // serialize
    let mut writer = Writer::default();
    let mut write_context = WriteContext::new(writer);
    serialize_nullable(&fory, &mut write_context);
    // deserialize
    let bytes = write_context.writer.dump();
    let reader = Reader::new(bytes.as_slice());
    let mut read_context = ReadContext::new(reader, 5);
    deserialize_nullable(&fory, &mut read_context, false, true);
}

// non-null -> nullable -> non-null
#[test]
fn auto_conv() {
    let fory = Fory::default().mode(Compatible);
    // serialize_non-null
    let mut writer = Writer::default();
    let mut write_context = WriteContext::new(writer);
    serialize_non_null(&fory, &mut write_context);
    // deserialize_nullable
    let bytes = write_context.writer.dump();
    let reader = Reader::new(bytes.as_slice());
    let mut read_context: ReadContext = ReadContext::new(reader, 5);
    deserialize_nullable(&fory, &mut read_context, true, true);
    // serialize_nullable
    let mut writer = Writer::default();
    let mut write_context = WriteContext::new(writer);
    serialize_nullable(&fory, &mut write_context);
    // deserialize_non-null
    let bytes = write_context.writer.dump();
    let reader = Reader::new(bytes.as_slice());
    let mut read_context = ReadContext::new(reader, 5);
    deserialize_non_null(&fory, &mut read_context, true, true);
}
