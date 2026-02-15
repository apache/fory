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

//! Streaming buffer for incremental deserialization.
//!
//! Provides [`ForyStreamBuf`], which wraps any [`Read`](std::io::Read) source
//! and maintains a growable internal buffer that data is appended into on
//! demand. This is the Rust equivalent of C++ `ForyInputStreamBuf` from
//! `cpp/fory/util/stream.h` (PR #3307).
//!
//! # Design
//!
//! The buffer grows monotonically (no compaction). Data is always appended at
//! the end, matching the C++ `ForyInputStreamBuf::fill_buffer` behavior where
//! `write_pos = cur_size` and new bytes are written after existing valid data.
//!
//! `reader_index` is an absolute position within this growing buffer, so the
//! [`Reader`](crate::buffer::Reader) cursor never needs adjustment after a fill.

use crate::error::Error;
use std::io::{self, Read};

/// Default initial buffer capacity matching C++ `ForyInputStreamBuf` default.
const DEFAULT_CAPACITY: usize = 4096;

/// Streaming buffer that reads from a source on demand.
///
/// Wraps any `Read` source and maintains a growable buffer. Data is appended
/// at the end on each `fill_buffer` call, never compacted. This matches the
/// C++ `ForyInputStreamBuf` which uses a `std::vector<char>` that only grows.
///
/// # Equivalent
/// C++ `ForyInputStreamBuf` in `cpp/fory/util/stream.h`
pub struct ForyStreamBuf {
    source: Box<dyn Read>,
    /// Buffer holding all fetched data. Only grows, never compacted.
    buffer: Vec<u8>,
    /// Number of valid (filled) bytes in buffer. Equivalent to C++ `size()`
    /// which returns `egptr() - eback()`.
    valid_len: usize,
    /// Current read position. Equivalent to C++ `reader_index()` which
    /// returns `gptr() - eback()`.
    read_pos: usize,
}

impl ForyStreamBuf {
    /// Creates a new stream buffer with default capacity (4096 bytes).
    pub fn new(source: Box<dyn Read>) -> Self {
        Self::with_capacity(source, DEFAULT_CAPACITY)
    }

    /// Creates a new stream buffer with specified initial capacity.
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf(std::istream&, uint32_t buffer_size)`
    pub fn with_capacity(source: Box<dyn Read>, capacity: usize) -> Self {
        Self {
            source,
            buffer: Vec::with_capacity(capacity.max(1)),
            valid_len: 0,
            read_pos: 0,
        }
    }

    /// Ensures at least `min_bytes` are available to read beyond current
    /// `read_pos`. Reads from source in a loop until enough data is available
    /// or EOF/error is reached.
    ///
    /// Does NOT compact — new data is appended at `valid_len`, matching C++
    /// `ForyInputStreamBuf::fill_buffer` where `write_pos = cur_size`.
    ///
    /// # Errors
    /// Returns `Error::buffer_out_of_bound` if EOF is reached before enough
    /// bytes are available.
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf::fill_buffer(uint32_t min_fill_size)`
    pub fn fill_buffer(&mut self, min_bytes: usize) -> Result<(), Error> {
        if min_bytes == 0 {
            return Ok(());
        }
        if self.remaining() >= min_bytes {
            return Ok(());
        }

        // Calculate required total buffer size
        // C++: required = cur_size + (min_fill_size - remaining_size())
        let required = self.valid_len + (min_bytes - self.remaining());

        // Grow buffer capacity if needed (double or required, whichever is larger)
        if required > self.buffer.len() {
            let new_cap = (self.buffer.len() * 2).max(required);
            self.buffer.resize(new_cap, 0);
        }

        // Read from source until we have enough data
        // C++: while (remaining_size() < min_fill_size) { ... sgetn(...) ... }
        while self.remaining() < min_bytes {
            let writable = self.buffer.len() - self.valid_len;
            if writable == 0 {
                // Need to grow more
                let new_cap = self.buffer.len() * 2 + 1;
                self.buffer.resize(new_cap, 0);
                continue;
            }

            match self.source.read(&mut self.buffer[self.valid_len..]) {
                Ok(0) => {
                    // EOF before getting enough bytes
                    return Err(Error::buffer_out_of_bound(
                        self.read_pos,
                        min_bytes,
                        self.remaining(),
                    ));
                }
                Ok(n) => {
                    self.valid_len += n;
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => {
                    continue;
                }
                Err(_) => {
                    return Err(Error::buffer_out_of_bound(
                        self.read_pos,
                        min_bytes,
                        self.remaining(),
                    ));
                }
            }
        }

        Ok(())
    }

    /// Returns pointer to the start of the buffer.
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf::data()` which returns `eback()` (start of buffer)
    #[inline(always)]
    pub fn data_ptr(&self) -> *const u8 {
        self.buffer.as_ptr()
    }

    /// Returns total valid bytes in buffer (from start).
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf::size()` which returns `egptr() - eback()`
    #[inline(always)]
    pub fn size(&self) -> usize {
        self.valid_len
    }

    /// Returns current read position (absolute, from buffer start).
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf::reader_index()` which returns `gptr() - eback()`
    #[inline(always)]
    pub fn reader_index(&self) -> usize {
        self.read_pos
    }

    /// Sets the read position.
    ///
    /// # Panics
    /// Panics if index exceeds valid data length.
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf::reader_index(uint32_t index)`
    #[inline(always)]
    pub fn set_reader_index(&mut self, index: usize) {
        assert!(
            index <= self.valid_len,
            "reader index {} exceeds valid data length {}",
            index,
            self.valid_len
        );
        self.read_pos = index;
    }

    /// Returns number of unread bytes remaining.
    ///
    /// # Equivalent
    /// C++ `ForyInputStreamBuf::remaining_size()` which returns `egptr() - gptr()`
    #[inline(always)]
    pub fn remaining(&self) -> usize {
        self.valid_len.saturating_sub(self.read_pos)
    }
}
