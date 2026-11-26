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

import Foundation
import Testing
@testable import Fory

private let rowSection31Schema = RowSchema([
    RowField(name: "a", dataType: .int32, nullable: true),
    RowField(name: "b", dataType: .int32, nullable: false),
    RowField(name: "c", dataType: .string, nullable: true)
])

private let rowSection31Bytes: [UInt8] = [
    0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // bitmap: a is null
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // slot a
    0x2C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // slot b = 300
    0x02, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,  // slot c: size 2, offset 0x20
    0x68, 0x69, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // "hi"
]

private func writeSection31Row(into buffer: ByteBuffer) throws -> RowWriter {
    var writer = RowWriter(schema: rowSection31Schema, buffer: buffer)
    writer.reset()
    writer.setNullAt(0)
    writer.writeInt32(300, at: 1)
    try writer.writeString("hi", at: 2)
    return writer
}

@Test
func rowWriterLayout() throws {
    #expect(try writeSection31Row(into: ByteBuffer()).toBytes() == rowSection31Bytes)

    // A row that starts after other bytes still counts its offsets from its own start.
    let buffer = ByteBuffer()
    buffer.writeBytes([UInt8](repeating: 0xAA, count: 8))
    let writer = try writeSection31Row(into: buffer)
    #expect(writer.base == 8)
    #expect(writer.size == 40)
    #expect(writer.toBytes() == rowSection31Bytes)
}

@Test
func rowWriterGoLayout() throws {
    let schema = RowSchema([
        RowField(name: "f1", dataType: .int64, nullable: false),
        RowField(name: "f2", dataType: .string, nullable: true)
    ])
    var writer = RowWriter(schema: schema)
    writer.reset()
    writer.writeInt64(0x0102_0304_0506_0708, at: 0)
    try writer.writeString("ab", at: 1)
    #expect(
        writer.toBytes() == [
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
            0x02, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00,
            0x61, 0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        ])
}

@Test
func rowWriterPrimitives() throws {
    let schema = RowSchema([
        RowField(name: "b", dataType: .bool, nullable: false),
        RowField(name: "i8", dataType: .int8, nullable: false),
        RowField(name: "i16", dataType: .int16, nullable: false),
        RowField(name: "i32", dataType: .int32, nullable: false),
        RowField(name: "i64", dataType: .int64, nullable: false)
    ])
    for useMin in [true, false] {
        var writer = RowWriter(schema: schema)
        writer.reset()
        writer.writeBool(useMin, at: 0)
        writer.writeInt8(useMin ? .min : .max, at: 1)
        writer.writeInt16(useMin ? .min : .max, at: 2)
        writer.writeInt32(useMin ? .min : .max, at: 3)
        writer.writeInt64(useMin ? .min : .max, at: 4)
        let row = try Row(schema: schema, bytes: writer.toBytes())
        #expect(row.bool(at: 0) == useMin)
        #expect(row.int8(at: 1) == (useMin ? .min : .max))
        #expect(row.int16(at: 2) == (useMin ? .min : .max))
        #expect(row.int32(at: 3) == (useMin ? .min : .max))
        #expect(row.int64(at: 4) == (useMin ? .min : .max))
    }
}

@Test
func rowWriterFloatBits() throws {
    let floats: [Float] = [Float(bitPattern: 0x7FC0_0001), -0.0, .infinity, -.infinity]
    let doubles: [Double] = [Double(bitPattern: 0x7FF8_0000_0000_0001), -0.0, .infinity, -.infinity]
    let schema = RowSchema(
        floats.indices.map { RowField(name: "f\($0)", dataType: .float32, nullable: false) }
            + doubles.indices.map { RowField(name: "d\($0)", dataType: .float64, nullable: false) }
    )
    var writer = RowWriter(schema: schema)
    writer.reset()
    for (index, value) in floats.enumerated() {
        writer.writeFloat32(value, at: index)
    }
    for (index, value) in doubles.enumerated() {
        writer.writeFloat64(value, at: floats.count + index)
    }
    let row = try Row(schema: schema, bytes: writer.toBytes())
    // NaN never equals itself, so the bits are compared instead of the values.
    for (index, value) in floats.enumerated() {
        #expect(row.float32(at: index)?.bitPattern == value.bitPattern)
    }
    for (index, value) in doubles.enumerated() {
        #expect(row.float64(at: floats.count + index)?.bitPattern == value.bitPattern)
    }
}

@Test
func rowWriterNullEveryType() throws {
    let types: [RowDataType] = [
        .bool, .int8, .int16, .int32, .int64, .float32, .float64, .date32, .timestamp, .duration, .string, .binary
    ]
    let schema = RowSchema(types.enumerated().map { RowField(name: "f\($0.offset)", dataType: $0.element, nullable: true) })
    var writer = RowWriter(schema: schema)
    writer.reset()
    // Fill the fixed-width slots first, so setNullAt has something to zero.
    for index in 0..<10 {
        writer.writeInt8(-1, at: index)
    }
    for index in types.indices {
        writer.setNullAt(index)
    }
    let bytes = writer.toBytes()
    #expect(writer.size == 8 + types.count * 8)
    #expect(Array(bytes[0..<8]) == [0xFF, 0x0F, 0, 0, 0, 0, 0, 0])
    #expect(bytes[8...].allSatisfy { $0 == 0 })
}

