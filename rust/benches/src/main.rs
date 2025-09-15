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

#[derive(Debug, Clone, Copy, ValueEnum)]
pub enum SerializerType {
    Protobuf,
    Fory,
}

#[derive(Parser)]
#[command(name = "fory_profiler")]
#[command(about = "A profiler for Fory and Protobuf serialization performance")]
struct Cli {
    /// The operation to perform
    #[arg(short, long, value_enum)]
    operation: Operation,

    /// The serializer to use
    #[arg(short, long, value_enum)]
    serializer: SerializerType,

    /// Number of iterations to run
    #[arg(short, long, default_value = "10000000")]
    iterations: usize,

    /// Data size to use (small, medium, large)
    #[arg(short, long, default_value = "large")]
    data_size: String,
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

fn main() {
    let cli = Cli::parse();

    println!("Starting profiling...");
    println!("Operation: {:?}", cli.operation);
    println!("Serializer: {:?}", cli.serializer);
    println!("Iterations: {}", cli.iterations);
    println!("Data size: {}", cli.data_size);

    // Generate data based on size parameter
    let data = match cli.data_size.as_str() {
        "small" => ECommerceData::generate_small(),
        "medium" => ECommerceData::generate_medium(),
        "large" => ECommerceData::generate_large(),
        _ => {
            eprintln!(
                "Invalid data size: {}. Using 'large' instead.",
                cli.data_size
            );
            ECommerceData::generate_large()
        }
    };

    // Create serializer based on type
    match cli.serializer {
        SerializerType::Protobuf => {
            let serializer = ProtobufSerializer::new();
            profile(cli.iterations, &data, &serializer, cli.operation);
        }
        SerializerType::Fory => {
            let serializer = ForySerializer::new();
            profile(cli.iterations, &data, &serializer, cli.operation);
        }
    }

    println!("\nProfiling completed!");
}
