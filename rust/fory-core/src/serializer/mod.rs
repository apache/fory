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
pub mod util;
pub mod weak;

pub mod serializer;
pub use any::{read_arc_any, read_box_any, read_rc_any, write_box_any};
pub use serializer::{
    CollectionSerializer, ForyDefault, MapSerializer, Serializer, StructSerializer,
};
pub use util::{
    get_skip_ref_flag, read_ref_info_data, read_type_info, write_ref_info_data, write_type_info,
};
