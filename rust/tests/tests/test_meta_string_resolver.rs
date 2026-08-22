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

use fory_core::meta::{Encoding, NAMESPACE_ENCODER};
use fory_core::resolver::meta_string_resolver::{
    MetaStringReaderResolver, MetaStringWriterResolver,
};
use fory_core::util::murmurhash3_x64_128;
use fory_core::{Reader, Writer};
use std::rc::Rc;

fn meta_string_hash(bytes: &[u8], encoding: Encoding) -> i64 {
    let mut hash_code = (murmurhash3_x64_128(bytes, 47).0 as i64).wrapping_abs();
    if hash_code == 0 {
        hash_code += 256;
    }
    ((hash_code as u64 & 0xffffffffffffff00) | (encoding as u64 & 0xff)) as i64
}

fn write_big(writer: &mut Writer<'_>, bytes: &[u8], hash_code: i64) {
    assert!(bytes.len() > 16);
    writer.write_var_u32((bytes.len() as u32) << 1);
    writer.write_i64(hash_code);
    writer.write_bytes(bytes);
}

fn write_small(writer: &mut Writer<'_>, bytes: &[u8], encoding: Encoding) {
    assert!(!bytes.is_empty() && bytes.len() <= 16);
    writer.write_var_u32((bytes.len() as u32) << 1);
    writer.write_u8(encoding as u8);
    writer.write_bytes(bytes);
}

#[test]
pub fn empty() {
    let mut meta_string_writer = MetaStringWriterResolver::default();
    let mut meta_string_reader = MetaStringReaderResolver::default();

    for _ in 0..3 {
        let meta_string = NAMESPACE_ENCODER.encode("").unwrap();
        let rc_meta_string = Rc::from(meta_string);

        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        meta_string_writer
            .write_meta_string_bytes(&mut writer, rc_meta_string.clone())
            .unwrap();

        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());

        let new_meta_string = meta_string_reader.read_meta_string(&mut reader).unwrap();
        assert_eq!(&*rc_meta_string, new_meta_string);
        meta_string_writer.reset();
        meta_string_reader.reset();
    }
}

#[test]
pub fn small_ms() {
    let mut meta_string_writer = MetaStringWriterResolver::default();
    let mut meta_string_reader = MetaStringReaderResolver::default();
    // test reset
    for _ in 0..3 {
        // write
        let mut data = Vec::new();
        for i in 0..20 {
            let meta_string = NAMESPACE_ENCODER.encode(&format!("s_{i}")).unwrap();
            let rc_meta_string = Rc::from(meta_string);
            // test cache
            for _ in 0..3 {
                data.push(rc_meta_string.clone());
            }
        }
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        for meta_string in data.iter() {
            meta_string_writer
                .write_meta_string_bytes(&mut writer, meta_string.clone())
                .unwrap();
        }
        // read
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let read_data: Vec<_> = (0..60)
            .map(|_| {
                meta_string_reader
                    .read_meta_string(&mut reader)
                    .unwrap()
                    .clone()
            })
            .collect();
        for i in 0..60 {
            assert_eq!(*data[i], read_data[i]);
        }
        meta_string_writer.reset();
        meta_string_reader.reset();
    }
}

#[test]
pub fn big_ms() {
    let long_string = "a".repeat(50);
    let mut meta_string_writer = MetaStringWriterResolver::default();
    let mut meta_string_reader = MetaStringReaderResolver::default();
    // test reset
    for _ in 0..3 {
        // write
        let mut data = Vec::new();
        for i in 0..20 {
            let meta_string = NAMESPACE_ENCODER
                .encode(&format!("{long_string}_{i}"))
                .unwrap();
            let rc_meta_string = Rc::from(meta_string);
            // test cache
            for _ in 0..3 {
                data.push(rc_meta_string.clone());
            }
        }
        let mut buffer = vec![];
        let mut writer = Writer::from_buffer(&mut buffer);
        for meta_string in data.iter() {
            meta_string_writer
                .write_meta_string_bytes(&mut writer, meta_string.clone())
                .unwrap();
        }
        // read
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let read_data: Vec<_> = (0..60)
            .map(|_| {
                meta_string_reader
                    .read_meta_string(&mut reader)
                    .unwrap()
                    .clone()
            })
            .collect();
        for i in 0..60 {
            assert_eq!(*data[i], read_data[i]);
        }
        meta_string_writer.reset();
        meta_string_reader.reset();
    }
}

