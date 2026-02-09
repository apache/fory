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

pub mod generated {
    #[path = "../generated/addressbook.rs"]
    pub mod addressbook;
    #[path = "../generated/any_example.rs"]
    pub mod any_example;
    #[path = "../generated/any_example_pb.rs"]
    pub mod any_example_pb;
    #[path = "../generated/auto_id.rs"]
    pub mod auto_id;
    #[path = "../generated/collection.rs"]
    pub mod collection;
    #[path = "../generated/complex_fbs.rs"]
    pub mod complex_fbs;
    #[path = "../generated/complex_pb.rs"]
    pub mod complex_pb;
    #[path = "../generated/evolving1.rs"]
    pub mod evolving1;
    #[path = "../generated/evolving2.rs"]
    pub mod evolving2;
    #[path = "../generated/graph.rs"]
    pub mod graph;
    #[path = "../generated/monster.rs"]
    pub mod monster;
    #[path = "../generated/optional_types.rs"]
    pub mod optional_types;
    #[path = "../generated/root.rs"]
    pub mod root;
    #[path = "../generated/tree.rs"]
    pub mod tree;
}
pub use generated::*;
