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
import Fory

// Row format peer driven by org.apache.fory.format.SwiftCrossLanguageTest. Each case reads the bytes
// Java wrote, checks the value and the exact layout, and overwrites the file with its own encoding
// for Java to read back.

// The models are internal rather than private: a private type prints as
// "(unknown context at $...).Bar" in failure messages, which hides which value differed.

@ForyRow
struct Bar: Equatable {
    var f1: Int32?
    var f2: String?
}

@ForyRow
struct Foo: Equatable {
    var f1: Int32?
    var f2: String?
    var f3: [String?]?
    var f4: [String: Int32?]?
    var f5: Bar?
}

// CrossLanguageTest.A.
@ForyRow
struct StringMap: Equatable {
    var f1: Int32?
    var f2: [String: String]?
}

@ForyRow
struct Blob: Equatable {
    var f1: Data?
    var f2: String?
}

// Data prints only its byte count ("5 bytes"), so a failure message would show the same text for the
// value read and the value expected. Printing the bytes shows which one differs.
extension Blob: CustomStringConvertible {
    var description: String {
        "Blob(f1: \(f1.map { Array($0).description } ?? "nil"), f2: \(f2 ?? "nil"))"
    }
}

private let expectedFoo = Foo(
    f1: 1, f2: "str", f3: ["str1", nil, "str2"], f4: ["k1": 1, "k2": 2, "k3": 3, "k4": 4, "k5": 5, "k6": 6],
    f5: Bar(f1: 1, f2: "str")
)
private let expectedStringMap = StringMap(f1: 1, f2: ["pid": "12345", "ip": "0.0.0.0", "k1": "v1"])
private let expectedBlob = Blob(f1: Data([0, 1, 0xff, 0x7f, 0x80]), f2: "bytes")

private struct PeerFailure: Error, CustomStringConvertible {
    let description: String
}

private func check(_ condition: Bool, _ message: @autoclosure () -> String) throws {
    if !condition {
        throw PeerFailure(description: message())
    }
}

private func present<T>(_ value: T?) throws -> T {
    guard let value else {
        throw PeerFailure(description: "a slot that is not null has no value")
    }
    return value
}

// Writes the row again through the low-level writers, copying map entries in the order they were
// read. A dictionary has no fixed order, so this is the only way to require the exact bytes of a
// row that holds a map.
private func replay(_ bytes: [UInt8], schema: RowSchema) throws -> [UInt8] {
    let row = try Row(schema: schema, bytes: bytes)
    var writer = RowWriter(schema: schema)
    writer.reset()
    for (index, field) in schema.fields.enumerated() {
        try copy(from: row, index, field, to: writer)
    }
    return writer.toBytes()
}

private func copy(from reader: some RowValueReader, _ index: Int, _ field: RowField, to writer: some RowValueWriter) throws {
    if reader.isNull(at: index) {
        writer.setNullAt(index)
        return
    }
    switch field.dataType {
    case .bool: writer.writeBool(try present(reader.bool(at: index)), at: index)
    case .int8: writer.writeInt8(try present(reader.int8(at: index)), at: index)
    case .int16: writer.writeInt16(try present(reader.int16(at: index)), at: index)
    case .int32: writer.writeInt32(try present(reader.int32(at: index)), at: index)
    case .int64: writer.writeInt64(try present(reader.int64(at: index)), at: index)
    case .float32: writer.writeFloat32(try present(reader.float32(at: index)), at: index)
    case .float64: writer.writeFloat64(try present(reader.float64(at: index)), at: index)
    case .date32: try writer.writeLocalDate(present(reader.localDate(at: index)), at: index)
    case .timestamp: try writer.writeTimestamp(present(reader.timestamp(at: index)), at: index)
    case .duration: try writer.writeDuration(present(reader.duration(at: index)), at: index)
    case .string: try writer.writeString(present(reader.string(at: index)), at: index)
    case .binary: try writer.writeBytes(present(reader.binary(at: index)), at: index)
    case .list:
        let array = try copy(present(reader.array(at: index)), to: writer.buffer)
        try writer.setOffsetAndSize(at: index, absoluteStart: array.base, size: array.size)
    case .map:
        let source = try present(reader.map(at: index))
        var map = RowMapWriter(buffer: writer.buffer)
        map.reset()
        _ = try copy(source.keys, to: writer.buffer)
        map.finishKeys()
        _ = try copy(source.values, to: writer.buffer)
        try writer.setOffsetAndSize(at: index, absoluteStart: map.base, size: map.size)
    case .struct(let fields):
        let source = try present(reader.row(at: index))
        var child = RowWriter(schema: RowSchema(fields), buffer: writer.buffer)
        child.reset()
        for (childIndex, childField) in fields.enumerated() {
            try copy(from: source, childIndex, childField, to: child)
        }
        try writer.setOffsetAndSize(at: index, absoluteStart: child.base, size: child.size)
    case .float16, .decimal:
        throw PeerFailure(description: "replay does not support \(field.dataType)")
    }
}

