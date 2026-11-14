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

// A one-field row whose only slot points at `value`, so the value starts at byte 16 rather than 0.
private func rowHolding(_ value: [UInt8], as dataType: RowDataType) throws -> Row {
    let slot = withUnsafeBytes(of: (UInt64(16) << 32 | UInt64(value.count)).littleEndian, Array.init)
    let schema = RowSchema([RowField(name: "v", dataType: dataType, nullable: true)])
    return try Row(schema: schema, bytes: [UInt8](repeating: 0, count: 8) + slot + value)
}

private func rowArray(_ bytes: [UInt8], of dataType: RowDataType) throws -> RowArray {
    try RowArray(element: RowField(name: "item", dataType: dataType, nullable: true), bytes: bytes, base: 0, count: bytes.count)
}

@Test
func rowArrayStrings() throws {
    let bytes: [UInt8] = [
        0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 3 elements
        0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // bitmap: element 1 is null
        0x02, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00,  // element 0: size 2, offset 0x28
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // element 1: null
        0x03, 0x00, 0x00, 0x00, 0x30, 0x00, 0x00, 0x00,  // element 2: size 3, offset 0x30
        0x61, 0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // "ab"
        0x78, 0x79, 0x7A, 0x00, 0x00, 0x00, 0x00, 0x00  // "xyz"
    ]
    let array = try #require(try rowHolding(bytes, as: .listOf(.string)).array(at: 0))
    #expect(array.elementCount == 3)
    #expect(array.sizeInBytes == 56)
    #expect(try array.string(at: 0) == "ab")
    #expect(array.isNull(at: 1))
    #expect(try array.string(at: 1) == nil)
    #expect(try array.string(at: 2) == "xyz")
    #expect(array.toBytes() == bytes)
}

@Test
func rowArrayEmpty() throws {
    let array = try rowArray([UInt8](repeating: 0, count: 8), of: .int32)
    #expect(array.elementCount == 0)
    #expect(array.sizeInBytes == 8)
}

@Test
func rowArrayCounts() throws {
    for (count, headerSize, size) in [(64, 16, 80), (65, 24, 96)] {
        var bytes = [UInt8](repeating: 0, count: size)
        bytes[0] = UInt8(count)
        bytes[8 + (count - 1) / 8] |= 1 << ((count - 1) % 8)
        bytes[headerSize] = 7
        let array = try rowArray(bytes, of: .int8)
        #expect(array.elementCount == count)
        #expect(array.isNull(at: count - 1))
        #expect(!array.isNull(at: count - 2))
        #expect(array.int8(at: 0) == 7)
    }
}

@Test
func rowArrayWidths() throws {
    let header: [UInt8] = [0x03, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 8)

    let int8s = try rowArray(header + [0x01, 0xFE, 0x7F, 0, 0, 0, 0, 0], of: .int8)
    #expect(int8s.sizeInBytes == 24)
    #expect((0..<3).map { int8s.int8(at: $0) } == [1, -2, 127])

    let int16s = try rowArray(header + [0x02, 0x01, 0xFE, 0xFF, 0x03, 0x00, 0, 0], of: .int16)
    #expect(int16s.sizeInBytes == 24)
    #expect((0..<3).map { int16s.int16(at: $0) } == [0x0102, -2, 3])

    let int32s = try rowArray(
        header + [0x04, 0x03, 0x02, 0x01, 0xFE, 0xFF, 0xFF, 0xFF, 0x03, 0x00, 0x00, 0x00, 0, 0, 0, 0],
        of: .int32
    )
    #expect(int32s.sizeInBytes == 32)
    #expect((0..<3).map { int32s.int32(at: $0) } == [0x0102_0304, -2, 3])

    let int64s = try rowArray(
        header + [0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01]
            + [0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF]
            + [0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00],
        of: .int64
    )
    #expect(int64s.sizeInBytes == 40)
    #expect((0..<3).map { int64s.int64(at: $0) } == [0x0102_0304_0506_0708, -2, 3])
}

@Test
func rowMapLayout() throws {
    let bytes: [UInt8] = [
        0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // keys array is 0x30 bytes
        0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // keys: 2 elements
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // keys bitmap
        0x02, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,  // key 0: size 2, offset 0x20 from the keys array
        0x02, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00,  // key 1: size 2, offset 0x28 from the keys array
        0x6B, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // "k1"
        0x6B, 0x32, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // "k2"
        0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // values: 2 elements
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // values bitmap
        0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00  // int32 1 and 2
    ]
    let map = try #require(try rowHolding(bytes, as: .mapOf(.string, .int32)).map(at: 0))
    #expect(map.count == 2)
    #expect(try map.keys.string(at: 0) == "k1")
    #expect(try map.keys.string(at: 1) == "k2")
    #expect(map.values.int32(at: 0) == 1)
    #expect(map.values.int32(at: 1) == 2)
}

@Test
func rowArrayCorrupt() throws {
    #expect(throws: ForyError.invalidData("array of 4 bytes is shorter than its 8-byte element count")) {
        try rowArray([0, 0, 0, 0], of: .int8)
    }
    #expect(throws: ForyError.invalidData("array declares \(UInt64.max) elements but holds only 16 bytes")) {
        try rowArray([UInt8](repeating: 0xFF, count: 8) + [UInt8](repeating: 0, count: 8), of: .int8)
    }
    // Four int64 elements need 16 + 32 bytes, but only 32 are there.
    #expect(throws: ForyError.invalidData("array declares 4 elements but holds only 32 bytes")) {
        try rowArray([0x04, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 24), of: .int64)
    }
    let pastEnd = try rowArray(
        [0x01, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 8) + [0x01, 0, 0, 0, 0x18, 0, 0, 0],
        of: .string
    )
    #expect(throws: ForyError.outOfBounds(cursor: 0x18, need: 1, length: 24)) {
        try pastEnd.string(at: 0)
    }
}

@Test
func rowMapCorrupt() throws {
    let mapType = RowDataType.mapOf(.int32, .int32)
    #expect(throws: ForyError.invalidData("map keys array of 16 bytes does not fit in a 16-byte map")) {
        try rowHolding([0x10, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 8), as: mapType).map(at: 0)
    }
    let oneKey: [UInt8] = [0x01, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 16)
    let noValues = [UInt8](repeating: 0, count: 8)
    #expect(throws: ForyError.invalidData("map has 1 keys but 0 values")) {
        try rowHolding([0x18, 0, 0, 0, 0, 0, 0, 0] + oneKey + noValues, as: mapType).map(at: 0)
    }
}