#[test]
pub fn big_dynamic_survives_growth() {
    let mut meta_string_writer = MetaStringWriterResolver::default();
    let data: Vec<_> = (0..20)
        .map(|i| {
            Rc::from(
                NAMESPACE_ENCODER
                    .encode(&format!("long_meta_string_name_{i:02}_after_growth"))
                    .unwrap(),
            )
        })
        .collect();

    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
    for meta_string in data.iter() {
        meta_string_writer
            .write_meta_string_bytes(&mut writer, meta_string.clone())
            .unwrap();
    }
    writer.write_var_u32(3);

    let binding = writer.dump();
    let mut reader = Reader::new(binding.as_slice());
    let mut meta_string_reader = MetaStringReaderResolver::default();
    for meta_string in data.iter() {
        let read = meta_string_reader.read_meta_string(&mut reader).unwrap();
        assert_eq!(&**meta_string, read);
    }
    let read = meta_string_reader.read_meta_string(&mut reader).unwrap();
    assert_eq!(&*data[0], read);
}

#[test]
pub fn small_dynamic_survives_growth() {
    let mut meta_string_writer = MetaStringWriterResolver::default();
    let data: Vec<_> = (0..20)
        .map(|i| Rc::from(NAMESPACE_ENCODER.encode(&format!("s{i:014}")).unwrap()))
        .collect();

    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
    for meta_string in data.iter() {
        meta_string_writer
            .write_meta_string_bytes(&mut writer, meta_string.clone())
            .unwrap();
    }
    writer.write_var_u32(3);

    let binding = writer.dump();
    let mut reader = Reader::new(binding.as_slice());
    let mut meta_string_reader = MetaStringReaderResolver::default();
    for meta_string in data.iter() {
        let read = meta_string_reader.read_meta_string(&mut reader).unwrap();
        assert_eq!(&**meta_string, read);
    }
    let read = meta_string_reader.read_meta_string(&mut reader).unwrap();
    assert_eq!(&*data[0], read);
}

#[test]
fn rejects_forged_big_hash() {
    let bytes = b"abcdefghijklmnopq";
    let forged_hash = meta_string_hash(bytes, Encoding::Utf8) ^ 0x100;
    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
    write_big(&mut writer, bytes, forged_hash);

    let binding = writer.dump();
    let mut reader = Reader::new(binding.as_slice());
    let mut resolver = MetaStringReaderResolver::default();
    let err = resolver.read_meta_string_bytes(&mut reader).unwrap_err();
    assert!(
        err.to_string().contains("malformed meta string hash"),
        "unexpected error: {err}"
    );
}

#[test]
fn small_zero_padding_does_not_alias() {
    let first = b"a";
    let second = b"a\0";
    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
    write_small(&mut writer, first, Encoding::Utf8);
    write_small(&mut writer, second, Encoding::Utf8);

    let binding = writer.dump();
    let mut reader = Reader::new(binding.as_slice());
    let mut resolver = MetaStringReaderResolver::default();
    assert_eq!(
        resolver
            .read_meta_string_bytes(&mut reader)
            .unwrap()
            .bytes
            .as_slice(),
        first
    );
    assert_eq!(
        resolver
            .read_meta_string_bytes(&mut reader)
            .unwrap()
            .bytes
            .as_slice(),
        second
    );
}

#[test]
fn checked_big_hit_skips_body() {
    let bytes = b"checked_big_cache_hit";
    let different_body = vec![0xff; bytes.len() + 1];
    let hash_code = meta_string_hash(bytes, Encoding::Utf8);
    let mut buffer = vec![];
    let mut writer = Writer::from_buffer(&mut buffer);
    write_big(&mut writer, bytes, hash_code);
    write_big(&mut writer, &different_body, hash_code);

    let binding = writer.dump();
    let mut reader = Reader::new(binding.as_slice());
    let mut resolver = MetaStringReaderResolver::default();
    let cached_ptr = resolver.read_meta_string_bytes(&mut reader).unwrap() as *const _;
    let cached = resolver.read_meta_string_bytes(&mut reader).unwrap();
    assert_eq!(cached as *const _, cached_ptr);
    assert_eq!(cached.bytes.as_slice(), bytes);
    assert_eq!(reader.get_cursor(), binding.len());

    let mut truncated = vec![];
    let mut writer = Writer::from_buffer(&mut truncated);
    write_big(&mut writer, bytes, hash_code);
    writer.write_var_u32(((bytes.len() as u32) + 3) << 1);
    writer.write_i64(hash_code);

    let binding = writer.dump();
    let mut reader = Reader::new(binding.as_slice());
    let mut resolver = MetaStringReaderResolver::default();
    resolver.read_meta_string_bytes(&mut reader).unwrap();
    assert!(resolver.read_meta_string_bytes(&mut reader).is_err());
}
