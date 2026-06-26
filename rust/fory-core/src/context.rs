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

use crate::buffer::{Reader, Writer};
use crate::config::Config;
use std::collections::HashMap;
use std::mem;

use crate::error::Error;
use crate::meta::MetaString;
use crate::resolver::meta_resolver::{MetaReaderResolver, MetaWriterResolver};
use crate::resolver::meta_string_resolver::{MetaStringReaderResolver, MetaStringWriterResolver};
use crate::resolver::{RefReader, RefWriter};
use crate::resolver::{TypeInfo, TypeResolver};
use crate::serializer::StructSerializer;
use crate::type_id as types;
use crate::TypeId;
use std::rc::Rc;

const KNOWN_ROOT_BUDGET_MULTIPLIER: usize = 8;
const KNOWN_ROOT_BUDGET_SLACK_BYTES: usize = 64 * 1024;
const VEC_OBJECT_BYTES: usize = mem::size_of::<Vec<u8>>();
const MAP_ENTRY_OVERHEAD_BYTES: usize = 16;
const REFERENCE_SLOT_BYTES: usize = mem::size_of::<usize>();
const MAX_CONTAINER_LEN: usize = u32::MAX as usize;

/// Thread-local context cache with fast path for single Fory instance.
/// Uses (cached_id, context) for O(1) access when using same Fory instance repeatedly.
/// Falls back to HashMap for multiple Fory instances per thread.
pub struct ContextCache<T> {
    /// Fast path: cached context for the most recently used Fory instance
    cached_id: u64,
    cached_context: Option<Box<T>>,
    /// Slow path: HashMap for other Fory instances
    others: HashMap<u64, Box<T>>,
}

impl<T> ContextCache<T> {
    pub fn new() -> Self {
        ContextCache {
            cached_id: u64::MAX,
            cached_context: None,
            others: HashMap::new(),
        }
    }

    #[inline(always)]
    pub fn get_or_insert(&mut self, id: u64, create: impl FnOnce() -> Box<T>) -> &mut T {
        if self.cached_id == id {
            // Fast path: same Fory instance as last time
            return self.cached_context.as_mut().unwrap();
        }

        // Check if we need to swap with cached
        if self.cached_context.is_some() {
            // Move current cached to others
            let old_id = self.cached_id;
            let old_context = self.cached_context.take().unwrap();
            self.others.insert(old_id, old_context);
        }

        // Get or create context for new id
        let context = self.others.remove(&id).unwrap_or_else(create);
        self.cached_id = id;
        self.cached_context = Some(context);
        self.cached_context.as_mut().unwrap()
    }

    /// Like `get_or_insert`, but the create closure returns a Result.
    /// This allows error handling during context creation without pre-fetching resources.
    #[inline(always)]
    pub fn get_or_insert_result<E>(
        &mut self,
        id: u64,
        create: impl FnOnce() -> Result<Box<T>, E>,
    ) -> Result<&mut T, E> {
        if self.cached_id == id {
            // Fast path: same Fory instance as last time
            return Ok(self.cached_context.as_mut().unwrap());
        }

        // Check if we need to swap with cached
        if self.cached_context.is_some() {
            // Move current cached to others
            let old_id = self.cached_id;
            let old_context = self.cached_context.take().unwrap();
            self.others.insert(old_id, old_context);
        }

        // Get or create context for new id
        let context = match self.others.remove(&id) {
            Some(ctx) => ctx,
            None => create()?,
        };
        self.cached_id = id;
        self.cached_context = Some(context);
        Ok(self.cached_context.as_mut().unwrap())
    }
}

impl<T> Default for ContextCache<T> {
    fn default() -> Self {
        Self::new()
    }
}

