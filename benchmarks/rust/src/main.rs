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

use clap::{Parser, ValueEnum};
use fory_benchmarks::data::{
    BenchmarkCase, DataKind, MediaContent, MediaContentList, NumericStruct, NumericStructList,
    Sample, SampleList, SchemaMismatchCase,
};
use fory_benchmarks::serializers::{
    fory::{schema_mismatch_enabled, ForySerializer},
    msgpack::MsgpackSerializer,
    protobuf::ProtobufSerializer,
    BenchmarkSerializer,
};
use std::hint::black_box;

#[derive(Debug, Clone, Copy, ValueEnum)]
enum Operation {
    Serialize,
    Deserialize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
enum SerializerKind {
    Fory,
    Protobuf,
    Msgpack,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
enum DataType {
    Struct,
    Sample,
    Mediacontent,
    Structlist,
    Samplelist,
    Mediacontentlist,
}

#[derive(Parser)]
#[command(name = "fory_profiler")]
#[command(about = "Profile Rust benchmark serializers against the shared bench.proto cases")]
struct Cli {
    #[arg(short, long, value_enum, default_value_t = Operation::Serialize)]
    operation: Operation,

    #[arg(short, long, value_enum, default_value_t = SerializerKind::Fory)]
    serializer: SerializerKind,

    #[arg(short, long, default_value_t = 10_000_000)]
    iterations: usize,

    #[arg(short = 't', long, value_enum, default_value_t = DataType::Mediacontent)]
    data_type: DataType,

    #[arg(long)]
    print_all_serialized_sizes: bool,
}

fn profile<T, S>(iterations: usize, value: &T, serializer: &S, operation: Operation)
where
    S: BenchmarkSerializer<T>,
{
    match operation {
        Operation::Serialize => {
            for _ in 0..1000 {
                let _ = black_box(serializer.serialize(black_box(value)).unwrap());
            }

            for _ in 0..iterations {
                let _ = black_box(serializer.serialize(black_box(value)).unwrap());
            }
        }
        Operation::Deserialize => {
            let bytes = serializer.serialize(value).unwrap();

            for _ in 0..1000 {
                let _: T = black_box(serializer.deserialize(black_box(&bytes)).unwrap());
            }

            for _ in 0..iterations {
                let _: T = black_box(serializer.deserialize(black_box(&bytes)).unwrap());
            }
        }
    }
}

fn profile_case<T>(iterations: usize, serializer: SerializerKind, operation: Operation)
where
    T: SchemaMismatchCase,
    ForySerializer: BenchmarkSerializer<T>,
    ProtobufSerializer: BenchmarkSerializer<T>,
    MsgpackSerializer: BenchmarkSerializer<T>,
{
    let value = T::create();
    let mismatch = schema_mismatch_enabled();
    if mismatch && serializer != SerializerKind::Fory {
        panic!(
            "FORY_BENCH_SCHEMA_MISMATCH=1 supports only Fory benchmarks; rerun with --serializer fory"
        );
    }

    match serializer {
        SerializerKind::Fory if mismatch => {
            profile_fory_mismatch::<T>(iterations, &value, operation)
        }
        SerializerKind::Fory => profile(iterations, &value, &ForySerializer::new(), operation),
        SerializerKind::Protobuf => {
            profile(iterations, &value, &ProtobufSerializer::new(), operation)
        }
        SerializerKind::Msgpack => {
            profile(iterations, &value, &MsgpackSerializer::new(), operation)
        }
    }
}

fn profile_fory_mismatch<T>(iterations: usize, value: &T, operation: Operation)
where
    T: SchemaMismatchCase,
    ForySerializer: BenchmarkSerializer<T>,
{
    let serializer = ForySerializer::new();
    match operation {
        Operation::Serialize => {
            for _ in 0..1000 {
                let _ = black_box(serializer.serialize(black_box(value)).unwrap());
            }

            for _ in 0..iterations {
                let _ = black_box(serializer.serialize(black_box(value)).unwrap());
            }
        }
        Operation::Deserialize => {
            let bytes = serializer.serialize(value).unwrap();
            let decoded: T::Read = serializer.deserialize_as(&bytes).unwrap();
            T::verify_mismatch(&decoded, value);

            for _ in 0..1000 {
                let value: T::Read =
                    black_box(serializer.deserialize_as(black_box(&bytes)).unwrap());
                black_box(value);
            }

            for _ in 0..iterations {
                let value: T::Read =
                    black_box(serializer.deserialize_as(black_box(&bytes)).unwrap());
                black_box(value);
            }
        }
    }
}

fn print_size_row<T>(label: &str)
where
    T: BenchmarkCase,
    ForySerializer: BenchmarkSerializer<T>,
    ProtobufSerializer: BenchmarkSerializer<T>,
    MsgpackSerializer: BenchmarkSerializer<T>,
{
    let value = T::create();
    let fory = ForySerializer::new().serialize(&value).unwrap().len();
    if schema_mismatch_enabled() {
        println!("| {label} | {fory} | n/a | n/a |");
        return;
    }

    let protobuf = ProtobufSerializer::new().serialize(&value).unwrap().len();
    let msgpack = MsgpackSerializer::new().serialize(&value).unwrap().len();

    println!("| {label} | {fory} | {protobuf} | {msgpack} |");
}

fn print_all_serialized_sizes() {
    println!("| Datatype | fory | protobuf | msgpack |");
    println!("|----------|------|----------|---------|");
    print_size_row::<NumericStruct>(DataKind::Struct.display_name());
    print_size_row::<Sample>(DataKind::Sample.display_name());
    print_size_row::<MediaContent>(DataKind::MediaContent.display_name());
    print_size_row::<NumericStructList>(DataKind::NumericStructList.display_name());
    print_size_row::<SampleList>(DataKind::SampleList.display_name());
    print_size_row::<MediaContentList>(DataKind::MediaContentList.display_name());
}

fn main() {
    let cli = Cli::parse();
    if schema_mismatch_enabled() && cli.serializer != SerializerKind::Fory {
        panic!(
            "FORY_BENCH_SCHEMA_MISMATCH=1 supports only Fory benchmarks; rerun with --serializer fory"
        );
    }

    if cli.print_all_serialized_sizes {
        print_all_serialized_sizes();
        return;
    }

    match cli.data_type {
        DataType::Struct => {
            profile_case::<NumericStruct>(cli.iterations, cli.serializer, cli.operation)
        }
        DataType::Sample => profile_case::<Sample>(cli.iterations, cli.serializer, cli.operation),
        DataType::Mediacontent => {
            profile_case::<MediaContent>(cli.iterations, cli.serializer, cli.operation)
        }
        DataType::Structlist => {
            profile_case::<NumericStructList>(cli.iterations, cli.serializer, cli.operation)
        }
        DataType::Samplelist => {
            profile_case::<SampleList>(cli.iterations, cli.serializer, cli.operation)
        }
        DataType::Mediacontentlist => {
            profile_case::<MediaContentList>(cli.iterations, cli.serializer, cli.operation)
        }
    }
}
