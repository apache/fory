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

pub mod data;
pub mod serializers;

pub mod generated {
    include!(concat!(env!("OUT_DIR"), "/protobuf.rs"));
}

use criterion::{black_box, Criterion};
use data::{
    MediaContent, MediaContentList, NumericStruct, NumericStructList, Sample, SampleList,
    SchemaMismatchCase,
};
use serializers::{
    fory::{schema_mismatch_enabled, ForySerializer},
    msgpack::MsgpackSerializer,
    protobuf::ProtobufSerializer,
    BenchmarkSerializer,
};

pub fn run_serialization_benchmarks(c: &mut Criterion) {
    let fory_serializer = ForySerializer::new();
    let protobuf_serializer = ProtobufSerializer::new();
    let msgpack_serializer = MsgpackSerializer::new();

    run_benchmark_case::<NumericStruct>(
        c,
        &fory_serializer,
        &protobuf_serializer,
        &msgpack_serializer,
    );
    run_benchmark_case::<Sample>(
        c,
        &fory_serializer,
        &protobuf_serializer,
        &msgpack_serializer,
    );
    run_benchmark_case::<MediaContent>(
        c,
        &fory_serializer,
        &protobuf_serializer,
        &msgpack_serializer,
    );
    run_benchmark_case::<NumericStructList>(
        c,
        &fory_serializer,
        &protobuf_serializer,
        &msgpack_serializer,
    );
    run_benchmark_case::<SampleList>(
        c,
        &fory_serializer,
        &protobuf_serializer,
        &msgpack_serializer,
    );
    run_benchmark_case::<MediaContentList>(
        c,
        &fory_serializer,
        &protobuf_serializer,
        &msgpack_serializer,
    );
}

fn run_benchmark_case<T>(
    c: &mut Criterion,
    fory_serializer: &ForySerializer,
    protobuf_serializer: &ProtobufSerializer,
    msgpack_serializer: &MsgpackSerializer,
) where
    T: SchemaMismatchCase,
    ForySerializer: BenchmarkSerializer<T>,
    ProtobufSerializer: BenchmarkSerializer<T>,
    MsgpackSerializer: BenchmarkSerializer<T>,
{
    let data = T::create();
    let mismatch = schema_mismatch_enabled();
    let mut group = c.benchmark_group(T::KIND.group_name());

    group.bench_function("fory_serialize", |b| {
        b.iter(|| {
            let _ = black_box(fory_serializer.serialize(black_box(&data)).unwrap());
        })
    });

    let fory_bytes = fory_serializer.serialize(&data).unwrap();
    if mismatch {
        let value: T::Read = fory_serializer.deserialize_as(&fory_bytes).unwrap();
        T::verify_mismatch(&value, &data);
    }
    group.bench_function("fory_deserialize", |b| {
        b.iter(|| {
            if mismatch {
                let value: T::Read = black_box(
                    fory_serializer
                        .deserialize_as(black_box(&fory_bytes))
                        .unwrap(),
                );
                black_box(value);
            } else {
                let value: T =
                    black_box(fory_serializer.deserialize(black_box(&fory_bytes)).unwrap());
                black_box(value);
            }
        })
    });

    if !mismatch {
        group.bench_function("protobuf_serialize", |b| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.serialize(black_box(&data)).unwrap());
            })
        });

        let protobuf_bytes = protobuf_serializer.serialize(&data).unwrap();
        group.bench_function("protobuf_deserialize", |b| {
            b.iter(|| {
                let value: T = black_box(
                    protobuf_serializer
                        .deserialize(black_box(&protobuf_bytes))
                        .unwrap(),
                );
                black_box(value);
            })
        });

        group.bench_function("msgpack_serialize", |b| {
            b.iter(|| {
                let _ = black_box(msgpack_serializer.serialize(black_box(&data)).unwrap());
            })
        });

        let msgpack_bytes = msgpack_serializer.serialize(&data).unwrap();
        group.bench_function("msgpack_deserialize", |b| {
            b.iter(|| {
                let value: T = black_box(
                    msgpack_serializer
                        .deserialize(black_box(&msgpack_bytes))
                        .unwrap(),
                );
                black_box(value);
            })
        });
    }

    group.finish();
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_round_trip<T>()
    where
        T: BenchmarkCase + std::fmt::Debug,
        ForySerializer: BenchmarkSerializer<T>,
        ProtobufSerializer: BenchmarkSerializer<T>,
        MsgpackSerializer: BenchmarkSerializer<T>,
    {
        let value = T::create();

        let fory_serializer = ForySerializer::new();
        let fory_bytes = fory_serializer.serialize(&value).unwrap();
        let decoded: T = fory_serializer.deserialize(&fory_bytes).unwrap();
        assert_eq!(value, decoded);

        let protobuf_serializer = ProtobufSerializer::new();
        let protobuf_bytes = protobuf_serializer.serialize(&value).unwrap();
        let decoded: T = protobuf_serializer.deserialize(&protobuf_bytes).unwrap();
        assert_eq!(value, decoded);

        let msgpack_serializer = MsgpackSerializer::new();
        let msgpack_bytes = msgpack_serializer.serialize(&value).unwrap();
        let decoded: T = msgpack_serializer.deserialize(&msgpack_bytes).unwrap();
        assert_eq!(value, decoded);
    }

    fn assert_serialized_size<T>(
        expected_fory: usize,
        expected_protobuf: usize,
        expected_msgpack: usize,
    ) where
        T: BenchmarkCase,
        ForySerializer: BenchmarkSerializer<T>,
        ProtobufSerializer: BenchmarkSerializer<T>,
        MsgpackSerializer: BenchmarkSerializer<T>,
    {
        let value = T::create();
        let fory_bytes = ForySerializer::new().serialize(&value).unwrap();
        let protobuf_bytes = ProtobufSerializer::new().serialize(&value).unwrap();
        let msgpack_bytes = MsgpackSerializer::new().serialize(&value).unwrap();
        assert_eq!(fory_bytes.len(), expected_fory);
        assert_eq!(protobuf_bytes.len(), expected_protobuf);
        assert_eq!(msgpack_bytes.len(), expected_msgpack);
    }

    #[test]
    fn benchmark_cases_round_trip() {
        assert_round_trip::<NumericStruct>();
        assert_round_trip::<Sample>();
        assert_round_trip::<MediaContent>();
        assert_round_trip::<NumericStructList>();
        assert_round_trip::<SampleList>();
        assert_round_trip::<MediaContentList>();
    }

    #[test]
    fn benchmark_serialized_sizes_match_baseline() {
        assert_serialized_size::<NumericStruct>(78, 93, 87);
        assert_serialized_size::<Sample>(445, 375, 590);
        assert_serialized_size::<MediaContent>(362, 301, 500);
        assert_serialized_size::<NumericStructList>(255, 475, 449);
        assert_serialized_size::<SampleList>(1978, 1890, 2964);
        assert_serialized_size::<MediaContentList>(1531, 1520, 2521);
    }
}
