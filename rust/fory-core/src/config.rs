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

/// Configuration for Fory serialization.
///
/// This struct holds all the configuration options that control how Fory
/// serializes and deserializes data. It is shared between the main `Fory`
/// instance and the `WriteContext`/`ReadContext` to ensure consistent behavior.
#[derive(Clone, Debug)]
pub struct Config {
    /// Whether compatible mode is enabled for schema evolution support.
    pub compatible: bool,
    /// Whether cross-language serialization is enabled.
    pub xlang: bool,
    /// Whether metadata sharing is enabled.
    pub share_meta: bool,
    /// Whether meta string compression is enabled.
    pub compress_string: bool,
    /// Maximum depth for nested dynamic object serialization.
    pub max_dyn_depth: u32,
    /// Whether class version checking is enabled.
    pub check_struct_version: bool,
    /// Whether reference tracking is enabled.
    /// When enabled, shared references and circular references are tracked
    /// and preserved during serialization/deserialization.
    pub track_ref: bool,
    /// Maximum byte length of a single deserialized string. `None` = unlimited.
    pub max_string_bytes: Option<usize>,
    /// Maximum element count of a single deserialized collection. `None` = unlimited.
    pub max_collection_size: Option<usize>,
    /// Maximum entry count of a single deserialized map. `None` = unlimited.
    pub max_map_size: Option<usize>,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            compatible: false,
            xlang: false,
            share_meta: false,
            compress_string: false,
            max_dyn_depth: 5,
            check_struct_version: false,
            track_ref: false,
            max_string_bytes: None,
            max_collection_size: None,
            max_map_size: None,
        }
    }
}

impl Config {
    /// Creates a new Config with default values.
    pub fn new() -> Self {
        Self::default()
    }

    /// Check if compatible mode is enabled.
    #[inline(always)]
    pub fn is_compatible(&self) -> bool {
        self.compatible
    }

    /// Check if cross-language mode is enabled.
    #[inline(always)]
    pub fn is_xlang(&self) -> bool {
        self.xlang
    }

    /// Check if meta sharing is enabled.
    #[inline(always)]
    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    /// Check if string compression is enabled.
    #[inline(always)]
    pub fn is_compress_string(&self) -> bool {
        self.compress_string
    }

    /// Get maximum dynamic depth.
    #[inline(always)]
    pub fn max_dyn_depth(&self) -> u32 {
        self.max_dyn_depth
    }

    /// Check if class version checking is enabled.
    #[inline(always)]
    pub fn is_check_struct_version(&self) -> bool {
        self.check_struct_version
    }

    /// Check if reference tracking is enabled.
    #[inline(always)]
    pub fn is_track_ref(&self) -> bool {
        self.track_ref
    }

    #[inline(always)]
    pub fn max_string_bytes(&self) -> Option<usize> {
        self.max_string_bytes
    }

    #[inline(always)]
    pub fn max_collection_size(&self) -> Option<usize> {
        self.max_collection_size
    }

    #[inline(always)]
    pub fn max_map_size(&self) -> Option<usize> {
        self.max_map_size
    }
}
