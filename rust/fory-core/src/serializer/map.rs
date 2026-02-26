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

use crate::ensure;
use crate::error::Error;
use crate::resolver::context::{ReadContext, WriteContext};
use crate::resolver::type_resolver::{TypeInfo, TypeResolver};
use crate::serializer::util::read_basic_type_info;
use crate::serializer::{ForyDefault, Serializer};
use crate::types::{need_to_write_type_for_field, RefMode, TypeId, SIZE_OF_REF_AND_TYPE};
use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;

const MAX_CHUNK_SIZE: u8 = 255;

const TRACKING_KEY_REF: u8 = 0b1;
pub const KEY_NULL: u8 = 0b10;
pub const DECL_KEY_TYPE: u8 = 0b100;
const TRACKING_VALUE_REF: u8 = 0b1000;
pub const VALUE_NULL: u8 = 0b10000;
pub const DECL_VALUE_TYPE: u8 = 0b100000;

fn write_chunk_size(context: &mut WriteContext, header_offset: usize, size: u8) {
    context.writer.set_bytes(header_offset + 1, &[size]);
}

pub fn write_map_data<'a, K, V, I>(
    iter: I,
    length: usize,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error>
where
    K: Serializer,
    V: Serializer,
    I: Iterator<Item = (&'a K, &'a V)>,
{
    context.writer.write_var_uint32(length as u32);
    if length == 0 {
        return Ok(());
    }
    let reserved_space = (K::fory_reserved_space() + SIZE_OF_REF_AND_TYPE) * length
        + (V::fory_reserved_space() + SIZE_OF_REF_AND_TYPE) * length;
    context.writer.reserve(reserved_space);

    if K::fory_is_polymorphic()
        || K::fory_is_shared_ref()
        || V::fory_is_polymorphic()
        || V::fory_is_shared_ref()
    {
        return write_map_data_dyn_ref(iter, context, has_generics);
    }
    let mut header_offset = 0;
    let mut pair_counter: u8 = 0;
    let mut need_write_header = true;
    let key_static_type_id = K::fory_static_type_id();
    let val_static_type_id = V::fory_static_type_id();
    let is_key_declared = has_generics && !need_to_write_type_for_field(key_static_type_id);
    let is_val_declared = has_generics && !need_to_write_type_for_field(val_static_type_id);
    for (key, value) in iter {
        let key_is_none = key.fory_is_none();
        let value_is_none = value.fory_is_none();

        if key_is_none || value_is_none {
            if !need_write_header && pair_counter > 0 {
                write_chunk_size(context, header_offset, pair_counter);
                pair_counter = 0;
                need_write_header = true;
            }

            if key_is_none && value_is_none {
                context.writer.write_u8(KEY_NULL | VALUE_NULL);
                continue;
            }

            if value_is_none {
                let mut chunk_header = VALUE_NULL;
                if is_key_declared {
                    chunk_header |= DECL_KEY_TYPE;
                    context.writer.write_u8(chunk_header);
                } else {
                    context.writer.write_u8(chunk_header);
                    K::fory_write_type_info(context)?;
                }
                key.fory_write_data_generic(context, has_generics)?;
                continue;
            }

            // key is None, value is not
            let mut chunk_header = KEY_NULL;
            if is_val_declared {
                chunk_header |= DECL_VALUE_TYPE;
                context.writer.write_u8(chunk_header);
            } else {
                context.writer.write_u8(chunk_header);
                V::fory_write_type_info(context)?;
            }
            value.fory_write_data_generic(context, has_generics)?;
            continue;
        }

        if need_write_header {
            header_offset = context.writer.len();
            context.writer.write_i16(-1);
            let mut chunk_header = 0u8;
            if is_key_declared {
                chunk_header |= DECL_KEY_TYPE;
            } else {
                K::fory_write_type_info(context)?;
            }
            if is_val_declared {
                chunk_header |= DECL_VALUE_TYPE;
            } else {
                V::fory_write_type_info(context)?;
            }
            context.writer.set_bytes(header_offset, &[chunk_header]);
            need_write_header = false;
        }

        key.fory_write_data_generic(context, has_generics)?;
        value.fory_write_data_generic(context, has_generics)?;
        pair_counter += 1;
        if pair_counter == MAX_CHUNK_SIZE {
            write_chunk_size(context, header_offset, pair_counter);
            pair_counter = 0;
            need_write_header = true;
        }
    }
    if pair_counter > 0 {
        write_chunk_size(context, header_offset, pair_counter);
    }
    Ok(())
}

