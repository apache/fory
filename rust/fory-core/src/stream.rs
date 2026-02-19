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

use crate::error::Error;
use std::io::{self, Read};

const DEFAULT_BUFFER_SIZE: usize = 4096;

/// Single internal `Vec<u8>` window. `valid_len` = `egptr()-eback()`.
/// `read_pos` = `gptr()-eback()`. [`fill_buffer`] grows on demand.
///
/// [`fill_buffer`]: ForyStreamBuf::fill_buffer
pub struct ForyStreamBuf {
    source: Box<dyn Read + Send>,
    /// Backing window — equivalent of C++ `buffer_` (`std::vector<char>`)
    buffer: Vec<u8>,
    /// Bytes fetched from source — equivalent of `egptr() - eback()`
    valid_len: usize,
    /// Current read cursor — equivalent of `gptr() - eback()`
    read_pos: usize,
}

impl ForyStreamBuf {
    pub fn new(source: impl Read + Send + 'static) -> Self {
        Self::with_capacity(source, DEFAULT_BUFFER_SIZE)
    }

    /// Allocates and zero-initialises the backing window immediately,
    /// `std::vector<char>(buffer_size)` in the constructor.
    pub fn with_capacity(source: impl Read + Send + 'static, buffer_size: usize) -> Self {
        let cap = buffer_size.max(1);
        let buffer = vec![0u8; cap];
        Self {
            source: Box::new(source),
            buffer,
            valid_len: 0,
            read_pos: 0,
        }
    }

    /// Pull bytes from source until `remaining() >= min_fill_size`.
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

        // Grow if required > current buffer length
        if required > self.buffer.len() {
            let new_cap = (self.buffer.len() * 2).max(required);
            self.buffer.resize(new_cap, 0);
        }

