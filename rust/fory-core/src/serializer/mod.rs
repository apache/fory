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

pub mod any;
mod arc;
mod bool;
mod box_;
pub mod collection;
mod datetime;
pub mod enum_;
mod heap;
mod list;
pub mod map;
mod mutex;
mod number;
mod option;
mod primitive_list;
mod rc;
mod refcell;
mod set;
pub mod skip;
mod string;
pub mod struct_;
pub mod trait_object;
mod tuple;
mod unsigned_number;
pub mod util;
pub mod weak;

mod core;
pub use any::{read_box_any, write_box_any};
pub use core::{read_data, write_data, ForyDefault, Serializer, StructSerializer};
