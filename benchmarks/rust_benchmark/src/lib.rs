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

pub mod models;
pub mod serializers;

// Include generated protobuf code
include!(concat!(env!("OUT_DIR"), "/simple.rs"));
include!(concat!(env!("OUT_DIR"), "/medium.rs"));
include!(concat!(env!("OUT_DIR"), "/complex.rs"));
include!(concat!(env!("OUT_DIR"), "/realworld.rs"));

// Benchmark function implementation
use criterion::{black_box, BenchmarkId, Criterion};
use models::{
    complex::ECommerceData,
    medium::{Company, Person},
    realworld::SystemData,
    simple::{SimpleList, SimpleMap, SimpleStruct},
    TestDataGenerator,
};
use serializers::{
    fory::ForySerializer, json::JsonSerializer, protobuf::ProtobufSerializer, Serializer,
};

pub fn run_serialization_benchmarks(c: &mut Criterion) {
    let fory_serializer = ForySerializer::new();
    let protobuf_serializer = ProtobufSerializer::new();
    let json_serializer = JsonSerializer::new();

    // Simple struct benchmarks
    run_benchmark_group::<SimpleStruct>(
        c,
        "simple_struct",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );

    // Simple list benchmarks
    run_benchmark_group::<SimpleList>(
        c,
        "simple_list",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );

    // Simple map benchmarks
    run_benchmark_group::<SimpleMap>(
        c,
        "simple_map",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );

    // Person benchmarks
    run_benchmark_group::<Person>(
        c,
        "person",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );

    // Company benchmarks
    run_benchmark_group::<Company>(
        c,
        "company",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );

    // ECommerce data benchmarks
    run_benchmark_group::<ECommerceData>(
        c,
        "ecommerce_data",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );

    // System data benchmarks
    run_benchmark_group::<SystemData>(
        c,
        "system_data",
        &fory_serializer,
        &protobuf_serializer,
        &json_serializer,
    );
}

fn run_benchmark_group<T>(
    c: &mut Criterion,
    group_name: &str,
    fory_serializer: &ForySerializer,
    protobuf_serializer: &ProtobufSerializer,
    json_serializer: &JsonSerializer,
) where
    T: TestDataGenerator<Data = T> + Clone + PartialEq,
    ForySerializer: Serializer<T>,
    ProtobufSerializer: Serializer<T>,
    JsonSerializer: Serializer<T>,
{
    let mut group = c.benchmark_group(group_name);

    // Test data sizes
    let small_data = T::generate_small();
    let medium_data = T::generate_medium();
    let large_data = T::generate_large();

    // Fory serialization benchmarks
    group.bench_with_input(
        BenchmarkId::new("fory_serialize", "small"),
        &small_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(fory_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("fory_serialize", "medium"),
        &medium_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(fory_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("fory_serialize", "large"),
        &large_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(fory_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    // Fory deserialization benchmarks
    let small_serialized = fory_serializer.serialize(&small_data).unwrap();
    let medium_serialized = fory_serializer.serialize(&medium_data).unwrap();
    let large_serialized = fory_serializer.serialize(&large_data).unwrap();

    group.bench_with_input(
        BenchmarkId::new("fory_deserialize", "small"),
        &small_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(fory_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("fory_deserialize", "medium"),
        &medium_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(fory_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("fory_deserialize", "large"),
        &large_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(fory_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    // Protobuf serialization benchmarks
    group.bench_with_input(
        BenchmarkId::new("protobuf_serialize", "small"),
        &small_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("protobuf_serialize", "medium"),
        &medium_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("protobuf_serialize", "large"),
        &large_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    // Protobuf deserialization benchmarks
    let small_protobuf_serialized = protobuf_serializer.serialize(&small_data).unwrap();
    let medium_protobuf_serialized = protobuf_serializer.serialize(&medium_data).unwrap();
    let large_protobuf_serialized = protobuf_serializer.serialize(&large_data).unwrap();

    group.bench_with_input(
        BenchmarkId::new("protobuf_deserialize", "small"),
        &small_protobuf_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("protobuf_deserialize", "medium"),
        &medium_protobuf_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("protobuf_deserialize", "large"),
        &large_protobuf_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(protobuf_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    // JSON serialization benchmarks
    group.bench_with_input(
        BenchmarkId::new("json_serialize", "small"),
        &small_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(json_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("json_serialize", "medium"),
        &medium_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(json_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("json_serialize", "large"),
        &large_data,
        |b, data| {
            b.iter(|| {
                let _ = black_box(json_serializer.serialize(black_box(data)).unwrap());
            })
        },
    );

    // JSON deserialization benchmarks
    let small_json_serialized = json_serializer.serialize(&small_data).unwrap();
    let medium_json_serialized = json_serializer.serialize(&medium_data).unwrap();
    let large_json_serialized = json_serializer.serialize(&large_data).unwrap();

    group.bench_with_input(
        BenchmarkId::new("json_deserialize", "small"),
        &small_json_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(json_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("json_deserialize", "medium"),
        &medium_json_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(json_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.bench_with_input(
        BenchmarkId::new("json_deserialize", "large"),
        &large_json_serialized,
        |b, data| {
            b.iter(|| {
                let _ = black_box(json_serializer.deserialize(black_box(data)).unwrap());
            })
        },
    );

    group.finish();
}
