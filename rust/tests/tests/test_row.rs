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

use std::collections::BTreeMap;

use fory_core::row::{from_row, to_row, to_row_into, RowView};
use fory_core::types::{Date, Duration, Timestamp};
use fory_derive::ForyRow;

#[derive(ForyRow)]
struct MixedRow {
    number: i32,
    text: String,
    short: i16,
}

#[derive(ForyRow)]
struct NestedChild {
    value: i32,
}

#[derive(ForyRow)]
struct NestedParent {
    child: NestedChild,
}

#[derive(ForyRow)]
struct CollectionRow {
    values: Vec<String>,
    mapping: BTreeMap<String, String>,
}

#[derive(ForyRow)]
struct NullableRow {
    empty: String,
    missing: Option<String>,
    number: Option<i32>,
}

#[derive(ForyRow)]
struct TemporalRow {
    date: Date,
    timestamp: Timestamp,
    duration: Duration,
}

#[derive(ForyRow)]
struct GenericRow<'__fory_row, T, const N: usize>
where
    Self: '__fory_row,
    T: Copy,
{
    label: &'__fory_row str,
    value: T,
    values: [T; N],
}

#[derive(ForyRow)]
struct AssociatedRow<'source>
where
    Self: std::ops::Deref<Target = str>,
{
    value: &'source <Self as std::ops::Deref>::Target,
}

impl std::ops::Deref for AssociatedRow<'_> {
    type Target = str;

    fn deref(&self) -> &Self::Target {
        self.value
    }
}

trait RowBorrow<'row> {}

impl<'row> RowBorrow<'row> for i32 {}

#[derive(ForyRow)]
struct HrtbRow<T>
where
    for<'__fory_row> T: RowBorrow<'__fory_row>,
{
    value: T,
}

#[derive(ForyRow)]
struct ByteMethodFields {
    as_bytes: i32,
    encoded_len: i32,
}

#[derive(ForyRow)]
struct GenericValue<T> {
    value: T,
}

#[test]
fn standard_row_bytes() {
    let bytes = to_row(&MixedRow {
        number: 0x1234_5678,
        text: "A".to_owned(),
        short: 0x1234,
    })
    .unwrap();

    // This is the raw Standard Row Format vector emitted by the Java/C++
    // standard writers for schema [int32, utf8, int16].
    let expected = [
        0, 0, 0, 0, 0, 0, 0, 0, // null bitmap
        0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0, // inline int32 slot
        1, 0, 0, 0, 32, 0, 0, 0, // string size, then relative offset
        0x34, 0x12, 0, 0, 0, 0, 0, 0, // inline int16 slot
        b'A', 0, 0, 0, 0, 0, 0, 0, // string and zero padding
    ];
    assert_eq!(bytes, expected);

    let view = from_row::<MixedRow>(&bytes).unwrap();
    assert_eq!(view.number().unwrap(), 0x1234_5678);
    assert_eq!(view.text().unwrap(), "A");
    assert_eq!(view.short().unwrap(), 0x1234);
}

#[test]
fn generic_row() {
    let bytes = to_row(&GenericRow {
        label: "generic",
        value: 7i32,
        values: [1, 2, 3],
    })
    .unwrap();

    let view = from_row::<GenericRow<'static, i32, 3>>(&bytes).unwrap();
    assert_eq!(view.label().unwrap(), "generic");
    assert_eq!(view.value().unwrap(), 7);
    let values = view.values().unwrap();
    assert_eq!(values.len(), 3);
    assert_eq!(values.get(0).unwrap(), 1);
    assert_eq!(values.get(2).unwrap(), 3);

    let associated = to_row(&AssociatedRow {
        value: "associated",
    })
    .unwrap();
    let associated_view = from_row::<AssociatedRow<'static>>(&associated).unwrap();
    assert_eq!(associated_view.value().unwrap(), "associated");

    let hrtb = to_row(&HrtbRow { value: 9i32 }).unwrap();
    let hrtb_view = from_row::<HrtbRow<i32>>(&hrtb).unwrap();
    assert_eq!(hrtb_view.value().unwrap(), 9);
}

