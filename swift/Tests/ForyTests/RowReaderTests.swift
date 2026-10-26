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

private let rowStringSchema = RowSchema([RowField(name: "s", dataType: .string, nullable: true)])

// A one-string row: 8-byte bitmap, then the given slot, then the given data.
private func rowWithString(slot: [UInt8], data: [UInt8] = [0x61, 0, 0, 0, 0, 0, 0, 0]) throws -> Row {
    try Row(schema: rowStringSchema, bytes: [UInt8](repeating: 0, count: 8) + slot + data)
}

@Test
func rowReaderLayout() throws {
    let schema = RowSchema([
        RowField(name: "a", dataType: .int32, nullable: true),
        RowField(name: "b", dataType: .int32, nullable: false),
        RowField(name: "c", dataType: .string, nullable: true)
    ])
    let bytes: [UInt8] = [
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // bitmap: a is null
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // slot a
        0x2C, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // slot b = 300
        0x02, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00, // slot c: size 2, offset 0x20
        0x68, 0x69, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // "hi"
    ]
    let row = try Row(schema: schema, bytes: bytes)
    #expect(row.fieldCount == 3)
    #expect(row.sizeInBytes == 40)
    #expect(row.isNull(at: 0))
    #expect(row.int32(at: 0) == nil)
    #expect(row.int32(at: 1) == 300)
    #expect(try row.string(at: 2) == "hi")
    #expect(row.toBytes() == bytes)
}

@Test
func rowReaderGoLayout() throws {
    let schema = RowSchema([
        RowField(name: "f1", dataType: .int64, nullable: false),
        RowField(name: "f2", dataType: .string, nullable: true)
    ])
    let bytes: [UInt8] = [
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01,
        0x02, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00,
        0x61, 0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    ]
    let row = try Row(schema: schema, bytes: bytes)
    #expect(row.int64(at: 0) == 0x0102_0304_0506_0708)
    #expect(try row.string(at: 1) == "ab")
}

@Test
func rowReaderNested() throws {
    let schema = RowSchema([
        RowField(name: "id", dataType: .int32, nullable: false),
        RowField(name: "child", dataType: .struct([RowField(name: "x", dataType: .int32, nullable: false)]), nullable: true)
    ])
    let bytes: [UInt8] = [
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // parent bitmap
        0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // slot id = 1
        0x10, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00, // slot child: size 16, offset 0x18
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // child bitmap
        0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // child slot x = 5
    ]
    let row = try Row(schema: schema, bytes: bytes)
    #expect(row.int32(at: 0) == 1)
    let child = try #require(try row.row(at: 1))
    #expect(child.sizeInBytes == 16)
    #expect(child.int32(at: 0) == 5)
    #expect(child.toBytes() == Array(bytes[0x18..<0x28]))
}

@Test
func rowReaderNullSkipsData() throws {
    let schema = RowSchema([
        RowField(name: "s", dataType: .string, nullable: true),
        RowField(name: "b", dataType: .binary, nullable: true),
        RowField(name: "e", dataType: .string, nullable: true)
    ])
    let bytes: [UInt8] = [
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, // s and b are null
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // slot s: garbage
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // slot b: garbage
        0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00 // slot e: empty, not null
    ]
    let row = try Row(schema: schema, bytes: bytes)
    #expect(try row.string(at: 0) == nil)
    #expect(try row.binary(at: 1) == nil)
    #expect(!row.isNull(at: 2))
    #expect(try row.string(at: 2) == "")
}

@Test
func rowReaderFieldCounts() throws {
    for (count, size) in [(0, 0), (63, 512), (64, 520), (65, 536)] {
        let schema = RowSchema((0..<count).map { RowField(name: "f\($0)", dataType: .int64, nullable: true) })
        var bytes = [UInt8](repeating: 0, count: size)
        if count > 0 {
            bytes[(count - 1) / 8] |= 1 << ((count - 1) % 8)
            // The first slot starts right after the bitmap, which grows to 16 bytes at 65 fields.
            bytes[rowBitmapWidth(count)] = 7
        }
        let row = try Row(schema: schema, bytes: bytes)
        #expect(row.sizeInBytes == size)
        if count > 0 {
            #expect(row.isNull(at: count - 1))
            #expect(!row.isNull(at: count - 2))
            #expect(row.int64(at: 0) == 7)
        }
    }
}

@Test
func rowReaderMultiByteUTF8() throws {
    let row = try rowWithString(
        slot: [0x06, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00],
        data: [0x68, 0xC3, 0xA9, 0x6C, 0x6C, 0x6F, 0x00, 0x00]
    )
    #expect(try row.string(at: 0) == "héllo")
}

@Test
func rowReaderTemporal() throws {
    let schema = RowSchema([
        RowField(name: "ts", dataType: .timestamp, nullable: false),
        RowField(name: "d", dataType: .date32, nullable: false),
        RowField(name: "dur", dataType: .duration, nullable: false)
    ])
    let bytes: [UInt8] = [
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, // ts = -1 microsecond
        0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, // d = -1 day
        0x24, 0xFA, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF // dur = -1500 microseconds
    ]
    let row = try Row(schema: schema, bytes: bytes)
    // Counted back from 2001 in whole microseconds, so the check itself adds no rounding.
    func micros(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSinceReferenceDate * 1_000_000).rounded()) + 978_307_200_000_000
    }
    let timestamp = try #require(row.timestamp(at: 0))
    #expect(micros(timestamp) == -1)
    #expect(timestamp < Date(timeIntervalSince1970: 0))
    #expect(micros(rowDate(micros: 1_234_567_890_123_456)) == 1_234_567_890_123_456)
    #expect(row.localDate(at: 1) == LocalDate(epochDay: -1))
    #expect(row.duration(at: 2) == .microseconds(-1500))
}

@Test
func rowReaderCorrupt() throws {
    #expect(throws: ForyError.invalidData("row of 15 bytes is shorter than its 16-byte fixed region")) {
        try Row(schema: rowStringSchema, bytes: [UInt8](repeating: 0, count: 15))
    }
    // Offset 0x18 is the end of the 24-byte row, so even one byte there is past it.
    #expect(throws: ForyError.outOfBounds(cursor: 0x18, need: 1, length: 24)) {
        try rowWithString(slot: [0x01, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00]).string(at: 0)
    }
    #expect(throws: ForyError.outOfBounds(cursor: 0x10, need: 9, length: 24)) {
        try rowWithString(slot: [0x09, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00]).string(at: 0)
    }
    let max = Int(clamping: UInt32.max)
    #expect(throws: ForyError.outOfBounds(cursor: max, need: max, length: 24)) {
        try rowWithString(slot: [UInt8](repeating: 0xFF, count: 8)).binary(at: 0)
    }
    #expect(throws: ForyError.invalidData("invalid UTF-8 sequence")) {
        try rowWithString(
            slot: [0x02, 0x00, 0x00, 0x00, 0x10, 0x00, 0x00, 0x00],
            data: [0xFF, 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]
        ).string(at: 0)
    }
}
