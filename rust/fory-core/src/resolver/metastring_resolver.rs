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

use crate::meta::{MetaString, MetaStringBytes};
use std::collections::HashMap;

#[derive(Default)]
pub struct MetaStringResolver {
    metastring_to_bytes_map: HashMap<MetaString, MetaStringBytes>,
}

impl MetaStringResolver {
    pub fn new() -> Self {
        Self {
            metastring_to_bytes_map: HashMap::new(),
        }
    }

    pub fn encode_metastring(&mut self, str: &MetaString) -> MetaStringBytes {
        if let Some(existing) = self.metastring_to_bytes_map.get(str) {
            return existing.clone();
        }
        let metastring_bytes = MetaStringBytes::from(str);
        self.metastring_to_bytes_map
            .insert(str.clone(), metastring_bytes.clone());
        metastring_bytes
    }
}
