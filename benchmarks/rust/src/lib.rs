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
    BenchmarkCase, MediaContent, MediaContentList, NumericStruct, Sample, SampleList, StructList,
};
use serializers::{fory::ForySerializer, protobuf::ProtobufSerializer, BenchmarkSerializer};

pub fn run_serialization_benchmarks(c: &mut Criterion) {
    let fory_serializer = ForySerializer::new();
    let protobuf_serializer = ProtobufSerializer::new();

    run_benchmark_case::<NumericStruct>(c, &fory_serializer, &protobuf_serializer);
    run_benchmark_case::<Sample>(c, &fory_serializer, &protobuf_serializer);
    run_benchmark_case::<MediaContent>(c, &fory_serializer, &protobuf_serializer);
    run_benchmark_case::<StructList>(c, &fory_serializer, &protobuf_serializer);
    run_benchmark_case::<SampleList>(c, &fory_serializer, &protobuf_serializer);
    run_benchmark_case::<MediaContentList>(c, &fory_serializer, &protobuf_serializer);
}

fn run_benchmark_case<T>(
    c: &mut Criterion,
    fory_serializer: &ForySerializer,
    protobuf_serializer: &ProtobufSerializer,
) where
    T: BenchmarkCase,
    ForySerializer: BenchmarkSerializer<T>,
    ProtobufSerializer: BenchmarkSerializer<T>,
{
    let data = T::create();
    let mut group = c.benchmark_group(T::KIND.group_name());

    group.bench_function("fory_serialize", |b| {
        b.iter(|| {
            let _ = black_box(fory_serializer.serialize(black_box(&data)).unwrap());
        })
    });

    let fory_bytes = fory_serializer.serialize(&data).unwrap();
    group.bench_function("fory_deserialize", |b| {
        b.iter(|| {
            let value: T = black_box(fory_serializer.deserialize(black_box(&fory_bytes)).unwrap());
            black_box(value);
        })
    });

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
    }

    #[test]
    fn benchmark_cases_round_trip() {
        assert_round_trip::<NumericStruct>();
        assert_round_trip::<Sample>();
        assert_round_trip::<MediaContent>();
        assert_round_trip::<StructList>();
        assert_round_trip::<SampleList>();
        assert_round_trip::<MediaContentList>();
    }
}