/// slow but versatile map serialization for dynamic trait object and shared/circular reference.
fn write_map_data_dyn_ref<'a, K, V, I>(
    iter: I,
    context: &mut WriteContext,
    has_generics: bool,
) -> Result<(), Error>
where
    K: Serializer,
    V: Serializer,
    I: Iterator<Item = (&'a K, &'a V)>,
{
    let mut header_offset = 0;
    let mut pair_counter: u8 = 0;
    let mut need_write_header = true;
    let key_static_type_id = K::fory_static_type_id();
    let val_static_type_id = V::fory_static_type_id();
    let is_key_declared = has_generics && !need_to_write_type_for_field(key_static_type_id);
    let is_val_declared = has_generics && !need_to_write_type_for_field(val_static_type_id);
    let key_is_polymorphic = K::fory_is_polymorphic();
    let val_is_polymorphic = V::fory_is_polymorphic();
    let key_is_shared_ref = K::fory_is_shared_ref();
    let val_is_shared_ref = V::fory_is_shared_ref();

    // Track the current chunk's key and value types (for polymorphic types)
    let mut current_key_type_id: Option<std::any::TypeId> = None;
    let mut current_val_type_id: Option<std::any::TypeId> = None;

    for (key, value) in iter {
        // Handle null key/value entries (write as separate single-entry chunks)
        if key.fory_is_none() || value.fory_is_none() {
            // Finish current chunk if any
            if pair_counter > 0 {
                write_chunk_size(context, header_offset, pair_counter);
                pair_counter = 0;
                need_write_header = true;
            }

            if key.fory_is_none() && value.fory_is_none() {
                context.writer.write_u8(KEY_NULL | VALUE_NULL);
                continue;
            } else if value.fory_is_none() {
                let mut chunk_header = VALUE_NULL;
                if key_is_shared_ref {
                    chunk_header |= TRACKING_KEY_REF;
                }
                if is_key_declared && !key_is_polymorphic {
                    chunk_header |= DECL_KEY_TYPE;
                    context.writer.write_u8(chunk_header);
                } else {
                    context.writer.write_u8(chunk_header);
                    if key_is_polymorphic {
                        context.write_any_type_info(
                            K::fory_static_type_id() as u32,
                            key.fory_concrete_type_id(),
                        )?;
                    } else {
                        K::fory_write_type_info(context)?;
                    }
                }
                if key_is_shared_ref {
                    key.fory_write(context, RefMode::Tracking, false, has_generics)?;
                } else {
                    key.fory_write_data_generic(context, has_generics)?;
                }
                continue;
            } else {
                // key.fory_is_none()
                let mut chunk_header = KEY_NULL;
                if val_is_shared_ref {
                    chunk_header |= TRACKING_VALUE_REF;
                }
                if is_val_declared && !val_is_polymorphic {
                    chunk_header |= DECL_VALUE_TYPE;
                    context.writer.write_u8(chunk_header);
                } else {
                    context.writer.write_u8(chunk_header);
                    if val_is_polymorphic {
                        context.write_any_type_info(
                            V::fory_static_type_id() as u32,
                            value.fory_concrete_type_id(),
                        )?;
                    } else {
                        V::fory_write_type_info(context)?;
                    }
                }
                if val_is_shared_ref {
                    value.fory_write(context, RefMode::Tracking, false, has_generics)?;
                } else {
                    value.fory_write_data_generic(context, has_generics)?;
                }
                continue;
            }
        }

        // Get type IDs for polymorphic types
        let key_type_id = if key_is_polymorphic {
            Some(key.fory_concrete_type_id())
        } else {
            None
        };
        let val_type_id = if val_is_polymorphic {
            Some(value.fory_concrete_type_id())
        } else {
            None
        };

        // Check if we need to start a new chunk due to type changes
        let types_changed = if key_is_polymorphic || val_is_polymorphic {
            key_type_id != current_key_type_id || val_type_id != current_val_type_id
        } else {
            false
        };

        if need_write_header || types_changed {
            // Finish previous chunk if types changed
            if types_changed && pair_counter > 0 {
                write_chunk_size(context, header_offset, pair_counter);
                pair_counter = 0;
            }

            // Write new chunk header
            header_offset = context.writer.len();
            context.writer.write_i16(-1); // Placeholder for header and size

            let mut chunk_header = 0u8;

            // Set key flags
            if key_is_shared_ref {
                chunk_header |= TRACKING_KEY_REF;
            }
            if is_key_declared && !key_is_polymorphic {
                chunk_header |= DECL_KEY_TYPE;
            } else {
                // Write type info for key
                if key_is_polymorphic {
                    context.write_any_type_info(
                        K::fory_static_type_id() as u32,
                        key.fory_concrete_type_id(),
                    )?;
                } else {
                    K::fory_write_type_info(context)?;
                }
            }

            // Set value flags
            if val_is_shared_ref {
                chunk_header |= TRACKING_VALUE_REF;
            }
            if is_val_declared && !val_is_polymorphic {
                chunk_header |= DECL_VALUE_TYPE;
            } else {
                // Write type info for value
                if val_is_polymorphic {
                    context.write_any_type_info(
                        V::fory_static_type_id() as u32,
                        value.fory_concrete_type_id(),
                    )?;
                } else {
                    V::fory_write_type_info(context)?;
                }
            }

            context.writer.set_bytes(header_offset, &[chunk_header]);
            need_write_header = false;
            current_key_type_id = key_type_id;
            current_val_type_id = val_type_id;
        }

        // Write key-value pair
        if key_is_shared_ref {
            key.fory_write(context, RefMode::Tracking, false, has_generics)?;
        } else {
            key.fory_write_data_generic(context, has_generics)?;
        }
        if val_is_shared_ref {
            value.fory_write(context, RefMode::Tracking, false, has_generics)?;
        } else {
            value.fory_write_data_generic(context, has_generics)?;
        }
        pair_counter += 1;
        if pair_counter == MAX_CHUNK_SIZE {
            write_chunk_size(context, header_offset, pair_counter);
            pair_counter = 0;
            need_write_header = true;
            current_key_type_id = None;
            current_val_type_id = None;
        }
    }

    // Write final chunk size if any
    if pair_counter > 0 {
        write_chunk_size(context, header_offset, pair_counter);
    }

    Ok(())
}

