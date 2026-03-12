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
use fory_benchmarks::models::complex::ECommerceData;
use fory_benchmarks::models::medium::{Company, Person};
use fory_benchmarks::models::realworld::SystemData;
use fory_benchmarks::models::simple::{SimpleList, SimpleMap, SimpleStruct};
use fory_benchmarks::models::TestDataGenerator;
use fory_benchmarks::serializers::fory::ForySerializer;
use fory_benchmarks::serializers::protobuf::ProtobufSerializer;
use fory_benchmarks::serializers::Serializer;
use std::hint::black_box;

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum Operation {
    Serialize,
    Deserialize,
}

#[derive(Debug, Clone, Copy, ValueEnum, PartialEq)]
pub enum SerializerType {
    Protobuf,
    Fory,
}

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum DataType {
    SimpleStruct,
    SimpleList,
    SimpleMap,
    Person,
    Company,
    ECommerceData,
    SystemData,
}

#[derive(Parser)]
#[command(name = "fory_profiler")]
#[command(about = "A profiler for Fory and Protobuf serialization performance")]
struct Cli {
    /// The operation to perform
    #[arg(short, long, value_enum, default_value_t = Operation::Serialize)]
    operation: Operation,

    /// The serializer to use
    #[arg(short, long, value_enum, default_value_t = SerializerType::Fory)]
    serializer: SerializerType,

    /// Number of iterations to run
    #[arg(short, long, default_value = "10000000")]
    iterations: usize,

    /// Data size to use (small, medium, large)
    #[arg(short, long, default_value = "large")]
    data_size: String,

    /// Data type to profile
    #[arg(short = 't', long, value_enum, default_value = "e-commerce-data")]
    data_type: DataType,

    /// Print serialized sizes for all data types and serializers, then exit
    #[arg(long)]
    print_all_serialized_sizes: bool,
}

fn profile<T, S>(num_iterations: usize, data: &T, serializer: &S, operation: Operation)
where
    T: Clone,
    S: Serializer<T>,
{
    match operation {
        Operation::Serialize => {
            println!(
                "Starting serialization profiling with {} iterations...",
                num_iterations
            );

            // Warm up - run a few iterations to get the code in cache
            for _ in 0..1000 {
                let _ = black_box(serializer.serialize(black_box(data)).unwrap());
            }

            println!("Warmup complete. Starting main profiling loop...");

            // Main profiling loop
            for i in 0..num_iterations {
                let _ = black_box(serializer.serialize(black_box(data)).unwrap());

                // Print progress every million iterations
                if i % 1_000_000 == 0 && i > 0 {
                    println!("Completed {} iterations", i);
                }
            }
        }
        Operation::Deserialize => {
            println!(
                "Starting deserialization profiling with {} iterations...",
                num_iterations
            );

            // Pre-serialize the data once
            let serialized_data = serializer.serialize(data).unwrap();
            println!("Serialized data size: {} bytes", serialized_data.len());

            // Warm up - run a few iterations to get the code in cache
            for _ in 0..1000 {
                let _: T = black_box(serializer.deserialize(black_box(&serialized_data)).unwrap());
            }

            println!("Warmup complete. Starting main profiling loop...");

            // Main profiling loop
            for i in 0..num_iterations {
                let _: T = black_box(serializer.deserialize(black_box(&serialized_data)).unwrap());

                // Print progress every million iterations
                if i % 1_000_000 == 0 && i > 0 {
                    println!("Completed {} iterations", i);
                }
            }
        }
    }

    println!("Profiling complete! Ran {} iterations", num_iterations);
}

fn profile_with_serializer<T>(
    num_iterations: usize,
    data: &T,
    serializer_type: SerializerType,
    operation: Operation,
) where
    T: Clone,
    ProtobufSerializer: Serializer<T>,
    ForySerializer: Serializer<T>,
{
    match serializer_type {
        SerializerType::Protobuf => {
            let serializer = ProtobufSerializer::new();
            profile(num_iterations, data, &serializer, operation);
        }
        SerializerType::Fory => {
            let serializer = ForySerializer::new();
            profile(num_iterations, data, &serializer, operation);
        }
    }
}

