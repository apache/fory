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

use std::collections::BTreeMap;
use std::marker::PhantomData;

use crate::error::Error;

use super::bit_util::{bitmap_width, is_bit_set, round_up_to_word, slot_width};
use super::row::{Row, RowValue};

/// Backing-byte access shared by immutable Standard Row Format views.
pub trait RowView<'a> {
    /// Returns the complete encoded bytes bound to this view.
    fn as_bytes(&self) -> &'a [u8];

    /// Returns the number of encoded bytes bound to this view.
    #[inline]
    fn encoded_len(&self) -> usize {
        self.as_bytes().len()
    }
}

/// A zero-copy view over one Standard Row Format struct.
///
/// This type is public only because `ForyRow` views are generated in
/// downstream crates.
#[doc(hidden)]
#[derive(Clone, Copy)]
pub struct StructView<'a> {
    bytes: &'a [u8],
    bitmap_width: usize,
    num_fields: usize,
    fixed_end: usize,
}

impl<'a> StructView<'a> {
    /// Validates the fixed region for a struct with `num_fields` fields.
    pub fn new(bytes: &'a [u8], num_fields: usize) -> Result<Self, Error> {
        let bitmap_width = bitmap_width(num_fields)?;
        let slots_size = num_fields
            .checked_mul(8)
            .ok_or_else(|| Error::invalid_data("row fixed region size overflow"))?;
        let fixed_end = bitmap_width
            .checked_add(slots_size)
            .ok_or_else(|| Error::invalid_data("row fixed region size overflow"))?;
        ensure_range(bytes, 0, fixed_end)?;
        Ok(Self {
            bytes,
            bitmap_width,
            num_fields,
            fixed_end,
        })
    }

    /// Reads a field at its schema ordinal.
    pub fn get<T: RowValue>(&self, index: usize) -> Result<T::View<'a>, Error> {
        self.check_index(index)?;
        let bitmap = &self.bytes[..self.bitmap_width];
        if is_bit_set(bitmap, index) {
            return T::read_null();
        }

        let slot_offset = self
            .bitmap_width
            .checked_add(index * 8)
            .ok_or_else(|| Error::invalid_data("row field offset overflow"))?;
        let value = match T::FIXED_SIZE {
            Some(width) => {
                slot_width(Some(width))?;
                checked_slice(self.bytes, slot_offset, width)?
            }
            None => variable_slice(self.bytes, slot_offset, self.fixed_end)?,
        };
        T::read(value)
    }

    /// Returns whether a field's null bit is set.
    pub fn is_null(&self, index: usize) -> Result<bool, Error> {
        self.check_index(index)?;
        Ok(is_bit_set(&self.bytes[..self.bitmap_width], index))
    }

    fn check_index(&self, index: usize) -> Result<(), Error> {
        if index >= self.num_fields {
            Err(Error::buffer_out_of_bound(index, 1, self.num_fields))
        } else {
            Ok(())
        }
    }
}

impl<'a> RowView<'a> for StructView<'a> {
    #[inline]
    fn as_bytes(&self) -> &'a [u8] {
        self.bytes
    }
}

/// A zero-copy view over one Standard Row Format array.
pub struct ArrayView<'a, T: RowValue> {
    bytes: &'a [u8],
    num_elements: usize,
    bitmap_width: usize,
    header_size: usize,
    element_size: usize,
    fixed_end: usize,
    marker: PhantomData<T>,
}

impl<T: RowValue> Copy for ArrayView<'_, T> {}

