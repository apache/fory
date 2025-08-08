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

use crate::buffer::Writer;

use super::{bit_util::calculate_bitmap_width_in_bytes, row::Row};

pub struct WriteCallbackInfo {
    field_offset: usize,
    data_start: usize,
}

struct FieldWriterHelper<'a> {
    pub writer: &'a mut Writer,
    base_offset: usize,
    get_field_offset: Box<dyn Fn(usize) -> usize>,
}

impl<'a> FieldWriterHelper<'a> {
    fn new(
        writer: &'a mut Writer,
        base_offset: usize,
        get_field_offset: Box<dyn Fn(usize) -> usize>,
    ) -> FieldWriterHelper<'a> {
        FieldWriterHelper {
            writer,
            base_offset,
            get_field_offset,
        }
    }

    fn write_start(&mut self, idx: usize) -> WriteCallbackInfo {
        let base_offset = self.base_offset;
        let field_offset = (self.get_field_offset)(idx);
        let writer: &mut Writer = self.writer;
        let offset = writer.len() - base_offset;
        writer.set_bytes(field_offset, &(offset as u32).to_le_bytes());
        let data_start: usize = writer.len();
        WriteCallbackInfo {
            field_offset,
            data_start,
        }
    }

    fn write_end(&mut self, callback_info: WriteCallbackInfo) {
        let writer: &mut Writer = self.writer;
        let size: usize = writer.len() - callback_info.data_start;
        writer.set_bytes(callback_info.field_offset + 4, &(size as u32).to_le_bytes());
    }
}

pub struct StructWriter<'a> {
    field_writer_helper: FieldWriterHelper<'a>,
}

impl StructWriter<'_> {
    fn get_fixed_size(bit_map_width_in_bytes: usize, num_fields: usize) -> usize {
        bit_map_width_in_bytes + num_fields * 8
    }
    pub fn new(num_fields: usize, writer: &mut Writer) -> StructWriter<'_> {
        let base_offset = writer.len();
        let bit_map_width_in_bytes = calculate_bitmap_width_in_bytes(num_fields);

        let struct_writer = StructWriter {
            field_writer_helper: FieldWriterHelper::new(
                writer,
                base_offset,
                Box::new(move |idx| base_offset + bit_map_width_in_bytes + idx * 8),
            ),
        };
        let fixed_size = Self::get_fixed_size(bit_map_width_in_bytes, num_fields);
        struct_writer.field_writer_helper.writer.reserve(fixed_size);
        struct_writer.field_writer_helper.writer.skip(fixed_size);
        struct_writer
    }

    pub fn get_writer(&mut self) -> &mut Writer {
        self.field_writer_helper.writer
    }

    pub fn write_start(&mut self, idx: usize) -> WriteCallbackInfo {
        self.field_writer_helper.write_start(idx)
    }

    pub fn write_end(&mut self, callback_info: WriteCallbackInfo) {
        self.field_writer_helper.write_end(callback_info)
    }
}

pub struct ArrayWriter<'a> {
    field_writer_helper: FieldWriterHelper<'a>,
}

impl ArrayWriter<'_> {
    fn get_fixed_size(bit_map_width_in_bytes: usize, num_fields: usize) -> usize {
        8 + bit_map_width_in_bytes + num_fields * 8
    }

    pub fn new(num_fields: usize, writer: &mut Writer) -> ArrayWriter<'_> {
        let base_offset = writer.len();
        let bit_map_width_in_bytes = calculate_bitmap_width_in_bytes(num_fields);
        let array_writer = ArrayWriter {
            field_writer_helper: FieldWriterHelper::new(
                writer,
                base_offset,
                Box::new(move |idx| 8 + base_offset + bit_map_width_in_bytes + idx * 8),
            ),
        };
        let fixed_size = Self::get_fixed_size(bit_map_width_in_bytes, num_fields);
        array_writer.field_writer_helper.writer.reserve(fixed_size);
        array_writer
            .field_writer_helper
            .writer
            .u64(num_fields as u64);
        array_writer.field_writer_helper.writer.skip(fixed_size - 8);
        array_writer
    }

    pub fn get_writer(&mut self) -> &mut Writer {
        self.field_writer_helper.writer
    }

    pub fn write_start(&mut self, idx: usize) -> WriteCallbackInfo {
        self.field_writer_helper.write_start(idx)
    }

    pub fn write_end(&mut self, callback_info: WriteCallbackInfo) {
        self.field_writer_helper.write_end(callback_info)
    }
}

pub struct MapWriter<'a> {
    base_offset: usize,
    writer: &'a mut Writer,
}

impl MapWriter<'_> {
    fn get_fixed_size(&self) -> usize {
        // key_byte_size
        8
    }

    pub fn new(writer: &mut Writer) -> MapWriter<'_> {
        let base_offset = writer.len();
        let array_writer = MapWriter {
            writer,
            base_offset,
        };
        let fixed_size = array_writer.get_fixed_size();
        array_writer.writer.reserve(fixed_size);
        array_writer.writer.skip(fixed_size);
        array_writer
    }

    pub fn get_writer(&mut self) -> &mut Writer {
        self.writer
    }

    pub fn write_start(&mut self, _idx: usize) -> usize {
        self.writer.len()
    }

    pub fn write_end(&mut self, data_start: usize) {
        let size: usize = self.writer.len() - data_start;
        self.writer
            .set_bytes(self.base_offset, &(size as u64).to_le_bytes());
    }
}

pub fn to_row<'a, T: Row<'a>>(v: &T) -> Vec<u8> {
    let mut writer = Writer::default();
    T::write(v, &mut writer);
    writer.dump()
}