/// Macro to generate read_*_data_dyn_ref functions for HashMap and BTreeMap.
/// This avoids code duplication while maintaining zero runtime cost.
macro_rules! impl_read_map_dyn_ref {
    ($fn_name:ident, $map_type:ty, $($extra_trait_bounds:tt)*) => {
        fn $fn_name<K, V>(
            context: &mut ReadContext,
            mut map: $map_type,
            length: u32,
        ) -> Result<$map_type, Error>
        where
            K: Serializer + ForyDefault + $($extra_trait_bounds)*,
            V: Serializer + ForyDefault,
        {
            let key_is_polymorphic = K::fory_is_polymorphic();
            let val_is_polymorphic = V::fory_is_polymorphic();
            let mut len_counter = 0u32;

            while len_counter < length {
                let header = context.reader.read_u8()?;

                // Handle null key/value entries
                if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                    // Both key and value are null
                    map.insert(K::fory_default(), V::fory_default());
                    len_counter += 1;
                    continue;
                }

                if header & KEY_NULL != 0 {
                    // Null key, non-null value
                    let value_declared = (header & DECL_VALUE_TYPE) != 0;
                    let track_value_ref = (header & TRACKING_VALUE_REF) != 0;

                    // Determine value type info (if any)
                    let value_type_info: Option<Rc<TypeInfo>> = if !value_declared {
                        if val_is_polymorphic {
                            Some(context.read_any_type_info()?)
                        } else {
                            V::fory_read_type_info(context)?;
                            None
                        }
                    } else {
                        None
                    };

                    // Read value payload
                    let ref_mode = if track_value_ref {
                        RefMode::Tracking
                    } else {
                        RefMode::None
                    };
                    let value = if let Some(type_info) = value_type_info {
                        V::fory_read_with_type_info(context, ref_mode, type_info)?
                    } else if track_value_ref {
                        V::fory_read(context, ref_mode, false)?
                    } else {
                        V::fory_read_data(context)?
                    };

                    map.insert(K::fory_default(), value);
                    len_counter += 1;
                    continue;
                }

                if header & VALUE_NULL != 0 {
                    // Non-null key, null value
                    let key_declared = (header & DECL_KEY_TYPE) != 0;
                    let track_key_ref = (header & TRACKING_KEY_REF) != 0;

                    let key_type_info: Option<Rc<TypeInfo>> = if !key_declared {
                        if key_is_polymorphic {
                            Some(context.read_any_type_info()?)
                        } else {
                            K::fory_read_type_info(context)?;
                            None
                        }
                    } else {
                        None
                    };

                    let ref_mode = if track_key_ref {
                        RefMode::Tracking
                    } else {
                        RefMode::None
                    };
                    let key = if let Some(type_info) = key_type_info {
                        K::fory_read_with_type_info(context, ref_mode, type_info)?
                    } else if track_key_ref {
                        K::fory_read(context, ref_mode, false)?
                    } else {
                        K::fory_read_data(context)?
                    };

                    map.insert(key, V::fory_default());
                    len_counter += 1;
                    continue;
                }

                // Non-null key and value chunk
                let chunk_size = context.reader.read_u8()?;
                let key_declared = (header & DECL_KEY_TYPE) != 0;
                let value_declared = (header & DECL_VALUE_TYPE) != 0;
                let track_key_ref = (header & TRACKING_KEY_REF) != 0;
                let track_value_ref = (header & TRACKING_VALUE_REF) != 0;

                let key_type_info: Option<Rc<TypeInfo>> = if !key_declared {
                    if key_is_polymorphic {
                        Some(context.read_any_type_info()?)
                    } else {
                        K::fory_read_type_info(context)?;
                        None
                    }
                } else {
                    None
                };
                let value_type_info: Option<Rc<TypeInfo>> = if !value_declared {
                    if val_is_polymorphic {
                        Some(context.read_any_type_info()?)
                    } else {
                        V::fory_read_type_info(context)?;
                        None
                    }
                } else {
                    None
                };

                let cur_len = len_counter + chunk_size as u32;
                ensure!(
                    cur_len <= length,
                    Error::invalid_data(
                        format!("current length {} exceeds total length {}", cur_len, length)
                    )
                );

                // Read chunk_size pairs of key-value
                let key_ref_mode = if track_key_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let val_ref_mode = if track_value_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                for _ in 0..chunk_size {
                    let key = if let Some(type_info) = key_type_info.as_ref() {
                        K::fory_read_with_type_info(context, key_ref_mode, type_info.clone())?
                    } else if track_key_ref {
                        K::fory_read(context, key_ref_mode, false)?
                    } else {
                        K::fory_read_data(context)?
                    };

                    let value = if let Some(type_info) = value_type_info.as_ref() {
                        V::fory_read_with_type_info(context, val_ref_mode, type_info.clone())?
                    } else if track_value_ref {
                        V::fory_read(context, val_ref_mode, false)?
                    } else {
                        V::fory_read_data(context)?
                    };

                    map.insert(key, value);
                }

                len_counter += chunk_size as u32;
            }

            Ok(map)
        }
    };
}

