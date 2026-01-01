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

mod string_util;
mod sync;

pub use string_util::{to_camel_case, to_snake_case, to_utf8};
pub use sync::{Spinlock, SpinlockGuard};

use chrono::NaiveDate;

pub const EPOCH: NaiveDate = match NaiveDate::from_ymd_opt(1970, 1, 1) {
    None => {
        panic!("Unreachable code")
    }
    Some(epoch) => epoch,
};

/// Global flag to check if ENABLE_FORY_DEBUG_OUTPUT environment variable is set at compile time.
/// Set ENABLE_FORY_DEBUG_OUTPUT=1 at compile time to enable debug output.
pub const ENABLE_FORY_DEBUG_OUTPUT: bool = option_env!("ENABLE_FORY_DEBUG_OUTPUT").is_some();
