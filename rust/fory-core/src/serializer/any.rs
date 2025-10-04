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

use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::types::RefFlag;
use std::any::Any;

/// Helper function to serialize a Box<dyn Any>
pub fn serialize_any_box(any_box: &Box<dyn Any>, context: &mut WriteContext, is_field: bool) {
    context.writer.write_i8(RefFlag::NotNullValue as i8);

    let concrete_type_id = (**any_box).type_id();

    let fory_type_id = context
        .get_fory()
        .get_type_resolver()
        .get_fory_type_id(concrete_type_id)
        .expect("Type not registered");

    context.writer.write_varuint32(fory_type_id);

    let harness = context
        .get_fory()
        .get_type_resolver()
        .get_harness(fory_type_id)
        .expect("Harness not found");

    let serializer_fn = harness.get_serializer();
    serializer_fn(&**any_box, context, is_field);
}

/// Helper function to deserialize to Box<dyn Any>
pub fn deserialize_any_box(context: &mut ReadContext) -> Result<Box<dyn Any>, Error> {
    let ref_flag = context.reader.read_i8();
    if ref_flag != RefFlag::NotNullValue as i8 {
        return Err(Error::Other(anyhow::anyhow!(
            "Expected NotNullValue for Box<dyn Any>"
        )));
    }

    let fory_type_id = context.reader.read_varuint32();

    let harness = context
        .get_fory()
        .get_type_resolver()
        .get_harness(fory_type_id)
        .ok_or_else(|| Error::Other(anyhow::anyhow!("Type {} not registered", fory_type_id)))?;

    let deserializer_fn = harness.get_deserializer();
    deserializer_fn(context, true, true)
}
