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
use crate::buffer::Reader;

struct FieldAccessorHelper<'a> {
    row: &'a [u8],
    get_field_offset: Box<dyn Fn(usize) -> usize>,
}

impl<'a> FieldAccessorHelper<'a> {
    fn get_offset_size(&self, idx: usize) -> (u32, u32) {
        let row = self.row;
        let field_offset = (self.get_field_offset)(idx);
        let offset = LittleEndian::read_u32(&row[field_offset..field_offset + 4]);
        let size = LittleEndian::read_u32(&row[field_offset + 4..field_offset + 8]);
        (offset, size)
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
        let (offset, size) = self.get_offset_size(idx);
        &row[(offset as usize)..(offset + size) as usize]
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
        let num_elements = LittleEndian::read_u64(&row[0..8]) as usize;
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
        let key_byte_size = LittleEndian::read_u64(&row[0..8]) as usize;
        MapViewer {
            value_row: &row[key_byte_size + 8..row.len()],
            key_row: &row[8..key_byte_size + 8],
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

pub fn from_row_from<'a, T: Row<'a>>(reader: &Reader<'a>) -> T::ReadResult {
    T::cast(&reader.bf[reader.cursor..])
}
