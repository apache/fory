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

pub mod fory;
pub mod json;
pub mod protobuf;

use chrono::{DateTime, NaiveDateTime, Utc};
use prost_types::Timestamp;

pub trait Serializer<T> {
    fn serialize(&self, data: &T) -> Result<Vec<u8>, Box<dyn std::error::Error>>;
    fn deserialize(&self, data: &[u8]) -> Result<T, Box<dyn std::error::Error>>;
}

// Helper functions for protobuf conversion
pub fn naive_datetime_to_timestamp(dt: NaiveDateTime) -> Timestamp {
    Timestamp {
        seconds: dt.and_utc().timestamp(),
        nanos: dt.and_utc().timestamp_subsec_nanos() as i32,
    }
}

pub fn timestamp_to_naive_datetime(ts: Timestamp) -> NaiveDateTime {
    DateTime::from_timestamp(ts.seconds, ts.nanos as u32)
        .unwrap()
        .naive_utc()
}

pub fn datetime_to_timestamp(dt: DateTime<Utc>) -> Timestamp {
    Timestamp {
        seconds: dt.timestamp(),
        nanos: dt.timestamp_subsec_nanos() as i32,
    }
}

pub fn timestamp_to_datetime(ts: Timestamp) -> DateTime<Utc> {
    DateTime::from_timestamp(ts.seconds, ts.nanos as u32).unwrap()
}
