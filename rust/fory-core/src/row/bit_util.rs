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

const WORD_SIZE: usize = 8;
const WORD_BITS: usize = WORD_SIZE * 8;

pub(crate) fn bitmap_width(num_values: usize) -> Result<usize, Error> {
    let words = num_values
        .checked_add(WORD_BITS - 1)
        .ok_or_else(|| Error::invalid_data("row bitmap width overflow"))?
        / WORD_BITS;
    words
        .checked_mul(WORD_SIZE)
        .ok_or_else(|| Error::invalid_data("row bitmap width overflow"))
}

pub(crate) fn round_up_to_word(size: usize) -> Result<usize, Error> {
    size.checked_add(WORD_SIZE - 1)
        .map(|value| value & !(WORD_SIZE - 1))
        .ok_or_else(|| Error::invalid_data("row size alignment overflow"))
}

pub(crate) fn slot_width(fixed_size: Option<usize>) -> Result<usize, Error> {
    match fixed_size {
        Some(width) if matches!(width, 1 | 2 | 4 | 8) => Ok(width),
        Some(_) => Err(Error::invalid_data(
            "row fixed-width values must occupy 1, 2, 4, or 8 bytes",
        )),
        None => Ok(8),
    }
}

#[inline(always)]
pub(crate) fn is_bit_set(bitmap: &[u8], index: usize) -> bool {
    bitmap[index >> 3] & (1 << (index & 7)) != 0
}

#[inline(always)]
pub(crate) fn set_bit(bitmap: &mut [u8], index: usize) {
    bitmap[index >> 3] |= 1 << (index & 7);
}
