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

use fory_core::meta::{FieldInfo, FieldType, MetaString, TypeMeta};

#[test]
fn test_meta_hash() {
    let meta = TypeMeta::new(
        42,
        u32::MAX,
        MetaString::get_empty().clone(),
        MetaString::get_empty().clone(),
        false,
        vec![FieldInfo {
            field_id: 43,
            field_name: "f1".to_string(),
            field_type: FieldType {
                type_id: 44,
                nullable: true,
                ref_tracking: false,
                generics: vec![],
            },
        }],
    )
    .unwrap();
    assert_ne!(meta.get_hash(), 0);
}