impl<T: RowValue> Clone for ArrayView<'_, T> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<'a, T: RowValue> ArrayView<'a, T> {
    pub(crate) fn new(bytes: &'a [u8]) -> Result<Self, Error> {
        let count = read_u64(bytes, 0)?;
        let num_elements = usize::try_from(count)
            .map_err(|_| Error::invalid_data("row array element count exceeds usize"))?;
        let bitmap_width = bitmap_width(num_elements)?;
        let header_size = 8usize
            .checked_add(bitmap_width)
            .ok_or_else(|| Error::invalid_data("row array header size overflow"))?;
        let element_size = slot_width(T::FIXED_SIZE)?;
        let element_bytes = num_elements
            .checked_mul(element_size)
            .ok_or_else(|| Error::invalid_data("row array fixed region size overflow"))?;
        let aligned_element_bytes = round_up_to_word(element_bytes)?;
        let fixed_end = header_size
            .checked_add(aligned_element_bytes)
            .ok_or_else(|| Error::invalid_data("row array fixed region size overflow"))?;
        ensure_range(bytes, 0, fixed_end)?;
        Ok(Self {
            bytes,
            num_elements,
            bitmap_width,
            header_size,
            element_size,
            fixed_end,
            marker: PhantomData,
        })
    }

    /// Returns the number of elements encoded in this array.
    pub fn len(&self) -> usize {
        self.num_elements
    }

    /// Returns true when this array contains no elements.
    pub fn is_empty(&self) -> bool {
        self.num_elements == 0
    }

    /// Returns an iterator that reads elements on demand.
    pub fn iter(&self) -> ArrayIter<'_, 'a, T> {
        ArrayIter {
            view: self,
            index: 0,
        }
    }

    /// Reads one array element without materializing the rest of the array.
    pub fn get(&self, index: usize) -> Result<T::View<'a>, Error> {
        self.check_index(index)?;
        let bitmap = &self.bytes[8..8 + self.bitmap_width];
        if is_bit_set(bitmap, index) {
            return T::read_null();
        }
        let slot_offset = self
            .header_size
            .checked_add(index * self.element_size)
            .ok_or_else(|| Error::invalid_data("row array element offset overflow"))?;
        let value = match T::FIXED_SIZE {
            Some(width) => checked_slice(self.bytes, slot_offset, width)?,
            None => variable_slice(self.bytes, slot_offset, self.fixed_end)?,
        };
        T::read(value)
    }

    /// Returns whether an element's null bit is set.
    pub fn is_null(&self, index: usize) -> Result<bool, Error> {
        self.check_index(index)?;
        let bitmap_start = 8;
        let bitmap = &self.bytes[bitmap_start..bitmap_start + self.bitmap_width];
        Ok(is_bit_set(bitmap, index))
    }

    fn check_index(&self, index: usize) -> Result<(), Error> {
        if index >= self.num_elements {
            Err(Error::buffer_out_of_bound(index, 1, self.num_elements))
        } else {
            Ok(())
        }
    }
}

impl<'a, T: RowValue> RowView<'a> for ArrayView<'a, T> {
    #[inline]
    fn as_bytes(&self) -> &'a [u8] {
        self.bytes
    }
}

/// An iterator over the elements of an [`ArrayView`].
pub struct ArrayIter<'view, 'row, T: RowValue> {
    view: &'view ArrayView<'row, T>,
    index: usize,
}

impl<'row, T: RowValue> Iterator for ArrayIter<'_, 'row, T> {
    type Item = Result<T::View<'row>, Error>;

    fn next(&mut self) -> Option<Self::Item> {
        if self.index == self.view.len() {
            return None;
        }
        let index = self.index;
        self.index += 1;
        Some(self.view.get(index))
    }

    fn size_hint(&self) -> (usize, Option<usize>) {
        let remaining = self.view.len() - self.index;
        (remaining, Some(remaining))
    }
}

impl<T: RowValue> ExactSizeIterator for ArrayIter<'_, '_, T> {}

impl<'view, 'row, T: RowValue> IntoIterator for &'view ArrayView<'row, T> {
    type Item = Result<T::View<'row>, Error>;
    type IntoIter = ArrayIter<'view, 'row, T>;

    fn into_iter(self) -> Self::IntoIter {
        self.iter()
    }
}

/// A zero-copy view over one Standard Row Format map.
pub struct MapView<'a, K: RowValue, V: RowValue> {
    bytes: &'a [u8],
    keys: ArrayView<'a, K>,
    values: ArrayView<'a, V>,
}

impl<K: RowValue, V: RowValue> Copy for MapView<'_, K, V> {}

impl<K: RowValue, V: RowValue> Clone for MapView<'_, K, V> {
    fn clone(&self) -> Self {
        *self
    }
}