private func copy(_ source: RowArray, to buffer: ByteBuffer) throws -> RowArrayWriter {
    var array = RowArrayWriter(element: source.element, buffer: buffer)
    try array.reset(count: source.elementCount)
    for index in 0..<source.elementCount {
        try copy(from: source, index, source.element, to: array)
    }
    return array
}

private func readFile(_ path: String) throws -> Data {
    try Data(contentsOf: URL(fileURLWithPath: path))
}

private func writeFile(_ path: String, _ data: Data) throws {
    try data.write(to: URL(fileURLWithPath: path))
}

private func testByteArrayCarrier(_ dataFile: String) throws {
    let encoder = RowEncoder<Blob>()
    let data = try readFile(dataFile)
    let decoded = try encoder.decode(data)
    try check(decoded == expectedBlob, "decoded \(decoded), expected \(expectedBlob)")
    let encoded = try encoder.encode(expectedBlob)
    try check(encoded == data, "encoded Blob bytes differ from Java's")
    try writeFile(dataFile, encoded)
}

private func testMapEncoder(_ dataFile: String) throws {
    let encoder = RowEncoder<StringMap>()
    let data = try readFile(dataFile)
    let decoded = try encoder.decode(data)
    try check(decoded == expectedStringMap, "decoded \(decoded), expected \(expectedStringMap)")
    let hash = withUnsafeBytes(of: encoder.schemaHash.littleEndian) { Data($0) }
    try check(data.prefix(8) == hash, "schema hash prefix differs from \(encoder.schemaHash)")
    let rowBytes = Array(data.dropFirst(8))
    try check(try replay(rowBytes, schema: encoder.schema) == rowBytes, "replayed StringMap row differs from Java's")
    try writeFile(dataFile, encoder.encode(expectedStringMap))
}

private func testSerializationWithoutSchema(_ dataFile: String) throws {
    let encoder = RowEncoder<Foo>()
    let data = try readFile(dataFile)
    let decoded = try encoder.fromRow(data)
    try check(decoded == expectedFoo, "decoded \(decoded), expected \(expectedFoo)")
    let rowBytes = Array(data)
    try check(try replay(rowBytes, schema: encoder.schema) == rowBytes, "replayed Foo row differs from Java's")
    try writeFile(dataFile, encoder.toRow(expectedFoo))
}

private func testSerializationWithSchema(_ schemaFile: String, _ dataFile: String) throws {
    let schemaBytes = Array(try readFile(schemaFile))
    let parsed = try RowSchema(bytes: schemaBytes)
    try check(parsed == Foo.rowSchema, "Java schema \(parsed), inferred schema \(Foo.rowSchema)")
    try check(parsed.hash == Foo.rowSchema.hash, "schema hash \(parsed.hash), encoder hash \(Foo.rowSchema.hash)")
    try check(try Foo.rowSchema.toBytes() == schemaBytes, "re-encoded schema bytes differ from Java's")
    try testSerializationWithoutSchema(dataFile)
}

private func run(_ arguments: [String]) throws {
    guard arguments.count >= 3 else {
        throw PeerFailure(description: "usage: ForyRowXlangPeer <case> <file>...")
    }
    switch arguments[1] {
    case "test_byte_array_carrier":
        try testByteArrayCarrier(arguments[2])
    case "test_map_encoder":
        try testMapEncoder(arguments[2])
    case "test_serialization_without_schema":
        try testSerializationWithoutSchema(arguments[2])
    case "test_serialization_with_schema":
        guard arguments.count >= 4 else {
            throw PeerFailure(description: "test_serialization_with_schema needs <schemaFile> <dataFile>")
        }
        try testSerializationWithSchema(arguments[2], arguments[3])
    default:
        throw PeerFailure(description: "unknown test case \"\(arguments[1])\"")
    }
}

do {
    try run(CommandLine.arguments)
} catch {
    FileHandle.standardError.write(Data("row_xlang: \(error)\n".utf8))
    exit(1)
}