#[test]
fn primitive_array_bytes() {
    let bytes = to_row(&vec![0x1234_5678i32]).unwrap();
    let expected = [
        1, 0, 0, 0, 0, 0, 0, 0, // element count
        0, 0, 0, 0, 0, 0, 0, 0, // null bitmap
        0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0, // natural-width value and padding
    ];
    assert_eq!(bytes, expected);

    let view = from_row::<Vec<i32>>(&bytes).unwrap();
    assert_eq!(view.len(), 1);
    assert_eq!(view.get(0).unwrap(), 0x1234_5678);
    assert!(view.get(1).is_err());

    let boolean = to_row(&vec![true]).unwrap();
    assert_eq!(boolean.len(), 24);
    assert_eq!(&boolean[16..], &[1, 0, 0, 0, 0, 0, 0, 0]);

    let int8 = to_row(&vec![-1i8]).unwrap();
    assert_eq!(&int8[16..], &[0xff, 0, 0, 0, 0, 0, 0, 0]);

    let int16 = to_row(&vec![0x1234i16]).unwrap();
    assert_eq!(&int16[16..], &[0x34, 0x12, 0, 0, 0, 0, 0, 0]);

    let int64 = to_row(&vec![0x0102_0304_0506_0708i64]).unwrap();
    assert_eq!(
        &int64[16..],
        &[0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01]
    );

    let float32 = to_row(&vec![1.0f32]).unwrap();
    assert_eq!(&float32[16..], &[0, 0, 0x80, 0x3f, 0, 0, 0, 0]);

    let float64 = to_row(&vec![1.0f64]).unwrap();
    assert_eq!(&float64[16..], &[0, 0, 0, 0, 0, 0, 0xf0, 0x3f]);
}

#[test]
fn nullable_variable_array_bytes() {
    let values = vec![Some("a".to_owned()), None, Some("bc".to_owned())];
    let bytes = to_row(&values).unwrap();
    let expected = [
        3, 0, 0, 0, 0, 0, 0, 0, // element count
        2, 0, 0, 0, 0, 0, 0, 0, // element 1 is null
        1, 0, 0, 0, 40, 0, 0, 0, // "a"
        0, 0, 0, 0, 0, 0, 0, 0, // null slot remains zero
        2, 0, 0, 0, 48, 0, 0, 0, // "bc"
        b'a', 0, 0, 0, 0, 0, 0, 0, // first body
        b'b', b'c', 0, 0, 0, 0, 0, 0, // second body
    ];
    assert_eq!(bytes, expected);

    let view = from_row::<Vec<Option<String>>>(&bytes).unwrap();
    assert_eq!(view.get(0).unwrap(), Some("a"));
    assert_eq!(view.get(1).unwrap(), None);
    assert_eq!(view.get(2).unwrap(), Some("bc"));
}

#[test]
fn standard_map_bytes() {
    let values = BTreeMap::from([("k".to_owned(), 7i32)]);
    let bytes = to_row(&values).unwrap();
    let expected = [
        32, 0, 0, 0, 0, 0, 0, 0, // key array byte size
        1, 0, 0, 0, 0, 0, 0, 0, // key count
        0, 0, 0, 0, 0, 0, 0, 0, // key bitmap
        1, 0, 0, 0, 24, 0, 0, 0, // key offset-size
        b'k', 0, 0, 0, 0, 0, 0, 0, // key body
        1, 0, 0, 0, 0, 0, 0, 0, // value count
        0, 0, 0, 0, 0, 0, 0, 0, // value bitmap
        7, 0, 0, 0, 0, 0, 0, 0, // value and padding
    ];
    assert_eq!(bytes, expected);

    let view = from_row::<BTreeMap<String, i32>>(&bytes).unwrap();
    assert_eq!(view.keys().get(0).unwrap(), "k");
    assert_eq!(view.values().get(0).unwrap(), 7);
    assert_eq!(view.to_btree_map().unwrap(), BTreeMap::from([("k", 7)]));
}

