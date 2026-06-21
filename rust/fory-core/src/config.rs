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
    /// Whether xlang mode is enabled.
    pub xlang: bool,
    /// Whether metadata sharing is enabled.
    pub share_meta: bool,
    /// Whether meta string compression is enabled.
    pub compress_string: bool,
    /// Whether UTF-8 string payloads are validated before constructing Rust strings.
    pub check_string_read: bool,
    /// Maximum depth for nested dynamic object serialization.
    pub max_dyn_depth: u32,
    /// Whether class version checking is enabled.
    pub check_struct_version: bool,
    /// Whether reference tracking is enabled.
    /// When enabled, shared references and circular references are tracked
    /// and preserved during serialization/deserialization.
    pub track_ref: bool,
    /// Maximum accepted field count in one received struct TypeMeta.
    pub max_type_fields: u32,
    /// Maximum accepted body size in one received TypeMeta.
    pub max_type_meta_bytes: u32,
    /// Maximum accepted remote metadata versions for one logical type.
    pub max_schema_versions_per_type: u32,
    /// Maximum accepted average remote metadata versions across logical types.
    pub max_average_schema_versions_per_type: u32,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            compatible: false,
            xlang: true,
            share_meta: false,
            compress_string: false,
            check_string_read: true,
            max_dyn_depth: 5,
            check_struct_version: false,
            track_ref: false,
            max_type_fields: 512,
            max_type_meta_bytes: 4096,
            max_schema_versions_per_type: 10,
            max_average_schema_versions_per_type: 3,
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

    /// Check if xlang mode is enabled.
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

    /// Check if UTF-8 string payload validation is enabled.
    #[inline(always)]
    pub fn is_check_string_read(&self) -> bool {
        self.check_string_read
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

    /// Get maximum accepted field count in one received struct TypeMeta.
    #[inline(always)]
    pub fn max_type_fields(&self) -> usize {
        self.max_type_fields as usize
    }

    /// Get maximum accepted body size in one received TypeMeta.
    #[inline(always)]
    pub fn max_type_meta_bytes(&self) -> usize {
        self.max_type_meta_bytes as usize
    }

    /// Get maximum accepted remote metadata versions for one logical type.
    #[inline(always)]
    pub fn max_schema_versions_per_type(&self) -> usize {
        self.max_schema_versions_per_type as usize
    }

    /// Get maximum accepted average remote metadata versions across logical types.
    #[inline(always)]
    pub fn max_average_schema_versions_per_type(&self) -> usize {
        self.max_average_schema_versions_per_type as usize
    }
}