        while self.remaining() < min_fill_size {
            let writable = self.buffer.len() - self.valid_len;
            if writable == 0 {
                // Inner double `buffer_.size() * 2 + 1` with u32 overflow guard
                let new_cap = self
                    .buffer
                    .len()
                    .checked_mul(2)
                    .and_then(|n| n.checked_add(1))
                    .filter(|&n| n <= u32::MAX as usize)
                    .ok_or_else(|| {
                        Error::buffer_out_of_bound(self.read_pos, min_fill_size, self.remaining())
                    })?;
                self.buffer.resize(new_cap, 0);
                // fall through — self.buffer[self.valid_len..] is now non-empty
            }
            match self.source.read(&mut self.buffer[self.valid_len..]) {
                Ok(0) => {
                    // `read_bytes <= 0` → buffer_out_of_bound
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

    /// Move cursor backward by `size` bytes.
    ///
    /// `setg(eback(), gptr() - size, egptr())`
    ///
    /// Panics if `size > read_pos`.
    pub fn rewind(&mut self, size: usize) {
        assert!(
            size <= self.read_pos,
            "rewind size {} exceeds consumed bytes {}",
            size,
            self.read_pos
        );
        self.read_pos -= size;
    }

    /// Advance cursor forward by `size` bytes without pulling from source.
    ///
    /// `gbump(static_cast<int>(size))`
    ///
    /// Panics if `size > remaining()`.
    pub fn consume(&mut self, size: usize) {
        assert!(
            size <= self.remaining(),
            "consume size {} exceeds available bytes {}",
            size,
            self.remaining()
        );
        self.read_pos += size;
    }

    /// Raw pointer to byte 0 of the internal window.
    ///
    /// Re-read by `Reader` (buffer.rs) after every `fill_buffer` call that
    /// may reallocate
    /// `data_ = stream_->data()`.
    ///
    /// `uint8_t* data()` → `reinterpret_cast<uint8_t*>(eback())`.
    ///
    /// # Safety
    /// Valid until the next `fill_buffer` call that causes reallocation.
    /// `Reader` always re-reads this pointer after every `fill_buffer`.
    #[inline(always)]
    pub(crate) fn data(&self) -> *const u8 {
        self.buffer.as_ptr()
    }

    /// Total fetched bytes
    #[inline(always)]
    pub fn size(&self) -> usize {
        self.valid_len
    }

    /// Current read cursor
    #[inline(always)]
    pub fn reader_index(&self) -> usize {
        self.read_pos
    }

    /// Set cursor to absolute `index`.
    ///
    /// Called by `Reader` (buffer.rs) after every cursor advance, mirroring
    ///
    /// Returns `Err` if `index > valid_len`
    #[inline(always)]
    pub(crate) fn set_reader_index(&mut self, index: usize) -> Result<(), Error> {
        if index > self.valid_len {
            return Err(Error::buffer_out_of_bound(index, 0, self.valid_len));
        }
        self.read_pos = index;
        Ok(())
    }

    /// Unread bytes in window
    #[inline(always)]
    pub fn remaining(&self) -> usize {
        self.valid_len.saturating_sub(self.read_pos)
    }

    /// Always `true` — used by `Reader` (buffer.rs) to branch into the stream path.
    #[inline(always)]
    pub fn is_stream_backed(&self) -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    /// Reads exactly 1 byte at a time.
    struct OneByteCursor(Cursor<Vec<u8>>);
    impl Read for OneByteCursor {
        fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
            if buf.is_empty() {
                return Ok(0);
            }
            let mut one = [0u8; 1];
            match self.0.read(&mut one)? {
                0 => Ok(0),
                _ => {
                    buf[0] = one[0];
                    Ok(1)
                }
            }
        }
    }

    #[test]
    fn test_rewind() {
        let data = vec![0x01u8, 0x02, 0x03, 0x04, 0x05];
        let mut s = ForyStreamBuf::with_capacity(OneByteCursor(Cursor::new(data)), 2);
        s.fill_buffer(4).unwrap();
        assert_eq!(s.size(), 4);
        assert_eq!(s.reader_index(), 0);
        s.consume(3);
        assert_eq!(s.reader_index(), 3);
        s.rewind(2);
        assert_eq!(s.reader_index(), 1);
        s.consume(1);
        assert_eq!(s.reader_index(), 2);
    }

    #[test]
    fn test_short_read_error() {
        let mut s = ForyStreamBuf::new(Cursor::new(vec![0x01u8, 0x02, 0x03]));
        assert!(s.fill_buffer(4).is_err());
    }

    // Sequential fills with tiny-chunk reader
    #[test]
    fn test_sequential_fill() {
        let data: Vec<u8> = (0u8..=9).collect();
        let mut s = ForyStreamBuf::with_capacity(OneByteCursor(Cursor::new(data)), 2);
        s.fill_buffer(3).unwrap();
        assert!(s.remaining() >= 3);
        s.consume(3);
        s.fill_buffer(3).unwrap();
        assert!(s.remaining() >= 3);
    }

    #[test]
    fn test_overflow_guard() {
        // valid_len near usize::MAX would overflow without the u32 guard.
        // We can't actually allocate that — just verify the guard logic
        // via a saturating check on a real (tiny) stream.
        let mut s = ForyStreamBuf::new(Cursor::new(vec![0u8; 8]));
        // Requesting more than the source has should error, not panic/overflow
        assert!(s.fill_buffer(16).is_err());
    }

    #[test]
    fn test_consume_panics_on_overrun() {
        let result = std::panic::catch_unwind(|| {
            let mut s = ForyStreamBuf::new(Cursor::new(vec![0x01u8]));
            s.fill_buffer(1).unwrap();
            s.consume(2); // only 1 byte available
        });
        assert!(result.is_err());
    }

    #[test]
    fn test_rewind_panics_on_overrun() {
        let result = std::panic::catch_unwind(|| {
            let mut s = ForyStreamBuf::new(Cursor::new(vec![0x01u8, 0x02]));
            s.fill_buffer(2).unwrap();
            s.consume(1);
            s.rewind(2); // only consumed 1
        });
        assert!(result.is_err());
    }

    #[test]
    fn test_set_reader_index() {
        let mut s = ForyStreamBuf::new(Cursor::new(vec![0x01u8, 0x02, 0x03]));
        s.fill_buffer(3).unwrap();
        assert!(s.set_reader_index(2).is_ok());
        assert_eq!(s.reader_index(), 2);
        assert_eq!(s.remaining(), 1);
        assert!(s.set_reader_index(4).is_err()); // beyond valid_len
    }

    #[test]
    fn test_is_stream_backed() {
        let s = ForyStreamBuf::new(Cursor::new(vec![]));
        assert!(s.is_stream_backed());
    }
}
