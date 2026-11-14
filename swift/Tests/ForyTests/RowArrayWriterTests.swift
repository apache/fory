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

private let pointFields = [RowField(name: "x", dataType: .int32, nullable: false)]

private func field(_ dataType: RowDataType) -> RowField {
    RowField(name: "item", dataType: dataType, nullable: true)
}

private func writePoint(_ value: Int32, into buffer: ByteBuffer) -> (start: Int, size: Int) {
    var point = RowWriter(fields: pointFields, buffer: buffer)
    point.reset()
    point.writeInt32(value, at: 0)
    return (point.base, point.size)
}

@Test
func rowArrayWriterStrings() throws {
    var writer = RowArrayWriter(element: field(.string), buffer: ByteBuffer())
    try writer.reset(count: 3)
    try writer.writeString("ab", at: 0)
    writer.setNullAt(1)
    try writer.writeString("xyz", at: 2)
    #expect(
        writer.toBytes() == [
            0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 3 elements
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // bitmap: element 1 is null
            0x02, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00,  // element 0: size 2, offset 0x28
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // element 1: null
            0x03, 0x00, 0x00, 0x00, 0x30, 0x00, 0x00, 0x00,  // element 2: size 3, offset 0x30
            0x61, 0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // "ab"
            0x78, 0x79, 0x7A, 0x00, 0x00, 0x00, 0x00, 0x00  // "xyz"
        ])
}

@Test
func rowMapWriterLayout() throws {
    let buffer = ByteBuffer()
    var map = RowMapWriter(buffer: buffer)
    map.reset()
    var keys = RowArrayWriter(element: field(.string), buffer: buffer)
    try keys.reset(count: 2)
    try keys.writeString("k1", at: 0)
    try keys.writeString("k2", at: 1)
    map.finishKeys()
    var values = RowArrayWriter(element: field(.int32), buffer: buffer)
    try values.reset(count: 2)
    values.writeInt32(1, at: 0)
    values.writeInt32(2, at: 1)
    #expect(map.size == 80)
    #expect(
        buffer.copyBytes(start: 0, end: buffer.count) == [
            0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // keys array is 48 bytes
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 2 keys
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // key bitmap
            0x02, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,  // key 0: offset counted from the keys array
            0x02, 0x00, 0x00, 0x00, 0x28, 0x00, 0x00, 0x00,  // key 1
            0x6B, 0x31, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // "k1"
            0x6B, 0x32, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // "k2"
            0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // 2 values
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // value bitmap
            0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00  // 1 and 2
        ])
}

@Test
func rowWriterNested() throws {
    let schema = RowSchema([
        RowField(name: "id", dataType: .int32, nullable: false),
        RowField(name: "child", dataType: .struct(pointFields), nullable: true)
    ])
    var parent = RowWriter(schema: schema)
    parent.reset()
    parent.writeInt32(1, at: 0)
    let child = writePoint(5, into: parent.buffer)
    try parent.setOffsetAndSize(at: 1, absoluteStart: child.start, size: child.size)
    #expect(
        parent.toBytes() == [
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // parent bitmap
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // id = 1
            0x10, 0x00, 0x00, 0x00, 0x18, 0x00, 0x00, 0x00,  // child: size 16, offset 0x18
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  // child bitmap
            0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00  // x = 5
        ])
}

@Test
func rowArrayWriterWidths() throws {
    for (dataType, size) in [(RowDataType.int8, 24), (.int16, 24), (.int32, 32), (.int64, 40)] {
        var writer = RowArrayWriter(element: field(dataType), buffer: ByteBuffer())
        try writer.reset(count: 3)
        for index in 0..<3 {
            switch dataType {
            case .int8: writer.writeInt8(-1, at: index)
            case .int16: writer.writeInt16(-1, at: index)
            case .int32: writer.writeInt32(-1, at: index)
            default: writer.writeInt64(-1, at: index)
            }
        }
        let filled = 3 * writer.elementWidth
        var expected: [UInt8] = [0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        expected += [UInt8](repeating: 0xFF, count: filled)
        expected += [UInt8](repeating: 0, count: size - 16 - filled)
        #expect(writer.toBytes() == expected)
    }
}

@Test
func rowArrayWriterCounts() throws {
    for (count, headerSize) in [(0, 8), (64, 16), (65, 24)] {
        var writer = RowArrayWriter(element: field(.int64), buffer: ByteBuffer())
        try writer.reset(count: count)
        #expect(writer.size == headerSize + count * 8)
        if count == 0 {
            #expect(writer.toBytes() == [UInt8](repeating: 0, count: 8))
            continue
        }
        writer.writeInt64(7, at: 0)
        writer.setNullAt(count - 1)
        let array = try RowArray(element: field(.int64), bytes: writer.toBytes(), base: 0, count: writer.size)
        #expect(array.elementCount == count)
        #expect(array.int64(at: 0) == 7)
        #expect(array.isNull(at: count - 1))
        #expect(!array.isNull(at: count - 2))
    }
}

@Test
func rowMapWriterEmpty() throws {
    let buffer = ByteBuffer()
    var map = RowMapWriter(buffer: buffer)
    map.reset()
    var keys = RowArrayWriter(element: field(.string), buffer: buffer)
    try keys.reset(count: 0)
    map.finishKeys()
    var values = RowArrayWriter(element: field(.int32), buffer: buffer)
    try values.reset(count: 0)
    #expect(
        buffer.copyBytes(start: 0, end: buffer.count)
            == [0x08, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 16))
}

