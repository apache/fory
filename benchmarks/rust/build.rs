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

use std::path::Path;

fn main() {
    println!("cargo:warning=Build script running");
    println!(
        "cargo:warning=OUT_DIR: {}",
        std::env::var("OUT_DIR").unwrap()
    );

    let proto_files = [
        "proto/simple.proto",
        "proto/medium.proto",
        "proto/complex.proto",
        "proto/realworld.proto",
    ];

    for proto_file in &proto_files {
        if Path::new(proto_file).exists() {
            println!("cargo:rerun-if-changed={}", proto_file);
            println!("cargo:warning=Found proto file: {}", proto_file);
        } else {
            println!("cargo:warning=Proto file not found: {}", proto_file);
        }
    }

    let mut config = prost_build::Config::new();
    // Don't set out_dir, use the default OUT_DIR

    println!("cargo:warning=About to compile protobuf files");
    config
        .compile_protos(&proto_files, &["proto/"])
        .expect("Failed to compile protobuf files");
    println!("cargo:warning=Protobuf compilation completed");
}