#[test]
fn nested_row_bytes() {
    let bytes = to_row(&NestedParent {
        child: NestedChild { value: 7 },
    })
    .unwrap();
    let expected = [
        0, 0, 0, 0, 0, 0, 0, 0, // parent bitmap
        16, 0, 0, 0, 16, 0, 0, 0, // child size and parent-relative offset
        0, 0, 0, 0, 0, 0, 0, 0, // child bitmap
        7, 0, 0, 0, 0, 0, 0, 0, // child field slot
    ];
    assert_eq!(bytes, expected);

    let view: NestedParentRowView<'_> = from_row::<NestedParent>(&bytes).unwrap();
    assert_eq!(view.child().unwrap().value().unwrap(), 7);
}

#[test]
fn nested_container_offsets() {
    let bytes = to_row(&CollectionRow {
        values: vec!["a".to_owned()],
        mapping: BTreeMap::from([("k".to_owned(), "v".to_owned())]),
    })
    .unwrap();

    assert_eq!(&bytes[8..16], &[32, 0, 0, 0, 24, 0, 0, 0]);
    assert_eq!(&bytes[16..24], &[72, 0, 0, 0, 56, 0, 0, 0]);
    assert_eq!(&bytes[40..48], &[1, 0, 0, 0, 24, 0, 0, 0]);
    assert_eq!(&bytes[80..88], &[1, 0, 0, 0, 24, 0, 0, 0]);
    assert_eq!(&bytes[112..120], &[1, 0, 0, 0, 24, 0, 0, 0]);

    let view = from_row::<CollectionRow>(&bytes).unwrap();
    assert_eq!(view.values().unwrap().get(0).unwrap(), "a");
    assert_eq!(
        view.mapping().unwrap().to_btree_map().unwrap(),
        BTreeMap::from([("k", "v")])
    );
}

#[test]
fn null_and_empty_are_distinct() {
    let bytes = to_row(&NullableRow {
        empty: String::new(),
        missing: None,
        number: None,
    })
    .unwrap();
    assert_eq!(bytes.len(), 32);
    assert_eq!(bytes[0], 0b0000_0110);
    assert_eq!(&bytes[8..16], &[0, 0, 0, 0, 32, 0, 0, 0]);
    assert_eq!(&bytes[16..32], &[0; 16]);

    let view = from_row::<NullableRow>(&bytes).unwrap();
    assert_eq!(view.empty().unwrap(), "");
    assert_eq!(view.missing().unwrap(), None);
    assert_eq!(view.number().unwrap(), None);
}

#[test]
fn null_bitmap_crosses_words() {
    let mut values = vec![Some(1i8); 65];
    for index in [0, 7, 8, 63, 64] {
        values[index] = None;
    }
    let bytes = to_row(&values).unwrap();
    assert_eq!(
        &bytes[8..24],
        &[0x81, 0x01, 0, 0, 0, 0, 0, 0x80, 1, 0, 0, 0, 0, 0, 0, 0]
    );

    let view = from_row::<Vec<Option<i8>>>(&bytes).unwrap();
    for index in 0..65 {
        let expected = if [0, 7, 8, 63, 64].contains(&index) {
            None
        } else {
            Some(1)
        };
        assert_eq!(view.get(index).unwrap(), expected);
    }
}

#[test]
fn temporal_slots() {
    let value = TemporalRow {
        date: Date::from_epoch_days(-2),
        timestamp: Timestamp::from_epoch_micros(-1),
        duration: Duration::from_micros(1_500_000),
    };
    let bytes = to_row(&value).unwrap();
    assert_eq!(&bytes[8..12], &(-2i32).to_le_bytes());
    assert_eq!(&bytes[16..24], &(-1i64).to_le_bytes());
    assert_eq!(&bytes[24..32], &(1_500_000i64).to_le_bytes());

    let view = from_row::<TemporalRow>(&bytes).unwrap();
    assert_eq!(view.date().unwrap(), value.date);
    assert_eq!(view.timestamp().unwrap(), value.timestamp);
    assert_eq!(view.duration().unwrap(), value.duration);
}