@Test
func rowWriterEmptyString() throws {
    var writer = RowWriter(schema: RowSchema([RowField(name: "s", dataType: .string, nullable: true)]))
    writer.reset()
    try writer.writeString("", at: 0)
    #expect(
        writer.toBytes() == [
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00  // size 0, offset 0x10 (the end of the slots)
        ])
}

@Test
func rowWriterMultiByteUTF8() throws {
    var writer = RowWriter(schema: RowSchema([RowField(name: "s", dataType: .string, nullable: true)]))
    writer.reset()
    try writer.writeString("héllo", at: 0)
    #expect(
        Array(writer.toBytes()[8...]) == [
            0x06, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00,
            0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F, 0x00, 0x00
        ])
}

@Test
func rowWriterFieldCounts() throws {
    for (count, size) in [(0, 0), (63, 512), (64, 520), (65, 536)] {
        let schema = RowSchema((0..<count).map { RowField(name: "f\($0)", dataType: .int64, nullable: true) })
        var writer = RowWriter(schema: schema)
        writer.reset()
        #expect(writer.size == size)
        if count > 0 {
            writer.setNullAt(count - 1)
            let bytes = writer.toBytes()
            #expect(bytes[(count - 1) / 8] == 1 << ((count - 1) % 8))
            let row = try Row(schema: schema, bytes: bytes)
            #expect(row.isNull(at: count - 1))
            #expect(!row.isNull(at: count - 2))
        }
    }
}

@Test
func rowWriterDeterministic() throws {
    let first = try writeSection31Row(into: ByteBuffer()).toBytes()
    let second = try writeSection31Row(into: ByteBuffer()).toBytes()
    let reused = ByteBuffer()
    reused.writeBytes([UInt8](repeating: 0xFF, count: 128))
    reused.clear()
    let third = try writeSection31Row(into: reused).toBytes()
    #expect(first == second)
    #expect(first == third)
}

@Test
func rowWriterTimestamp() throws {
    let window = Int64(8_589_934_591)
    let samples: [Int64] = [
        0, -1, 1, 1_700_000_000_123_457, -1_000_000_000_000_000,
        (978_307_200 + window) * 1_000_000 + 999_999,
        (978_307_200 - window) * 1_000_000 + 999_999
    ]
    for micros in samples {
        #expect(try rowTimestampMicros(rowDate(micros: micros)) == micros)
    }

    let schema = RowSchema([RowField(name: "t", dataType: .timestamp, nullable: false)])
    var writer = RowWriter(schema: schema)
    writer.reset()
    try writer.writeTimestamp(rowDate(micros: -1), at: 0)
    #expect(try Row(schema: schema, bytes: writer.toBytes()).int64(at: 0) == -1)

    #expect(throws: ForyError.encodingError("timestamp inf seconds from 2001 does not fit in 64-bit microseconds")) {
        try writer.writeTimestamp(Date(timeIntervalSinceReferenceDate: .infinity), at: 0)
    }
}

@Test
func rowWriterDuration() throws {
    #expect(try rowDurationMicros(.nanoseconds(-1)) == 0)
    #expect(try rowDurationMicros(.nanoseconds(1_999)) == 1)
    #expect(try rowDurationMicros(.nanoseconds(-1_999)) == -1)
    #expect(try rowDurationMicros(.microseconds(Int64.min)) == Int64.min)

    let schema = RowSchema([RowField(name: "d", dataType: .duration, nullable: false)])
    var writer = RowWriter(schema: schema)
    writer.reset()
    try writer.writeDuration(.microseconds(-1_500), at: 0)
    #expect(try Row(schema: schema, bytes: writer.toBytes()).duration(at: 0) == .microseconds(-1_500))

    #expect(throws: ForyError.self) {
        try writer.writeDuration(.seconds(Int64.max), at: 0)
    }
}

@Test
func rowWriterLocalDate() throws {
    let schema = RowSchema([RowField(name: "d", dataType: .date32, nullable: false)])
    var writer = RowWriter(schema: schema)
    writer.reset()
    for epochDay in [-1, Int64(Int32.max)] {
        try writer.writeLocalDate(LocalDate(epochDay: epochDay), at: 0)
        #expect(try Row(schema: schema, bytes: writer.toBytes()).localDate(at: 0) == LocalDate(epochDay: epochDay))
    }
    #expect(throws: ForyError.encodingError("date 2147483648 days from 1970 does not fit in 32 bits")) {
        try writer.writeLocalDate(LocalDate(epochDay: Int64(Int32.max) + 1), at: 0)
    }
}
