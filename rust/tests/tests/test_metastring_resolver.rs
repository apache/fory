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

use fory_core::meta::NAMESPACE_ENCODER;
use fory_core::resolver::metastring_resolver::{
    MetaStringReaderResolver, MetaStringWriterResolver,
};
use fory_core::{Reader, Writer};
use std::rc::Rc;

#[test]
pub fn empty() {
    let mut ms_writer = MetaStringWriterResolver::default();
    let mut ms_reader = MetaStringReaderResolver::default();

    for _ in 0..3 {
        let ms = NAMESPACE_ENCODER.encode("").unwrap();
        let rc_ms = Rc::from(ms);

        let mut writer = Writer::default();
        ms_writer
            .write_meta_string_bytes(&mut writer, rc_ms.clone())
            .unwrap();

        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());

        let read_ms = ms_reader.read_meta_string(&mut reader).unwrap();
        assert_eq!(&*rc_ms, read_ms);
        ms_writer.reset();
        ms_reader.reset();
    }
}

#[test]
pub fn small_ms() {
    let mut ms_writer = MetaStringWriterResolver::default();
    let mut ms_reader = MetaStringReaderResolver::default();
    // test reset
    for _ in 0..3 {
        // write
        let mut data = Vec::new();
        for i in 0..20 {
            let ms = NAMESPACE_ENCODER.encode(&format!("ms_{i}")).unwrap();
            let rc_ms = Rc::from(ms);
            // test cache
            for _ in 0..3 {
                data.push(rc_ms.clone());
            }
        }
        let mut writer = Writer::default();
        for ms in data.iter() {
            ms_writer
                .write_meta_string_bytes(&mut writer, ms.clone())
                .unwrap();
        }
        // read
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let read_data: Vec<_> = (0..60)
            .map(|_| ms_reader.read_meta_string(&mut reader).unwrap().clone())
            .collect();
        for i in 0..60 {
            assert_eq!(*data[i], read_data[i]);
        }
        ms_writer.reset();
        ms_reader.reset();
    }
}

#[test]
pub fn big_ms() {
    let long_string = "a".repeat(50);
    let mut ms_writer = MetaStringWriterResolver::default();
    let mut ms_reader = MetaStringReaderResolver::default();
    // test reset
    for _ in 0..3 {
        // write
        let mut data = Vec::new();
        for i in 0..20 {
            let ms = NAMESPACE_ENCODER
                .encode(&format!("{long_string}_{i}"))
                .unwrap();
            let rc_ms = Rc::from(ms);
            // test cache
            for _ in 0..3 {
                data.push(rc_ms.clone());
            }
        }
        let mut writer = Writer::default();
        for ms in data.iter() {
            ms_writer
                .write_meta_string_bytes(&mut writer, ms.clone())
                .unwrap();
        }
        // read
        let binding = writer.dump();
        let mut reader = Reader::new(binding.as_slice());
        let read_data: Vec<_> = (0..60)
            .map(|_| ms_reader.read_meta_string(&mut reader).unwrap().clone())
            .collect();
        for i in 0..60 {
            assert_eq!(*data[i], read_data[i]);
        }
        ms_writer.reset();
        ms_reader.reset();
    }
}