/// Serialization state container used on a single thread at a time.
/// Sharing the same instance across threads simultaneously causes undefined behavior.
#[allow(clippy::needless_lifetimes)]
pub struct WriteContext<'a> {
    // Replicated environment fields (direct access, no Arc indirection for flags)
    type_resolver: TypeResolver,
    compatible: bool,
    share_meta: bool,
    compress_string: bool,
    xlang: bool,
    check_struct_version: bool,
    track_ref: bool,

    // Context-specific fields
    default_writer: Option<Writer<'a>>,
    pub writer: Writer<'a>,
    meta_resolver: MetaWriterResolver,
    meta_string_resolver: MetaStringWriterResolver,
    pub ref_writer: RefWriter,
}

#[allow(clippy::needless_lifetimes)]
impl<'a> WriteContext<'a> {
    pub fn new(type_resolver: TypeResolver, config: Config) -> WriteContext<'a> {
        WriteContext {
            type_resolver,
            compatible: config.compatible,
            share_meta: config.share_meta,
            compress_string: config.compress_string,
            xlang: config.xlang,
            check_struct_version: config.check_struct_version,
            track_ref: config.track_ref,
            default_writer: None,
            writer: Writer::from_buffer(Self::get_leak_buffer()),
            meta_resolver: MetaWriterResolver::default(),
            meta_string_resolver: MetaStringWriterResolver::default(),
            ref_writer: RefWriter::new(),
        }
    }

    #[inline(always)]
    fn get_leak_buffer() -> &'static mut Vec<u8> {
        Box::leak(Box::new(vec![]))
    }

    #[inline(always)]
    pub fn attach_writer(&mut self, writer: Writer<'a>) {
        let old = mem::replace(&mut self.writer, writer);
        self.default_writer = Some(old);
    }

    #[inline(always)]
    pub fn detach_writer(&mut self) {
        let default = mem::take(&mut self.default_writer);
        self.writer = default.unwrap();
    }

    /// Get type resolver
    #[inline(always)]
    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    #[inline(always)]
    pub fn get_type_info(&self, type_id: &std::any::TypeId) -> Result<Rc<TypeInfo>, Error> {
        self.type_resolver.get_type_info(type_id)
    }

    /// Check if compatible mode is enabled
    #[inline(always)]
    pub fn is_compatible(&self) -> bool {
        self.compatible
    }

    /// Check if meta sharing is enabled
    #[inline(always)]
    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    /// Check if string compression is enabled
    #[inline(always)]
    pub fn is_compress_string(&self) -> bool {
        self.compress_string
    }

    /// Check if xlang mode is enabled
    #[inline(always)]
    pub fn is_xlang(&self) -> bool {
        self.xlang
    }

    /// Check if class version checking is enabled
    #[inline(always)]
    pub fn is_check_struct_version(&self) -> bool {
        self.check_struct_version
    }

    /// Check if reference tracking is enabled
    #[inline(always)]
    pub fn is_track_ref(&self) -> bool {
        self.track_ref
    }

    /// Write type meta inline using streaming protocol.
    /// Writes index marker with LSB indicating new type or reference.
    #[inline(always)]
    pub fn write_type_meta(&mut self, type_id: std::any::TypeId) -> Result<(), Error> {
        self.meta_resolver
            .write_type_meta(&mut self.writer, type_id, &self.type_resolver)
    }

    /// Write generated struct type info without Rust TypeId hash lookups.
    #[inline(always)]
    pub fn write_struct_type_info<T: StructSerializer>(&mut self) -> Result<(), Error> {
        let rust_type_id = std::any::TypeId::of::<T>();
        let type_index = T::fory_type_index();
        let type_id = self.type_resolver.get_type_id_by_index(type_index)?;
        match type_id {
            TypeId::STRUCT | TypeId::ENUM | TypeId::EXT | TypeId::TYPED_UNION => {
                self.writer.write_u8(type_id as u8);
                let user_type_id = self
                    .type_resolver
                    .get_user_type_id_by_index(&rust_type_id, type_index)?;
                self.writer.write_var_u32(user_type_id);
            }
            TypeId::COMPATIBLE_STRUCT | TypeId::NAMED_COMPATIBLE_STRUCT => {
                self.writer.write_u8(type_id as u8);
                self.meta_resolver.write_type_meta_fast(
                    &mut self.writer,
                    rust_type_id,
                    type_index,
                    &self.type_resolver,
                )?;
            }
            TypeId::NAMED_ENUM | TypeId::NAMED_EXT | TypeId::NAMED_STRUCT | TypeId::NAMED_UNION
                if self.is_share_meta() =>
            {
                self.writer.write_u8(type_id as u8);
                self.meta_resolver.write_type_meta_fast(
                    &mut self.writer,
                    rust_type_id,
                    type_index,
                    &self.type_resolver,
                )?;
            }
            _ => {
                self.write_any_type_info(type_id as u32, rust_type_id)?;
            }
        }
        Ok(())
    }

    pub fn write_any_type_info(
        &mut self,
        fory_type_id: u32,
        concrete_type_id: std::any::TypeId,
    ) -> Result<Rc<TypeInfo>, Error> {
        if types::is_internal_type(fory_type_id) {
            self.writer.write_u8(fory_type_id as u8);
            return self
                .type_resolver
                .get_type_info_by_id(fory_type_id)
                .ok_or_else(|| Error::type_error("Type info for internal type not found"));
        }
        let type_info = self.type_resolver.get_type_info(&concrete_type_id)?;
        let fory_type_id = type_info.get_type_id();
        let namespace = type_info.get_namespace();
        let type_name = type_info.get_type_name();
        self.writer.write_u8(fory_type_id as u8);
        // should be compiled to jump table generation
        match fory_type_id {
            TypeId::ENUM | TypeId::STRUCT | TypeId::EXT | TypeId::TYPED_UNION => {
                let user_type_id = type_info.get_user_type_id();
                self.writer.write_var_u32(user_type_id);
            }
            TypeId::COMPATIBLE_STRUCT | TypeId::NAMED_COMPATIBLE_STRUCT => {
                // Write type meta inline using streaming protocol
                self.meta_resolver.write_type_meta(
                    &mut self.writer,
                    concrete_type_id,
                    &self.type_resolver,
                )?;
            }
            TypeId::NAMED_ENUM | TypeId::NAMED_EXT | TypeId::NAMED_STRUCT | TypeId::NAMED_UNION => {
                if self.is_share_meta() {
                    // Write type meta inline using streaming protocol
                    self.meta_resolver.write_type_meta(
                        &mut self.writer,
                        concrete_type_id,
                        &self.type_resolver,
                    )?;
                } else {
                    self.write_meta_string_bytes(namespace)?;
                    self.write_meta_string_bytes(type_name)?;
                }
            }
            _ => {
                // default case: do nothing
            }
        }
        Ok(type_info)
    }

    #[inline(always)]
    pub fn write_meta_string_bytes(&mut self, ms: Rc<MetaString>) -> Result<(), Error> {
        self.meta_string_resolver
            .write_meta_string_bytes(&mut self.writer, ms)
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.meta_resolver.reset();
        self.meta_string_resolver.reset();
        self.ref_writer.reset();
    }
}

