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

private enum Color: String, RowCodable {
    case red
    case green
}

private struct Point: RowStruct, Equatable {
    var number: Int32
    var label: String?

    static let rowSchema = RowSchema([.inferred("label", String?.self), .inferred("number", Int32.self)])

    static func writeRowFields(_ value: Point, to writer: RowWriter) throws {
        try writer.write(value.label, at: 0)
        try writer.write(value.number, at: 1)
    }

    static func readRowFields(from row: Row) throws -> Point {
        try Point(number: row.read(at: 1), label: row.read(at: 0))
    }
}

private struct Bag: RowStruct, Equatable {
    var names: [String?]
    var ids: Set<Int32>
    var scores: [String: Int32?]
    var blob: Data
    var points: [Point]
    var origin: Point?
    var color: Color

    static let rowSchema = RowSchema([
        .inferred("blob", Data.self), .inferred("color", Color.self), .inferred("ids", Set<Int32>.self),
        .inferred("names", [String?].self), .inferred("origin", Point?.self), .inferred("points", [Point].self),
        .inferred("scores", [String: Int32?].self)
    ])

    static func writeRowFields(_ value: Bag, to writer: RowWriter) throws {
        try writer.write(value.blob, at: 0)
        try writer.write(value.color, at: 1)
        try writer.write(value.ids, at: 2)
        try writer.write(value.names, at: 3)
        try writer.write(value.origin, at: 4)
        try writer.write(value.points, at: 5)
        try writer.write(value.scores, at: 6)
    }

    static func readRowFields(from row: Row) throws -> Bag {
        try Bag(
            names: row.read(at: 3), ids: row.read(at: 2), scores: row.read(at: 6), blob: row.read(at: 0),
            points: row.read(at: 5), origin: row.read(at: 4), color: row.read(at: 1)
        )
    }
}

// One field of type T, to compare the bytes two Swift types produce for the same row type.
private struct Single<T: RowCodable>: RowStruct {
    var value: T

    static var rowSchema: RowSchema { RowSchema([.inferred("value", T.self)]) }

    static func writeRowFields(_ value: Single, to writer: RowWriter) throws {
        try writer.write(value.value, at: 0)
    }

    static func readRowFields(from row: Row) throws -> Single {
        try Single(value: row.read(at: 0))
    }
}

private let fullBag = Bag(
    names: ["ab", nil, "xyz"], ids: [3, 1, 2], scores: ["k1": 1, "k2": nil], blob: Data([0, 1, 0xff, 0x7f, 0x80]),
    points: [Point(number: 1, label: "a"), Point(number: 2, label: nil)], origin: Point(number: 5, label: "o"), color: .green
)

private let emptyBag = Bag(
    names: [], ids: [], scores: [:], blob: Data(), points: [], origin: nil, color: .red
)

@Test
func rowEncoderFraming() throws {
    let encoder = RowEncoder<Bag>()
    #expect(encoder.schemaHash == Bag.rowSchema.hash)
    let encoded = try encoder.encode(fullBag)
    let hash = withUnsafeBytes(of: encoder.schemaHash.littleEndian) { Data($0) }
    #expect(encoded.prefix(8) == hash)
    #expect(encoded.dropFirst(8) == (try encoder.toRow(fullBag)))
    #expect(try encoder.decode(encoded) == fullBag)

    #expect(throws: ForyError.invalidData("encoded row of 7 bytes is shorter than its 8-byte schema hash")) {
        try encoder.decode(encoded.prefix(7))
    }
    var flipped = encoded
    flipped[0] ^= 0x01
    let found = encoder.schemaHash ^ 0x01
    #expect(throws: ForyError.invalidData("row schema hash \(found) does not match the expected \(encoder.schemaHash)")) {
        try encoder.decode(flipped)
    }
}

@Test
func rowEncoderCollections() throws {
    let encoder = RowEncoder<Bag>()
    #expect(try encoder.fromRow(encoder.toRow(fullBag)) == fullBag)
    #expect(try encoder.fromRow(encoder.toRow(emptyBag)) == emptyBag)

    // An empty map is its 8-byte keys size, then an empty keys array and an empty values array.
    // Reading the map slot as binary gives those raw bytes.
    let emptyMap = try RowEncoder<Single<[String: Int32]>>().toRow(Single(value: [:]))
    let mapBytes = try Row(schema: Single<[String: Int32]>.rowSchema, bytes: Array(emptyMap)).binary(at: 0)
    #expect(mapBytes == [0x08, 0, 0, 0, 0, 0, 0, 0] + [UInt8](repeating: 0, count: 16))

    // Data copies its bytes in one go but must match an element by element [Int8] list.
    let bytes = Data([0, 1, 0xff, 0x7f, 0x80])
    let int8s = bytes.map { Int8(bitPattern: $0) }
    #expect(Single<Data>.rowSchema == Single<[Int8]>.rowSchema)
    #expect(try RowEncoder<Single<Data>>().toRow(Single(value: bytes)) == RowEncoder<Single<[Int8]>>().toRow(Single(value: int8s)))
}

@Test
func rowEncoderDataNullElement() throws {
    let schema = Single<Data>.rowSchema
    var writer = RowWriter(schema: schema)
    writer.reset()
    var array = RowArrayWriter(element: RowField(name: "item", dataType: .int8, nullable: false), buffer: writer.buffer)
    try array.reset(count: 2)
    array.writeInt8(1, at: 0)
    array.setNullAt(1)
    try writer.setOffsetAndSize(at: 0, absoluteStart: array.base, size: array.size)

    #expect(throws: ForyError.invalidData("null row value cannot be read into non-optional Int8")) {
        try RowEncoder<Single<Data>>().fromRow(Data(writer.toBytes()))
    }
}

@Test
func rowEncoderEnum() throws {
    let encoder = RowEncoder<Single<Color>>()
    #expect(encoder.schema.fields[0].dataType == .string)
    #expect(try encoder.fromRow(encoder.toRow(Single(value: .green))).value == .green)

    let unknown = try RowEncoder<Single<String>>().toRow(Single(value: "blue"))
    #expect(throws: ForyError.invalidData("unknown case \"blue\" for Color")) {
        try encoder.fromRow(unknown)
    }
}

@Test
func rowEncoderDeterministic() throws {
    let encoder = RowEncoder<Bag>()
    #expect(try encoder.toRow(fullBag) == encoder.toRow(fullBag))
}
