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

//! Stream buffer infrastructure for future streaming deserialization.
//!
//! This module provides `ForyStreamBuf`, a standalone streaming buffer that will
//! be integrated with Reader in subsequent PRs. Currently has zero impact on
//! existing code.
//!
//! Design follows C++ PR #3307 and issue #3300.

use crate::error::Error;
use std::io::{self, Read};

const DEFAULT_CAPACITY: usize = 4096;

/// Streaming buffer that wraps a `Read` source with a growable internal buffer.
///
/// Buffer grows monotonically (no compaction) - data is always appended at the end.
/// This matches the C++ `ForyInputStreamBuf` design where `write_pos = cur_size`.
///
/// Currently standalone - will be integrated with Reader in Phase 2.
pub struct ForyStreamBuf {
    source: Box<dyn Read>,
    buffer: Vec<u8>,
    valid_len: usize,
    read_pos: usize,
}

impl ForyStreamBuf {
    /// Creates a new stream buffer with default capacity (4096 bytes).
    pub fn new(source: Box<dyn Read>) -> Self {
        Self::with_capacity(source, DEFAULT_CAPACITY)
    }

    /// Creates a new stream buffer with specified initial capacity.
    pub fn with_capacity(source: Box<dyn Read>, capacity: usize) -> Self {
        Self {
            source,
            buffer: Vec::with_capacity(capacity.max(1)),
            valid_len: 0,
            read_pos: 0,
        }
    }

    /// Ensures at least `min_bytes` are available to read beyond current position.
    ///
    /// Reads from source in a loop until enough data is available or EOF is reached.
    /// Buffer grows automatically as needed (never compacts).
    ///
    /// # Errors
    ///
    /// Returns `Error::buffer_out_of_bound` if EOF is reached before enough bytes
    /// are available.
    pub fn fill_buffer(&mut self, min_bytes: usize) -> Result<(), Error> {
        if min_bytes == 0 || self.remaining() >= min_bytes {
            return Ok(());
        }

        let required = self.valid_len + (min_bytes - self.remaining());

        if required > self.buffer.len() {
            let new_cap = (self.buffer.len() * 2).max(required);
            self.buffer.resize(new_cap, 0);
        }

        while self.remaining() < min_bytes {
            let writable = self.buffer.len() - self.valid_len;
            if writable == 0 {
                let new_cap = self.buffer.len() * 2 + 1;
                self.buffer.resize(new_cap, 0);
                continue;
            }

            match self.source.read(&mut self.buffer[self.valid_len..]) {
                Ok(0) => {
                    return Err(Error::buffer_out_of_bound(
                        self.read_pos,
                        min_bytes,
                        self.valid_len,
                    ));
                }
                Ok(n) => {
                    self.valid_len += n;
                }
                Err(e) if e.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => {
                    return Err(Error::buffer_out_of_bound(
                        self.read_pos,
                        min_bytes,
                        self.valid_len,
                    ));
                }
            }
        }

        Ok(())
    }

    /// Returns pointer to the start of the buffer.
    #[inline(always)]
    pub fn data_ptr(&self) -> *const u8 {
        self.buffer.as_ptr()
    }

    /// Returns total valid bytes in buffer (from start).
    #[inline(always)]
    pub fn size(&self) -> usize {
        self.valid_len
    }

    /// Returns current read position (absolute, from buffer start).
    #[inline(always)]
    pub fn reader_index(&self) -> usize {
        self.read_pos
    }

    /// Sets the read position.
    ///
    /// # Panics
    ///
    /// Panics if index exceeds valid data length.
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
    #[inline(always)]
    pub fn remaining(&self) -> usize {
        self.valid_len.saturating_sub(self.read_pos)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn test_basic_fill() {
        let data = vec![1, 2, 3, 4, 5];
        let mut buf = ForyStreamBuf::new(Box::new(Cursor::new(data)));

        buf.fill_buffer(3).unwrap();
        assert!(buf.size() >= 3);
        assert_eq!(buf.reader_index(), 0);
    }

    #[test]
    fn test_insufficient_data_error() {
        let data = vec![1, 2];
        let mut buf = ForyStreamBuf::new(Box::new(Cursor::new(data)));

        let result = buf.fill_buffer(10);
        assert!(result.is_err());
    }

    #[test]
    fn test_reader_index_management() {
        let data = vec![1, 2, 3, 4, 5];
        let mut buf = ForyStreamBuf::new(Box::new(Cursor::new(data)));

        buf.fill_buffer(5).unwrap();
        assert_eq!(buf.reader_index(), 0);
        assert_eq!(buf.remaining(), 5);

        buf.set_reader_index(3);
        assert_eq!(buf.reader_index(), 3);
        assert_eq!(buf.remaining(), 2);
    }

    #[test]
    fn test_zero_fill_request() {
        let data = vec![1, 2, 3];
        let mut buf = ForyStreamBuf::new(Box::new(Cursor::new(data)));

        buf.fill_buffer(0).unwrap();
        assert_eq!(buf.size(), 0); // No fill should have occurred
    }
}