// Generate read_hashmap_data_dyn_ref for HashMap
impl_read_map_dyn_ref!(
    read_hashmap_data_dyn_ref,
    HashMap<K, V>,
    Eq + std::hash::Hash
);

// Generate read_btreemap_data_dyn_ref for BTreeMap
impl_read_map_dyn_ref!(
    read_btreemap_data_dyn_ref,
    BTreeMap<K, V>,
    Ord
);

impl<K: Serializer + ForyDefault + Eq + std::hash::Hash, V: Serializer + ForyDefault> Serializer
    for HashMap<K, V>
{
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_map_data(self.iter(), self.len(), context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_map_data(self.iter(), self.len(), context, has_generics)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let len = context.reader.read_varuint32()?;
        if len == 0 {
            return Ok(HashMap::new());
        }
        let remaining = context
            .reader
            .bf
            .len()
            .saturating_sub(context.reader.cursor);
        if len as usize > remaining {
            return Err(Error::invalid_data(format!(
                "map entry count {} exceeds buffer remaining {}",
                len, remaining
            )));
        }
        context.check_map_size(len as usize)?;
        let mut map = HashMap::<K, V>::with_capacity(len as usize);
        if K::fory_is_polymorphic()
            || K::fory_is_shared_ref()
            || V::fory_is_polymorphic()
            || V::fory_is_shared_ref()
        {
            let map: HashMap<K, V> = HashMap::with_capacity(len as usize);
            return read_hashmap_data_dyn_ref(context, map, len);
        }
        let mut len_counter = 0;
        loop {
            if len_counter == len {
                break;
            }
            let header = context.reader.read_u8()?;
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert(K::fory_default(), V::fory_default());
                len_counter += 1;
                continue;
            }
            let key_declared = (header & DECL_KEY_TYPE) != 0;
            let value_declared = (header & DECL_VALUE_TYPE) != 0;
            let track_key_ref = (header & TRACKING_KEY_REF) != 0;
            let track_value_ref = (header & TRACKING_VALUE_REF) != 0;
            if header & KEY_NULL != 0 {
                // Null case is handled by KEY_NULL flag, so use None (no ref flag) unless tracking
                let ref_mode = if track_value_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let value = V::fory_read(context, ref_mode, !value_declared)?;
                map.insert(K::fory_default(), value);
                len_counter += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                // Null case is handled by VALUE_NULL flag, so use None (no ref flag) unless tracking
                let ref_mode = if track_key_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let key = K::fory_read(context, ref_mode, !key_declared)?;
                map.insert(key, V::fory_default());
                len_counter += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8()?;
            if header & DECL_KEY_TYPE == 0 {
                K::fory_read_type_info(context)?;
            }
            if header & DECL_VALUE_TYPE == 0 {
                V::fory_read_type_info(context)?;
            }
            let cur_len = len_counter + chunk_size as u32;
            ensure!(
                cur_len <= len,
                Error::invalid_data(format!(
                    "current length {} exceeds total length {}",
                    cur_len, len
                ))
            );
            if !track_key_ref && !track_value_ref {
                for _ in 0..chunk_size {
                    let key = K::fory_read_data(context)?;
                    let value = V::fory_read_data(context)?;
                    map.insert(key, value);
                }
            } else {
                let key_ref_mode = if track_key_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let val_ref_mode = if track_value_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                for _ in 0..chunk_size {
                    let key = K::fory_read(context, key_ref_mode, false)?;
                    let value = V::fory_read(context, val_ref_mode, false)?;
                    map.insert(key, value);
                }
            }
            // advance the counter after processing the chunk
            len_counter += chunk_size as u32;
        }
        Ok(map)
    }

    fn fory_reserved_space() -> usize {
        size_of::<i32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::MAP)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::MAP)
    }

    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        TypeId::MAP
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::MAP as u8);
        Ok(())
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl<K, V> ForyDefault for HashMap<K, V> {
    fn fory_default() -> Self {
        HashMap::new()
    }
}