@Test
func rowWriterNullElements() throws {
    let schema = RowSchema([
        RowField(name: "names", dataType: .listOf(.string), nullable: true),
        RowField(name: "points", dataType: .listOf(.struct(pointFields)), nullable: true)
    ])
    var writer = RowWriter(schema: schema)
    writer.reset()

    var names = RowArrayWriter(element: field(.string), buffer: writer.buffer)
    try names.reset(count: 2)
    try names.writeString("a", at: 0)
    names.setNullAt(1)
    try writer.setOffsetAndSize(at: 0, absoluteStart: names.base, size: names.size)

    var points = RowArrayWriter(element: field(.struct(pointFields)), buffer: writer.buffer)
    try points.reset(count: 2)
    let point = writePoint(1, into: writer.buffer)
    try points.setOffsetAndSize(at: 0, absoluteStart: point.start, size: point.size)
    points.setNullAt(1)
    try writer.setOffsetAndSize(at: 1, absoluteStart: points.base, size: points.size)

    let row = try Row(schema: schema, bytes: writer.toBytes())
    let readNames = try #require(try row.array(at: 0))
    #expect(try readNames.string(at: 0) == "a")
    #expect(try readNames.string(at: 1) == nil)
    let readPoints = try #require(try row.array(at: 1))
    #expect(try readPoints.row(at: 0)?.int32(at: 0) == 1)
    #expect(try readPoints.row(at: 1) == nil)
}

@Test
func rowWriterNestedInListInMap() throws {
    let listType = RowDataType.listOf(.struct(pointFields))
    let schema = RowSchema([RowField(name: "m", dataType: .mapOf(.string, listType), nullable: true)])
    var writer = RowWriter(schema: schema)
    writer.reset()
    let buffer = writer.buffer

    var map = RowMapWriter(buffer: buffer)
    map.reset()
    var keys = RowArrayWriter(element: field(.string), buffer: buffer)
    try keys.reset(count: 1)
    try keys.writeString("k", at: 0)
    map.finishKeys()
    var values = RowArrayWriter(element: field(listType), buffer: buffer)
    try values.reset(count: 1)
    var list = RowArrayWriter(element: field(.struct(pointFields)), buffer: buffer)
    try list.reset(count: 2)
    for (index, value) in [Int32(1), 2].enumerated() {
        let point = writePoint(value, into: buffer)
        try list.setOffsetAndSize(at: index, absoluteStart: point.start, size: point.size)
    }
    try values.setOffsetAndSize(at: 0, absoluteStart: list.base, size: list.size)
    try writer.setOffsetAndSize(at: 0, absoluteStart: map.base, size: map.size)

    let readMap = try #require(try Row(schema: schema, bytes: writer.toBytes()).map(at: 0))
    #expect(try readMap.keys.string(at: 0) == "k")
    let readList = try #require(try readMap.values.array(at: 0))
    #expect(readList.elementCount == 2)
    #expect(try readList.row(at: 0)?.int32(at: 0) == 1)
    #expect(try readList.row(at: 1)?.int32(at: 0) == 2)
}

@Test
func rowArrayWriterRejects() throws {
    var writer = RowArrayWriter(element: field(.int64), buffer: ByteBuffer())
    #expect(throws: ForyError.encodingError("array of -1 elements of 8 bytes each cannot be written")) {
        try writer.reset(count: -1)
    }
    let tooMany = (Int(Int32.max) - 15) / 8 + 1
    #expect(throws: ForyError.encodingError("array of \(tooMany) elements of 8 bytes each cannot be written")) {
        try writer.reset(count: tooMany)
    }
    #expect(writer.buffer.count == 0)
}
