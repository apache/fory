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

use super::{bit_util::calculate_bitmap_width_in_bytes, row::Row};
use byteorder::{ByteOrder, LittleEndian};

struct FieldAccessorHelper<'a> {
    row: &'a [u8],
    get_field_offset: Box<dyn Fn(usize) -> usize>,
}

impl<'a> FieldAccessorHelper<'a> {
    fn read_u32(row: &[u8], offset: usize) -> Option<u32> {
        // in case `row` does not have enough bytes
        let end = offset.checked_add(4)?;
        let bytes = row.get(offset..end)?;
        Some(LittleEndian::read_u32(bytes))
    }

    fn get_offset_size(&self, idx: usize) -> Option<(u32, u32)> {
        let row = self.row;
        let field_offset = (self.get_field_offset)(idx);
        let offset = Self::read_u32(row, field_offset)?;
        let size_offset = field_offset.checked_add(4)?;
        let size = Self::read_u32(row, size_offset)?;
        Some((offset, size))
    }

    pub fn new(
        row: &'a [u8],
        get_field_offset: Box<dyn Fn(usize) -> usize>,
    ) -> FieldAccessorHelper<'a> {
        FieldAccessorHelper {
            row,
            get_field_offset,
        }
    }

    pub fn get_field_bytes(&self, idx: usize) -> &'a [u8] {
        let row = self.row;
        let Some((offset, size)) = self.get_offset_size(idx) else {
            return &[];
        };
        let offset = offset as usize;
        let size = size as usize;
        let Some(end) = offset.checked_add(size) else {
            return &[];
        };
        row.get(offset..end).unwrap_or(&[])
    }
}

pub struct StructViewer<'r> {
    field_accessor_helper: FieldAccessorHelper<'r>,
}

impl<'r> StructViewer<'r> {
    pub fn new(row: &'r [u8], num_fields: usize) -> StructViewer<'r> {
        let bit_map_width_in_bytes = calculate_bitmap_width_in_bytes(num_fields);
        StructViewer {
            field_accessor_helper: FieldAccessorHelper::new(
                row,
                Box::new(move |idx: usize| bit_map_width_in_bytes + idx * 8),
            ),
        }
    }

    pub fn get_field_bytes(&self, idx: usize) -> &'r [u8] {
        self.field_accessor_helper.get_field_bytes(idx)
    }
}

pub struct ArrayViewer<'r> {
    num_elements: usize,
    field_accessor_helper: FieldAccessorHelper<'r>,
}

impl<'r> ArrayViewer<'r> {
    pub fn new(row: &'r [u8]) -> ArrayViewer<'r> {
        let num_elements = row
            .get(0..8)
            .map(|bytes| LittleEndian::read_u64(bytes) as usize)
            .unwrap_or(0);
        let bit_map_width_in_bytes = calculate_bitmap_width_in_bytes(num_elements);
        ArrayViewer {
            num_elements,
            field_accessor_helper: FieldAccessorHelper::new(
                row,
                Box::new(move |idx: usize| 8 + bit_map_width_in_bytes + idx * 8),
            ),
        }
    }

    pub fn num_elements(&self) -> usize {
        self.num_elements
    }

    pub fn get_field_bytes(&self, idx: usize) -> &'r [u8] {
        self.field_accessor_helper.get_field_bytes(idx)
    }
}

pub struct MapViewer<'r> {
    key_row: &'r [u8],
    value_row: &'r [u8],
}

impl<'r> MapViewer<'r> {
    pub fn new(row: &'r [u8]) -> MapViewer<'r> {
        let Some(header) = row.get(0..8) else {
            return MapViewer {
                key_row: &[],
                value_row: &[],
            };
        };
        let key_byte_size = LittleEndian::read_u64(header) as usize;
        let key_end = (8usize).saturating_add(key_byte_size).min(row.len());
        MapViewer {
            value_row: &row[key_end..],
            key_row: &row[8..key_end],
        }
    }

    pub fn get_key_row(&self) -> &[u8] {
        self.key_row
    }

    pub fn get_value_row(&self) -> &[u8] {
        self.value_row
    }
}

pub fn from_row<'a, T: Row<'a>>(row: &'a [u8]) -> T::ReadResult {
    T::cast(row)
}