fn generate_data<T: TestDataGenerator>(data_size: &str) -> T::Data {
    match data_size {
        "small" => T::generate_small(),
        "medium" => T::generate_medium(),
        "large" => T::generate_large(),
        _ => {
            eprintln!("Invalid data size: {}. Using 'large' instead.", data_size);
            T::generate_large()
        }
    }
}

fn print_sizes_for_type<T>(type_label: &str, data_sizes: &[&str])
where
    T: TestDataGenerator,
    T::Data: Clone,
    ProtobufSerializer: Serializer<T::Data>,
    ForySerializer: Serializer<T::Data>,
{
    for data_size in data_sizes {
        let data = generate_data::<T>(data_size);
        let mut results = Vec::new();
        for serializer_type in SerializerType::value_variants() {
            let serializer_kind = *serializer_type;
            let result = match serializer_kind {
                SerializerType::Protobuf => ProtobufSerializer::new().serialize(&data),
                SerializerType::Fory => ForySerializer::new().serialize(&data),
            };
            results.push((serializer_kind, result));
        }

        let fory_size = results
            .iter()
            .find(|(kind, _)| *kind == SerializerType::Fory)
            .and_then(|(_, result)| result.as_ref().ok().map(|bytes| bytes.len()))
            .map(|size| size.to_string())
            .unwrap_or_else(|| "error".to_string());

        let protobuf_size = results
            .iter()
            .find(|(kind, _)| *kind == SerializerType::Protobuf)
            .and_then(|(_, result)| result.as_ref().ok().map(|bytes| bytes.len()))
            .map(|size| size.to_string())
            .unwrap_or_else(|| "error".to_string());

        println!(
            "| {:<11} | {:<9} | {:>6} | {:>8} |",
            type_label, data_size, fory_size, protobuf_size
        );
    }
}

fn print_all_serialized_sizes() {
    const DATA_SIZES: [&str; 3] = ["small", "medium", "large"];
    println!("| data type   | data size |   fory | protobuf |");
    println!("|-------------|-----------|--------|----------|");
    for data_type in DataType::value_variants() {
        match data_type {
            DataType::SimpleStruct => {
                print_sizes_for_type::<SimpleStruct>("simple-struct", &DATA_SIZES)
            }
            DataType::SimpleList => print_sizes_for_type::<SimpleList>("simple-list", &DATA_SIZES),
            DataType::SimpleMap => print_sizes_for_type::<SimpleMap>("simple-map", &DATA_SIZES),
            DataType::Person => print_sizes_for_type::<Person>("person", &DATA_SIZES),
            DataType::Company => print_sizes_for_type::<Company>("company", &DATA_SIZES),
            DataType::ECommerceData => {
                print_sizes_for_type::<ECommerceData>("e-commerce-data", &DATA_SIZES)
            }
            DataType::SystemData => print_sizes_for_type::<SystemData>("system-data", &DATA_SIZES),
        }
    }
}

fn main() {
    let cli = Cli::parse();

    if cli.print_all_serialized_sizes {
        println!("Serialized sizes (bytes) for all data types and serializers");
        print_all_serialized_sizes();
        return;
    }

    println!("Starting profiling...");
    println!("Operation: {:?}", cli.operation);
    println!("Serializer: {:?}", cli.serializer);
    println!("Data type: {:?}", cli.data_type);
    println!("Iterations: {}", cli.iterations);
    println!("Data size: {}", cli.data_size);

    // Generate data and profile based on data type
    match cli.data_type {
        DataType::SimpleStruct => {
            let data = generate_data::<SimpleStruct>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
        DataType::SimpleList => {
            let data = generate_data::<SimpleList>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
        DataType::SimpleMap => {
            let data = generate_data::<SimpleMap>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
        DataType::Person => {
            let data = generate_data::<Person>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
        DataType::Company => {
            let data = generate_data::<Company>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
        DataType::ECommerceData => {
            let data = generate_data::<ECommerceData>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
        DataType::SystemData => {
            let data = generate_data::<SystemData>(&cli.data_size);
            profile_with_serializer(cli.iterations, &data, cli.serializer, cli.operation);
        }
    }

    println!("\nProfiling completed!");
}