#[test]
fn malformed_rows_return_errors() {
    let valid = to_row(&MixedRow {
        number: 1,
        text: "A".to_owned(),
        short: 2,
    })
    .unwrap();
    assert!(from_row::<MixedRow>(&valid[..31]).is_err());

    let mut overlap = valid.clone();
    overlap[16..24].copy_from_slice(&1u64.to_le_bytes());
    assert!(from_row::<MixedRow>(&overlap).unwrap().text().is_err());

    let mut outside = valid.clone();
    outside[16..24].copy_from_slice(&(((32u64) << 32) | 100).to_le_bytes());
    assert!(from_row::<MixedRow>(&outside).unwrap().text().is_err());

    let mut invalid_utf8 = valid;
    invalid_utf8[32] = 0xff;
    assert!(from_row::<MixedRow>(&invalid_utf8).unwrap().text().is_err());

    assert!(from_row::<Vec<i32>>(&u64::MAX.to_le_bytes()).is_err());
}

#[test]
fn container_shape_is_validated() {
    let one = to_row(&vec![1i32]).unwrap();
    assert!(from_row::<[i32; 2]>(&one).is_err());

    let map = to_row(&BTreeMap::from([("k".to_owned(), 7i32)])).unwrap();
    let mut mismatched = map;
    mismatched[40..48].copy_from_slice(&2u64.to_le_bytes());
    assert!(from_row::<BTreeMap<String, i32>>(&mismatched).is_err());
}

#[test]
fn collection_view_navigation() {
    let fixed_bytes = to_row(&vec![1i32, 2, 3]).unwrap();
    let fixed = from_row::<Vec<i32>>(&fixed_bytes).unwrap();
    let mut fixed_iter = fixed.iter();
    assert_eq!(fixed_iter.len(), 3);
    assert_eq!(fixed_iter.next().unwrap().unwrap(), 1);
    assert_eq!(fixed_iter.len(), 2);
    assert_eq!(fixed_iter.collect::<Result<Vec<_>, _>>().unwrap(), [2, 3]);

    let optional_bytes = to_row(&vec![Some("a".to_owned()), None, Some("b".to_owned())]).unwrap();
    let optional = from_row::<Vec<Option<String>>>(&optional_bytes).unwrap();
    assert_eq!(
        (&optional)
            .into_iter()
            .collect::<Result<Vec<_>, _>>()
            .unwrap(),
        [Some("a"), None, Some("b")]
    );

    let nested_bytes = to_row(&vec![NestedChild { value: 4 }, NestedChild { value: 5 }]).unwrap();
    let nested = from_row::<Vec<NestedChild>>(&nested_bytes).unwrap();
    let nested_values = nested
        .iter()
        .map(|value| value.and_then(|view| view.value()))
        .collect::<Result<Vec<_>, _>>()
        .unwrap();
    assert_eq!(nested_values, [4, 5]);

    let map_bytes = to_row(&BTreeMap::from([
        ("a".to_owned(), 1i32),
        ("b".to_owned(), 2i32),
    ]))
    .unwrap();
    let map = from_row::<BTreeMap<String, i32>>(&map_bytes).unwrap();
    assert_eq!(map.len(), 2);
    assert!(!map.is_empty());
    assert_eq!(map.key(0).unwrap(), "a");
    assert_eq!(map.value(0).unwrap(), 1);
    assert!(map.key(map.len()).is_err());
    assert!(map.value(map.len()).is_err());

    let empty_bytes = to_row(&BTreeMap::<String, i32>::new()).unwrap();
    let empty = from_row::<BTreeMap<String, i32>>(&empty_bytes).unwrap();
    assert!(empty.is_empty());
    assert_eq!(empty.len(), 0);
    assert_eq!(empty.keys().iter().len(), 0);
}

