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
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::{ForyDefault, Serializer};
use crate::types::RefFlag;
use crate::types::TypeId;
use std::sync::Arc;

impl<T: Serializer + ForyDefault + Send + Sync + 'static> Serializer for Arc<T> {
    fn fory_is_shared_ref() -> bool {
        true
    }

    fn fory_write(
        &self,
        context: &mut WriteContext,
        write_ref_info: bool,
        write_type_info: bool,
        has_generics: bool,
    ) -> Result<(), Error> {
        if !write_ref_info
            || !context
                .ref_writer
                .try_write_arc_ref(&mut context.writer, self)
        {
            if T::fory_is_shared_ref() || T::fory_is_polymorphic() {
                let inner_write_ref = T::fory_is_shared_ref();
                return T::fory_write(
                    &**self,
                    context,
                    inner_write_ref,
                    write_type_info,
                    has_generics,
                );
            }
            if write_type_info {
                T::fory_write_type_info(context)?;
            }
            T::fory_write_data_generic(self, context, has_generics)
        } else {
            Ok(())
        }
    }

    fn fory_write_data_generic(&self, _: &mut WriteContext, _: bool) -> Result<(), Error> {
        panic!("Arc<T> should be written using `fory_write` to handle reference tracking properly");
    }

    fn fory_write_data(&self, _: &mut WriteContext) -> Result<(), Error> {
        panic!("Arc<T> should be written using `fory_write` to handle reference tracking properly");
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        T::fory_write_type_info(context)
    }

    fn fory_read(
        context: &mut ReadContext,
        read_ref_info: bool,
        read_type_info: bool,
    ) -> Result<Self, Error> {
        // if read_ref_info is false, shared refs will be deserialized into new objects each time.
        read_arc(context, read_ref_info, read_type_info, None)
    }

    fn fory_read_with_type_info(
        context: &mut ReadContext,
        read_ref_info: bool,
        typeinfo: Arc<TypeInfo>,
    ) -> Result<Self, Error>
    where
        Self: Sized + ForyDefault,
    {
        read_arc(context, read_ref_info, false, Some(typeinfo))
    }

    fn fory_read_data(_: &mut ReadContext) -> Result<Self, Error> {
        panic!("Arc<T> should be read using `fory_read/fory_read_with_type_info` to handle reference tracking properly");
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        T::fory_read_type_info(context)
    }

    fn fory_reserved_space() -> usize {
        // Arc is a shared ref, so we just need space for the ref tracking
        // We don't recursively compute inner type's space to avoid infinite recursion
        4
    }

    fn fory_get_type_id(type_resolver: &TypeResolver) -> Result<u32, Error> {
        T::fory_get_type_id(type_resolver)
    }

    fn fory_type_id_dyn(&self, type_resolver: &TypeResolver) -> Result<u32, Error> {
        (**self).fory_type_id_dyn(type_resolver)
    }

    fn fory_static_type_id() -> TypeId {
        T::fory_static_type_id()
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }
}

fn read_arc<T: Serializer + ForyDefault + 'static>(
    context: &mut ReadContext,
    read_ref_info: bool,
    read_type_info: bool,
    typeinfo: Option<Arc<TypeInfo>>,
) -> Result<Arc<T>, Error> {
    let ref_flag = if read_ref_info {
        context.ref_reader.read_ref_flag(&mut context.reader)?
    } else {
        RefFlag::NotNullValue
    };
    match ref_flag {
        RefFlag::Null => Err(Error::invalid_ref("Arc cannot be null")),
        RefFlag::Ref => {
            let ref_id = context.ref_reader.read_ref_id(&mut context.reader)?;
            context
                .ref_reader
                .get_arc_ref::<T>(ref_id)
                .ok_or_else(|| Error::invalid_ref(format!("Arc reference {ref_id} not found")))
        }
        RefFlag::NotNullValue => {
            let inner = read_arc_inner::<T>(context, read_type_info, typeinfo)?;
            Ok(Arc::new(inner))
        }
        RefFlag::RefValue => {
            let ref_id = context.ref_reader.reserve_ref_id();
            let inner = read_arc_inner::<T>(context, read_type_info, typeinfo)?;
            let arc = Arc::new(inner);
            context.ref_reader.store_arc_ref_at(ref_id, arc.clone());
            Ok(arc)
        }
    }
}

fn read_arc_inner<T: Serializer + ForyDefault + 'static>(
    context: &mut ReadContext,
    read_type_info: bool,
    typeinfo: Option<Arc<TypeInfo>>,
) -> Result<T, Error> {
    if let Some(typeinfo) = typeinfo {
        let inner_read_ref = T::fory_is_shared_ref();
        return T::fory_read_with_type_info(context, inner_read_ref, typeinfo);
    }
    if T::fory_is_shared_ref() || T::fory_is_polymorphic() {
        let inner_read_ref = T::fory_is_shared_ref();
        return T::fory_read(context, inner_read_ref, read_type_info);
    }
    if read_type_info {
        T::fory_read_type_info(context)?;
    }
    T::fory_read_data(context)
}

impl<T: ForyDefault> ForyDefault for Arc<T> {
    fn fory_default() -> Self {
        Arc::new(T::fory_default())
    }
}