impl<'a, K: RowValue, V: RowValue> MapView<'a, K, V> {
    pub(crate) fn new(bytes: &'a [u8]) -> Result<Self, Error> {
        let keys_size = read_u64(bytes, 0)?;
        let keys_size = usize::try_from(keys_size)
            .map_err(|_| Error::invalid_data("row map key array size exceeds usize"))?;
        let keys_end = 8usize
            .checked_add(keys_size)
            .ok_or_else(|| Error::invalid_data("row map key array size overflow"))?;
        ensure_range(bytes, 8, keys_size)?;
        let keys = ArrayView::<K>::new(&bytes[8..keys_end])?;
        let values = ArrayView::<V>::new(&bytes[keys_end..])?;
        if keys.len() != values.len() {
            return Err(Error::invalid_data(
                "row map key and value arrays have different lengths",
            ));
        }
        Ok(Self {
            bytes,
            keys,
            values,
        })
    }

    /// Returns the number of key-value pairs encoded in this map.
    pub fn len(&self) -> usize {
        self.keys.len()
    }

    /// Returns true when this map contains no key-value pairs.
    pub fn is_empty(&self) -> bool {
        self.keys.is_empty()
    }

    /// Reads the key at `index` without materializing the map.
    pub fn key(&self, index: usize) -> Result<K::View<'a>, Error> {
        self.keys.get(index)
    }

    /// Reads the value at `index` without materializing the map.
    pub fn value(&self, index: usize) -> Result<V::View<'a>, Error> {
        self.values.get(index)
    }

    /// Returns the map's key array.
    pub fn keys(&self) -> &ArrayView<'a, K> {
        &self.keys
    }

    /// Returns the map's value array.
    pub fn values(&self) -> &ArrayView<'a, V> {
        &self.values
    }

    /// Materializes this view as a `BTreeMap`.
    pub fn to_btree_map(
        &self,
    ) -> Result<BTreeMap<<K as RowValue>::View<'a>, <V as RowValue>::View<'a>>, Error>
    where
        <K as RowValue>::View<'a>: Ord,
    {
        let mut map = BTreeMap::new();
        for index in 0..self.keys.len() {
            map.insert(self.keys.get(index)?, self.values.get(index)?);
        }
        Ok(map)
    }
}

impl<'a, K: RowValue, V: RowValue> RowView<'a> for MapView<'a, K, V> {
    #[inline]
    fn as_bytes(&self) -> &'a [u8] {
        self.bytes
    }
}

fn variable_slice(bytes: &[u8], slot_offset: usize, fixed_end: usize) -> Result<&[u8], Error> {
    let offset_and_size = read_u64(bytes, slot_offset)?;
    let relative_offset = usize::try_from(offset_and_size >> 32)
        .map_err(|_| Error::invalid_data("row variable offset exceeds usize"))?;
    let size = (offset_and_size as u32) as usize;
    if relative_offset < fixed_end {
        return Err(Error::invalid_data(
            "row variable value overlaps the fixed region",
        ));
    }
    checked_slice(bytes, relative_offset, size)
}

fn read_u64(bytes: &[u8], offset: usize) -> Result<u64, Error> {
    let value = checked_slice(bytes, offset, 8)?;
    let mut array = [0u8; 8];
    array.copy_from_slice(value);
    Ok(u64::from_le_bytes(array))
}

fn checked_slice(bytes: &[u8], offset: usize, size: usize) -> Result<&[u8], Error> {
    let end = offset
        .checked_add(size)
        .ok_or_else(|| Error::buffer_out_of_bound(offset, size, bytes.len()))?;
    if end > bytes.len() {
        Err(Error::buffer_out_of_bound(offset, size, bytes.len()))
    } else {
        Ok(&bytes[offset..end])
    }
}

fn ensure_range(bytes: &[u8], offset: usize, size: usize) -> Result<(), Error> {
    checked_slice(bytes, offset, size).map(|_| ())
}

/// Decodes a Standard Row Format struct, array, or map root.
pub fn from_row<T: Row>(bytes: &[u8]) -> Result<T::View<'_>, Error> {
    T::read(bytes)
}