#[allow(clippy::needless_lifetimes)]
impl<'a> Drop for WriteContext<'a> {
    fn drop(&mut self) {
        unsafe {
            drop(Box::from_raw(self.writer.bf));
        }
    }
}

// Safety: WriteContext is only shared across threads via higher-level pooling code that
// ensures single-threaded access while the context is in use. Users must never hold the same
// instance on multiple threads simultaneously; that would violate the invariants and result in
// undefined behavior. Under that assumption, marking it Send/Sync is sound.
#[allow(clippy::needless_lifetimes)]
unsafe impl<'a> Send for WriteContext<'a> {}
#[allow(clippy::needless_lifetimes)]
unsafe impl<'a> Sync for WriteContext<'a> {}

/// Deserialization state container used on a single thread at a time.
/// Sharing the same instance across threads simultaneously causes undefined behavior.
pub struct ReadContext<'a> {
    // Replicated environment fields (direct access, no Arc indirection for flags)
    type_resolver: TypeResolver,
    config: Config,
    compatible: bool,
    share_meta: bool,
    xlang: bool,
    max_dyn_depth: u32,
    check_struct_version: bool,
    check_string_read: bool,
    max_container_memory_bytes: i64,
    container_memory_limit_bytes: usize,
    remaining_container_memory_bytes: usize,

    // Context-specific fields
    pub reader: Reader<'a>,
    pub meta_resolver: MetaReaderResolver,
    meta_string_resolver: MetaStringReaderResolver,
    pub ref_reader: RefReader,
    current_depth: u32,
}