#[test]
fn iteration_is_lazy() {
    let mut bytes = to_row(&vec!["a".to_owned(), "b".to_owned()]).unwrap();
    bytes[24..32].copy_from_slice(&(((40u64) << 32) | 100).to_le_bytes());

    let view = from_row::<Vec<String>>(&bytes).unwrap();
    let mut iter = view.iter();
    assert_eq!(iter.next().unwrap().unwrap(), "a");
    assert!(iter.next().unwrap().is_err());
    assert!(iter.next().is_none());
}

#[test]
fn view_bytes_and_copies() {
    fn copy<T: Copy>(value: T) -> T {
        value
    }

    fn clone<T: Clone>(value: &T) -> T {
        value.clone()
    }

    let nested_bytes = to_row(&NestedParent {
        child: NestedChild { value: 7 },
    })
    .unwrap();
    let parent = from_row::<NestedParent>(&nested_bytes).unwrap();
    assert_eq!(parent.as_bytes(), nested_bytes);
    assert_eq!(parent.encoded_len(), nested_bytes.len());
    assert_eq!(copy(parent).child().unwrap().value().unwrap(), 7);
    assert_eq!(clone(&parent).child().unwrap().value().unwrap(), 7);

    let generic_bytes = to_row(&GenericValue {
        value: "owned".to_owned(),
    })
    .unwrap();
    let generic = from_row::<GenericValue<String>>(&generic_bytes).unwrap();
    assert_eq!(copy(generic).value().unwrap(), "owned");
    assert_eq!(clone(&generic).value().unwrap(), "owned");

    let child = parent.child().unwrap();
    assert_eq!(child.as_bytes(), &nested_bytes[16..32]);
    assert_eq!(child.encoded_len(), 16);

    let array_bytes = to_row(&vec![NestedChild { value: 8 }]).unwrap();
    let array = from_row::<Vec<NestedChild>>(&array_bytes).unwrap();
    assert_eq!(copy(array).as_bytes(), array_bytes);
    assert_eq!(array.encoded_len(), array_bytes.len());

    let map_bytes = to_row(&BTreeMap::from([("k".to_owned(), 9i32)])).unwrap();
    let map = from_row::<BTreeMap<String, i32>>(&map_bytes).unwrap();
    assert_eq!(copy(map).as_bytes(), map_bytes);
    assert_eq!(map.encoded_len(), map_bytes.len());

    let named_bytes = to_row(&ByteMethodFields {
        as_bytes: 10,
        encoded_len: 20,
    })
    .unwrap();
    let named = from_row::<ByteMethodFields>(&named_bytes).unwrap();
    assert_eq!(named.as_bytes().unwrap(), 10);
    assert_eq!(named.encoded_len().unwrap(), 20);
    assert_eq!(RowView::as_bytes(&named), named_bytes);
    assert_eq!(RowView::encoded_len(&named), named_bytes.len());
}

#[test]
fn reusable_row_buffer() {
    let mut buffer = Vec::with_capacity(256);
    let original_pointer = buffer.as_ptr();
    let original_capacity = buffer.capacity();

    let value = MixedRow {
        number: 7,
        text: "reused".to_owned(),
        short: 3,
    };
    let expected = to_row(&value).unwrap();
    to_row_into(&value, &mut buffer).unwrap();
    assert_eq!(buffer, expected);
    assert_eq!(buffer.as_ptr(), original_pointer);
    assert_eq!(buffer.capacity(), original_capacity);

    let array = vec![1i16, 2];
    let expected = to_row(&array).unwrap();
    to_row_into(&array, &mut buffer).unwrap();
    assert_eq!(buffer, expected);

    let map = BTreeMap::from([("k".to_owned(), "v".to_owned())]);
    let expected = to_row(&map).unwrap();
    to_row_into(&map, &mut buffer).unwrap();
    assert_eq!(buffer, expected);

    let invalid = TemporalRow {
        date: Date::from_epoch_days(i64::from(i32::MAX) + 1),
        timestamp: Timestamp::from_epoch_micros(0),
        duration: Duration::from_micros(0),
    };
    assert!(to_row_into(&invalid, &mut buffer).is_err());
    assert!(buffer.is_empty());
    assert_eq!(buffer.capacity(), original_capacity);
}
