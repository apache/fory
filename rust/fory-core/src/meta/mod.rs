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

mod meta_string;
mod string_util;
mod type_meta;

pub use meta_string::{
    Encoding, MetaString, MetaStringDecoder, MetaStringEncoder, FIELD_NAME_DECODER,
    FIELD_NAME_ENCODER, NAMESPACE_DECODER, NAMESPACE_ENCODER, TYPE_NAME_DECODER, TYPE_NAME_ENCODER,
};
pub use string_util::{buffer_rw_string, get_latin1_length, is_latin, murmurhash3_x64_128};
pub use type_meta::{
    sort_fields, FieldInfo, FieldType, TypeMeta, TypeMetaLayer, NAMESPACE_ENCODINGS,
    TYPE_NAME_ENCODINGS,
};
