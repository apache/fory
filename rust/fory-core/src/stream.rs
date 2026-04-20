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
use std::io::{self, Read};

const DEFAULT_BUFFER_SIZE: usize = 4096;

/// Growable internal buffer backed by any [`Read`] source.
///
/// Bytes are pulled from the source on demand via [`fill_buffer`].
/// The buffer grows automatically and can be compacted via [`shrink_buffer`].
///
/// # Buffer size limit
/// The internal buffer is capped at `u32::MAX` bytes (~4 GiB).
/// Requesting more than this returns [`Error::buffer_out_of_bound`].
pub struct ForyStreamBuf {
    source: Box<dyn Read + Send>,
    buffer: Vec<u8>,
    /// Bytes available from source: `buffer[0..valid_len]`
    valid_len: usize,
    /// Current read position: `buffer[read_pos..valid_len]` is unread
    read_pos: usize,
    initial_buffer_size: usize,
}

impl ForyStreamBuf {
    pub fn new<R: Read + Send + 'static>(source: R) -> Self {
        Self::with_capacity(source, DEFAULT_BUFFER_SIZE)
    }

    pub fn with_capacity<R: Read + Send + 'static>(source: R, buffer_size: usize) -> Self {
        let cap = buffer_size.max(1);
        Self {
            source: Box::new(source),
            buffer: vec![0u8; cap],
            valid_len: 0,
            read_pos: 0,
            initial_buffer_size: cap,
        }
    }

    /// Pulls bytes from the source until at least `min_fill_size` unread bytes
    /// are available. Returns `Err` on EOF, I/O error, or 4 GiB overflow.
    pub fn fill_buffer(&mut self, min_fill_size: usize) -> Result<(), Error> {
        if min_fill_size == 0 || self.remaining() >= min_fill_size {
            return Ok(());
        }

        let need = min_fill_size - self.remaining();

        let required = self
            .valid_len
            .checked_add(need)
            .filter(|&r| r <= u32::MAX as usize)
            .ok_or_else(|| {
                Error::buffer_out_of_bound(self.read_pos, min_fill_size, self.remaining())
            })?;

        if required > self.buffer.len() {
            let new_cap = (self.buffer.len() * 2).max(required);
            self.buffer.resize(new_cap, 0);
        }

        while self.remaining() < min_fill_size {
            match self.source.read(&mut self.buffer[self.valid_len..]) {
                Ok(0) => {
                    return Err(Error::buffer_out_of_bound(
                        self.read_pos,
                        min_fill_size,
                        self.remaining(),
                    ));
                }
                Ok(n) => self.valid_len += n,
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => {
                    return Err(Error::buffer_out_of_bound(
                        self.read_pos,
                        min_fill_size,
                        self.remaining(),
                    ));
                }
            }
        }
        Ok(())
    }

    /// Moves the read cursor backward by `size` bytes.
    /// Returns `Err` if `size > read_pos`.
    pub fn rewind(&mut self, size: usize) -> Result<(), Error> {
        if size > self.read_pos {
            return Err(Error::buffer_out_of_bound(
                self.read_pos,
                size,
                self.valid_len,
            ));
        }
        self.read_pos -= size;
        Ok(())
    }

    /// Advances the read cursor by `size` bytes without reading from source.
    /// Returns `Err` if `size > remaining()`.
    pub fn consume(&mut self, size: usize) -> Result<(), Error> {
        if size > self.remaining() {
            return Err(Error::buffer_out_of_bound(
                self.read_pos,
                size,
                self.remaining(),
            ));
        }
        self.read_pos += size;
        Ok(())
    }

    /// Raw pointer to the start of the internal buffer window.
    ///
    /// # Safety
    /// Valid until the next [`fill_buffer`] call that causes reallocation.
    /// Always re-derive this pointer after any `fill_buffer` call.
    #[inline(always)]
    pub(crate) fn data(&self) -> *const u8 {
        self.buffer.as_ptr()
    }

    /// Total bytes fetched from source.
    #[inline(always)]
    pub fn size(&self) -> usize {
        self.valid_len
    }

    /// Current read cursor position.
    #[inline(always)]
    pub fn reader_index(&self) -> usize {
        self.read_pos
    }

    /// Sets the read cursor to `index`. Returns `Err` if `index > valid_len`.
    #[inline(always)]
    pub(crate) fn set_reader_index(&mut self, index: usize) -> Result<(), Error> {
        if index > self.valid_len {
            return Err(Error::buffer_out_of_bound(index, 0, self.valid_len));
        }
        self.read_pos = index;
        Ok(())
    }

    /// Unread bytes currently available.
    #[inline(always)]
    pub fn remaining(&self) -> usize {
        self.valid_len.saturating_sub(self.read_pos)
    }

    /// Compacts consumed bytes and optionally reduces buffer capacity.
    ///
    /// **Phase 1:** Always moves unread bytes to offset 0.
    ///
    /// **Phase 2:** Shrinks capacity only when it has grown beyond
    /// `initial_buffer_size` and current utilization is low (≤ 25%).
    pub fn shrink_buffer(&mut self) {
        let remaining = self.remaining();

        if self.read_pos > 0 {
            if remaining > 0 {
                self.buffer.copy_within(self.read_pos..self.valid_len, 0);
            }
            self.read_pos = 0;
            self.valid_len = remaining;
        }

        let current_capacity = self.buffer.len();
        if current_capacity <= self.initial_buffer_size {
            return;
        }

        let target_capacity = if remaining == 0 {
            self.initial_buffer_size
        } else if remaining <= current_capacity / 4 {
            let doubled = remaining.saturating_mul(2).max(1);
            self.initial_buffer_size.max(doubled)
        } else {
            current_capacity
        };

        if target_capacity < current_capacity {
            // Reduce logical size but keep allocation to avoid allocator churn
            self.buffer.truncate(target_capacity);
            self.buffer.shrink_to_fit();
        }
    }
}