// Safety: ReadContext follows the same invariants as WriteContext—external orchestrators ensure
// single-threaded use. Concurrent access to the same instance across threads is forbidden and
// would result in undefined behavior. With exclusive use guaranteed, the Send/Sync markers are safe
// even though Rc is used internally.
#[allow(clippy::needless_lifetimes)]
unsafe impl<'a> Send for ReadContext<'a> {}
#[allow(clippy::needless_lifetimes)]
unsafe impl<'a> Sync for ReadContext<'a> {}

impl<'a> ReadContext<'a> {
    pub fn new(type_resolver: TypeResolver, config: Config) -> ReadContext<'a> {
        ReadContext {
            type_resolver,
            config: config.clone(),
            compatible: config.compatible,
            share_meta: config.share_meta,
            xlang: config.xlang,
            max_dyn_depth: config.max_dyn_depth,
            check_struct_version: config.check_struct_version,
            check_string_read: config.check_string_read,
            max_container_memory_bytes: config.max_container_memory_bytes,
            container_memory_limit_bytes: 0,
            remaining_container_memory_bytes: 0,
            reader: Reader::default(),
            meta_resolver: MetaReaderResolver::default(),
            meta_string_resolver: MetaStringReaderResolver::default(),
            ref_reader: RefReader::new(),
            current_depth: 0,
        }
    }

    /// Get type resolver
    #[inline(always)]
    pub fn get_type_resolver(&self) -> &TypeResolver {
        &self.type_resolver
    }

    /// Check if compatible mode is enabled
    #[inline(always)]
    pub fn is_compatible(&self) -> bool {
        self.compatible
    }

    /// Check if meta sharing is enabled
    #[inline(always)]
    pub fn is_share_meta(&self) -> bool {
        self.share_meta
    }

    /// Check if xlang mode is enabled
    #[inline(always)]
    pub fn is_xlang(&self) -> bool {
        self.xlang
    }

    /// Check if class version checking is enabled
    #[inline(always)]
    pub fn is_check_struct_version(&self) -> bool {
        self.check_struct_version
    }

    /// Check if UTF-8 string payload validation is enabled.
    #[inline(always)]
    pub fn is_check_string_read(&self) -> bool {
        self.check_string_read
    }

    /// Get maximum dynamic depth
    #[inline(always)]
    pub fn max_dyn_depth(&self) -> u32 {
        self.max_dyn_depth
    }

    #[inline(always)]
    pub fn attach_reader(&mut self, reader: Reader<'a>) {
        self.reader = reader;
    }

    #[inline(always)]
    pub(crate) fn init_container_memory_budget(
        &mut self,
        root_input_bytes: usize,
    ) -> Result<(), Error> {
        let limit = if self.max_container_memory_bytes > 0 {
            usize::try_from(self.max_container_memory_bytes).map_err(|_| {
                container_memory_error("max_container_memory_bytes does not fit usize")
            })?
        } else {
            if root_input_bytes
                > (usize::MAX - KNOWN_ROOT_BUDGET_SLACK_BYTES) / KNOWN_ROOT_BUDGET_MULTIPLIER
            {
                return Err(container_memory_error(
                    "root input size overflows automatic container memory budget",
                ));
            }
            root_input_bytes * KNOWN_ROOT_BUDGET_MULTIPLIER + KNOWN_ROOT_BUDGET_SLACK_BYTES
        };
        self.container_memory_limit_bytes = limit;
        self.remaining_container_memory_bytes = limit;
        Ok(())
    }

    #[inline(always)]
    pub(crate) fn reserve_vec_memory<T>(&mut self, len: u32) -> Result<usize, Error> {
        let len = len as usize;
        self.reserve_counted_memory(len, VEC_OBJECT_BYTES, mem::size_of::<T>())?;
        Ok(len)
    }

    #[inline(always)]
    pub(crate) fn reserve_collection_memory<C, T>(&mut self, len: u32) -> Result<usize, Error> {
        let len = len as usize;
        let elem_size = mem::size_of::<T>();
        if elem_size > usize::MAX - REFERENCE_SLOT_BYTES {
            return Err(container_memory_overflow(len, elem_size));
        }
        let elem_bytes = elem_size + REFERENCE_SLOT_BYTES;
        self.reserve_counted_memory(len, mem::size_of::<C>(), elem_bytes)?;
        Ok(len)
    }

    #[inline(always)]
    pub(crate) fn reserve_map_memory<M, K, V>(&mut self, len: u32) -> Result<usize, Error> {
        let len = len as usize;
        let key_size = mem::size_of::<K>();
        let value_size = mem::size_of::<V>();
        let overhead = MAP_ENTRY_OVERHEAD_BYTES + REFERENCE_SLOT_BYTES * 3;
        if key_size > usize::MAX - value_size || key_size + value_size > usize::MAX - overhead {
            return Err(container_memory_overflow(len, key_size));
        }
        let elem_bytes = key_size + value_size + overhead;
        self.reserve_counted_memory(len, mem::size_of::<M>(), elem_bytes)?;
        Ok(len)
    }

    #[inline(always)]
    pub(crate) fn reserve_container_bytes(&mut self, bytes: usize) -> Result<(), Error> {
        let remaining = self.remaining_container_memory_bytes;
        if bytes > remaining {
            return Err(container_memory_exceeded(
                bytes,
                remaining,
                self.container_memory_limit_bytes,
            ));
        }
        self.remaining_container_memory_bytes = remaining - bytes;
        Ok(())
    }

    #[inline(always)]
    fn reserve_counted_memory(
        &mut self,
        len: usize,
        fixed_bytes: usize,
        elem_bytes: usize,
    ) -> Result<(), Error> {
        if len == 0 {
            return self.reserve_container_bytes(fixed_bytes);
        }
        if elem_bytes <= (usize::MAX - fixed_bytes) / MAX_CONTAINER_LEN {
            return self.reserve_container_bytes(len * elem_bytes + fixed_bytes);
        }
        self.reserve_counted_memory_checked(len, fixed_bytes, elem_bytes)
    }

    #[cold]
    #[inline(never)]
    fn reserve_counted_memory_checked(
        &mut self,
        len: usize,
        fixed_bytes: usize,
        elem_bytes: usize,
    ) -> Result<(), Error> {
        let elem_total = match len.checked_mul(elem_bytes) {
            Some(bytes) => bytes,
            None => return Err(container_memory_overflow(len, elem_bytes)),
        };
        let bytes = match elem_total.checked_add(fixed_bytes) {
            Some(bytes) => bytes,
            None => return Err(container_memory_overflow(len, elem_bytes)),
        };
        self.reserve_container_bytes(bytes)
    }

    #[inline(always)]
    pub fn detach_reader(&mut self) -> Reader<'_> {
        mem::take(&mut self.reader)
    }

    #[inline(always)]
    pub fn get_type_info_by_index(&self, type_index: usize) -> Result<&Rc<TypeInfo>, Error> {
        self.meta_resolver.get(type_index).ok_or_else(|| {
            Error::type_error(format!("TypeInfo not found for type index: {}", type_index))
        })
    }

    #[inline(always)]
    pub fn get_meta(&self, type_index: usize) -> Result<&Rc<TypeInfo>, Error> {
        self.get_type_info_by_index(type_index)
    }

    /// Read type meta inline using streaming protocol.
    /// Returns the TypeInfo for this type.
    #[inline(always)]
    pub fn read_type_meta(&mut self) -> Result<Rc<TypeInfo>, Error> {
        self.meta_resolver
            .read_type_meta(&mut self.reader, &self.type_resolver, &self.config)
    }

    pub fn read_any_type_info(&mut self) -> Result<Rc<TypeInfo>, Error> {
        let fory_type_id = self.reader.read_u8()? as u32;
        // should be compiled to jump table generation
        match fory_type_id {
            types::ENUM | types::STRUCT | types::EXT | types::TYPED_UNION => {
                let user_type_id = self.reader.read_var_u32()?;
                self.type_resolver
                    .get_user_type_info_by_id(user_type_id)
                    .ok_or_else(|| Error::type_error("ID harness not found"))
            }
            types::COMPATIBLE_STRUCT | types::NAMED_COMPATIBLE_STRUCT => {
                // Read type meta inline using streaming protocol
                self.read_type_meta()
            }
            types::NAMED_ENUM | types::NAMED_EXT | types::NAMED_STRUCT | types::NAMED_UNION => {
                if self.is_share_meta() {
                    // Read type meta inline using streaming protocol
                    self.read_type_meta()
                } else {
                    let namespace = self.read_meta_string()?.to_owned();
                    let type_name = self.read_meta_string()?.to_owned();
                    let rc_namespace = Rc::from(namespace.clone());
                    let rc_type_name = Rc::from(type_name.clone());
                    self.type_resolver
                        .get_type_info_by_meta_string_name(rc_namespace, rc_type_name)
                        .or_else(|| {
                            self.type_resolver.get_type_info_by_name(
                                namespace.original.as_str(),
                                type_name.original.as_str(),
                            )
                        })
                        .ok_or_else(|| {
                            Error::type_error(format!(
                                "Name harness not found: namespace='{}', type='{}'",
                                namespace.original, type_name.original
                            ))
                        })
                }
            }
            _ => self
                .type_resolver
                .get_type_info_by_id(fory_type_id)
                .ok_or_else(|| Error::type_error("ID harness not found")),
        }
    }

    #[inline(always)]
    pub fn get_type_info(&self, type_id: &std::any::TypeId) -> Result<Rc<TypeInfo>, Error> {
        self.type_resolver.get_type_info(type_id)
    }

    #[inline(always)]
    pub fn read_meta_string(&mut self) -> Result<&MetaString, Error> {
        self.meta_string_resolver.read_meta_string(&mut self.reader)
    }

    #[inline(always)]
    pub fn inc_depth(&mut self) -> Result<(), Error> {
        self.current_depth += 1;
        if self.current_depth > self.max_dyn_depth() {
            return Err(Error::depth_exceed(format!(
                "Maximum dynamic object nesting depth ({}) exceeded. Current depth: {}. \
                    This may indicate a circular reference or overly deep object graph. \
                    Consider increasing max_dyn_depth if this is expected.",
                self.max_dyn_depth(),
                self.current_depth
            )));
        }
        Ok(())
    }

    #[inline(always)]
    pub fn dec_depth(&mut self) {
        self.current_depth = self.current_depth.saturating_sub(1);
    }

    #[inline(always)]
    pub fn reset(&mut self) {
        self.meta_resolver.reset();
        self.meta_string_resolver.reset();
        self.ref_reader.reset();
        self.current_depth = 0;
    }
}

#[cold]
#[inline(never)]
fn container_memory_error(message: &'static str) -> Error {
    Error::invalid_data(message)
}

#[cold]
#[inline(never)]
fn container_memory_overflow(len: usize, elem_bytes: usize) -> Error {
    Error::invalid_data(format!(
        "container memory estimate overflows: length={} elementBytes={}",
        len, elem_bytes
    ))
}

#[cold]
#[inline(never)]
fn container_memory_exceeded(bytes: usize, remaining: usize, limit: usize) -> Error {
    Error::invalid_data(format!(
        "estimated container memory request {} bytes exceeds max_container_memory_bytes remaining budget {} bytes out of effective limit {} bytes",
        bytes, remaining, limit
    ))
}