impl<K: Serializer + ForyDefault + Ord + std::hash::Hash, V: Serializer + ForyDefault> Serializer
    for BTreeMap<K, V>
{
    fn fory_write_data(&self, context: &mut WriteContext) -> Result<(), Error> {
        write_map_data(self.iter(), self.len(), context, false)
    }

    fn fory_write_data_generic(
        &self,
        context: &mut WriteContext,
        has_generics: bool,
    ) -> Result<(), Error> {
        write_map_data(self.iter(), self.len(), context, has_generics)
    }

    fn fory_read_data(context: &mut ReadContext) -> Result<Self, Error> {
        let len = context.reader.read_varuint32()?;
        if len == 0 {
            return Ok(BTreeMap::new());
        }
        context.check_map_size(len as usize)?;
        let mut map = BTreeMap::<K, V>::new();
        if K::fory_is_polymorphic()
            || K::fory_is_shared_ref()
            || V::fory_is_polymorphic()
            || V::fory_is_shared_ref()
        {
            let map: BTreeMap<K, V> = BTreeMap::new();
            return read_btreemap_data_dyn_ref(context, map, len);
        }
        let mut len_counter = 0;
        loop {
            if len_counter == len {
                break;
            }
            let header = context.reader.read_u8()?;
            if header & KEY_NULL != 0 && header & VALUE_NULL != 0 {
                map.insert(K::fory_default(), V::fory_default());
                len_counter += 1;
                continue;
            }
            let key_declared = (header & DECL_KEY_TYPE) != 0;
            let value_declared = (header & DECL_VALUE_TYPE) != 0;
            let track_key_ref = (header & TRACKING_KEY_REF) != 0;
            let track_value_ref = (header & TRACKING_VALUE_REF) != 0;
            if header & KEY_NULL != 0 {
                // Null case is handled by KEY_NULL flag, so use None (no ref flag) unless tracking
                let ref_mode = if track_value_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let value = V::fory_read(context, ref_mode, !value_declared)?;
                map.insert(K::fory_default(), value);
                len_counter += 1;
                continue;
            }
            if header & VALUE_NULL != 0 {
                // Null case is handled by VALUE_NULL flag, so use None (no ref flag) unless tracking
                let ref_mode = if track_key_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let key = K::fory_read(context, ref_mode, !key_declared)?;
                map.insert(key, V::fory_default());
                len_counter += 1;
                continue;
            }
            let chunk_size = context.reader.read_u8()?;
            if header & DECL_KEY_TYPE == 0 {
                K::fory_read_type_info(context)?;
            }
            if header & DECL_VALUE_TYPE == 0 {
                V::fory_read_type_info(context)?;
            }
            let cur_len = len_counter + chunk_size as u32;
            ensure!(
                cur_len <= len,
                Error::invalid_data(format!(
                    "current length {} exceeds total length {}",
                    cur_len, len
                ))
            );
            if !track_key_ref && !track_value_ref {
                for _ in 0..chunk_size {
                    let key = K::fory_read_data(context)?;
                    let value = V::fory_read_data(context)?;
                    map.insert(key, value);
                }
            } else {
                let key_ref_mode = if track_key_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                let val_ref_mode = if track_value_ref {
                    RefMode::Tracking
                } else {
                    RefMode::None
                };
                for _ in 0..chunk_size {
                    let key = K::fory_read(context, key_ref_mode, false)?;
                    let value = V::fory_read(context, val_ref_mode, false)?;
                    map.insert(key, value);
                }
            }
            len_counter += chunk_size as u32;
        }
        Ok(map)
    }

    fn fory_reserved_space() -> usize {
        size_of::<i32>()
    }

    fn fory_get_type_id(_: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::MAP)
    }

    fn fory_type_id_dyn(&self, _: &TypeResolver) -> Result<TypeId, Error> {
        Ok(TypeId::MAP)
    }

    fn fory_static_type_id() -> TypeId
    where
        Self: Sized,
    {
        TypeId::MAP
    }

    fn as_any(&self) -> &dyn std::any::Any {
        self
    }

    fn fory_write_type_info(context: &mut WriteContext) -> Result<(), Error> {
        context.writer.write_u8(TypeId::MAP as u8);
        Ok(())
    }

    fn fory_read_type_info(context: &mut ReadContext) -> Result<(), Error> {
        read_basic_type_info::<Self>(context)
    }
}

impl<K, V> ForyDefault for BTreeMap<K, V> {
    fn fory_default() -> Self {
        BTreeMap::new()
    }
}
