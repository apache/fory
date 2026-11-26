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

/// The write methods ``RowWriter`` and ``RowArrayWriter`` share, so one ``RowCodable`` type can be
/// written as a struct field or as an array element.
public protocol RowValueWriter {
    var buffer: ByteBuffer { get }
    func setNullAt(_ index: Int)
    func writeBool(_ value: Bool, at index: Int)
    func writeInt8(_ value: Int8, at index: Int)
    func writeInt16(_ value: Int16, at index: Int)
    func writeInt32(_ value: Int32, at index: Int)
    func writeInt64(_ value: Int64, at index: Int)
    func writeFloat32(_ value: Float, at index: Int)
    func writeFloat64(_ value: Double, at index: Int)
    func writeLocalDate(_ value: LocalDate, at index: Int) throws
    func writeTimestamp(_ value: Date, at index: Int) throws
    func writeDuration(_ value: Duration, at index: Int) throws
    func writeString(_ value: String, at index: Int) throws
    func writeBytes(_ value: some ContiguousBytes, at index: Int) throws
    func setOffsetAndSize(at index: Int, absoluteStart: Int, size: Int) throws
}

/// The getters ``Row`` and ``RowArray`` share, so one ``RowCodable`` type can be read from a struct
/// field or from an array element.
public protocol RowValueReader {
    func isNull(at index: Int) -> Bool
    func bool(at index: Int) -> Bool?
    func int8(at index: Int) -> Int8?
    func int16(at index: Int) -> Int16?
    func int32(at index: Int) -> Int32?
    func int64(at index: Int) -> Int64?
    func float32(at index: Int) -> Float?
    func float64(at index: Int) -> Double?
    func localDate(at index: Int) -> LocalDate?
    func timestamp(at index: Int) -> Date?
    func duration(at index: Int) -> Duration?
    func string(at index: Int) throws -> String?
    func binary(at index: Int) throws -> [UInt8]?
    func row(at index: Int) throws -> Row?
    func array(at index: Int) throws -> RowArray?
    func map(at index: Int) throws -> RowMap?
}

extension RowWriter: RowValueWriter {}
extension RowArrayWriter: RowValueWriter {}
extension Row: RowValueReader {}
extension RowArray: RowValueReader {}

/// How one Swift type is stored in a row field or an array element.
public protocol RowCodable {
    static var rowDataType: RowDataType { get }
    /// Whether a field of this type is nullable in a schema built from Swift types. A type that
    /// cannot hold nil may still be nullable, as `String` is; reading a null into it throws.
    static var rowNullable: Bool { get }
    /// The same as `rowDataType.byteWidth`, kept as a constant so writing never builds the data type.
    static var rowFixedWidth: Int? { get }
    static func writeRow<W: RowValueWriter>(_ value: Self, to writer: W, at index: Int) throws
    /// Throws when the slot is null and `Self` cannot hold nil.
    static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Self
}

extension RowWriter {
    @inlinable
    public func write<T: RowCodable>(_ value: T, at index: Int) throws {
        try T.writeRow(value, to: self, at: index)
    }
}

extension Row {
    /// Reads field `index` as `T`. Read as an optional type to get nil for a null field.
    @inlinable
    public func read<T: RowCodable>(_ type: T.Type = T.self, at index: Int) throws -> T {
        try T.readRow(from: self, at: index)
    }
}

extension RowField {
    /// The field a Swift type gets in a schema built from Swift types.
    public static func inferred<T: RowCodable>(_ name: String, _ type: T.Type) -> RowField {
        RowField(name: name, dataType: T.rowDataType, nullable: T.rowNullable)
    }
}

extension Bool: RowCodable {
    public static var rowDataType: RowDataType { .bool }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 1 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Bool, to writer: W, at index: Int) throws {
        writer.writeBool(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Bool {
        try rowNonNull(reader.bool(at: index))
    }
}

extension Int8: RowCodable {
    public static var rowDataType: RowDataType { .int8 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 1 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Int8, to writer: W, at index: Int) throws {
        writer.writeInt8(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Int8 {
        try rowNonNull(reader.int8(at: index))
    }
}

extension Int16: RowCodable {
    public static var rowDataType: RowDataType { .int16 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 2 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Int16, to writer: W, at index: Int) throws {
        writer.writeInt16(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Int16 {
        try rowNonNull(reader.int16(at: index))
    }
}

extension Int32: RowCodable {
    public static var rowDataType: RowDataType { .int32 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 4 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Int32, to writer: W, at index: Int) throws {
        writer.writeInt32(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Int32 {
        try rowNonNull(reader.int32(at: index))
    }
}

extension Int64: RowCodable {
    public static var rowDataType: RowDataType { .int64 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 8 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Int64, to writer: W, at index: Int) throws {
        writer.writeInt64(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Int64 {
        try rowNonNull(reader.int64(at: index))
    }
}

// Int is stored as int64 on every platform. Where Int is 32 bits, a larger stored value throws.
extension Int: RowCodable {
    public static var rowDataType: RowDataType { .int64 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 8 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Int, to writer: W, at index: Int) throws {
        writer.writeInt64(Int64(value), at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Int {
        let value = try rowNonNull(reader.int64(at: index))
        guard let narrowed = Int(exactly: value) else {
            throw rowIntOutOfRange(value)
        }
        return narrowed
    }
}

extension Float: RowCodable {
    public static var rowDataType: RowDataType { .float32 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 4 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Float, to writer: W, at index: Int) throws {
        writer.writeFloat32(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Float {
        try rowNonNull(reader.float32(at: index))
    }
}

extension Double: RowCodable {
    public static var rowDataType: RowDataType { .float64 }
    public static var rowNullable: Bool { false }
    public static var rowFixedWidth: Int? { 8 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Double, to writer: W, at index: Int) throws {
        writer.writeFloat64(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Double {
        try rowNonNull(reader.float64(at: index))
    }
}

extension String: RowCodable {
    public static var rowDataType: RowDataType { .string }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: String, to writer: W, at index: Int) throws {
        try writer.writeString(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> String {
        try rowNonNull(reader.string(at: index))
    }
}

extension Date: RowCodable {
    public static var rowDataType: RowDataType { .timestamp }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { 8 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Date, to writer: W, at index: Int) throws {
        try writer.writeTimestamp(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Date {
        try rowNonNull(reader.timestamp(at: index))
    }
}

extension LocalDate: RowCodable {
    public static var rowDataType: RowDataType { .date32 }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { 4 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: LocalDate, to writer: W, at index: Int) throws {
        try writer.writeLocalDate(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> LocalDate {
        try rowNonNull(reader.localDate(at: index))
    }
}

extension Duration: RowCodable {
    public static var rowDataType: RowDataType { .duration }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { 8 }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Duration, to writer: W, at index: Int) throws {
        try writer.writeDuration(value, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Duration {
        try rowNonNull(reader.duration(at: index))
    }
}

// An optional uses its wrapped type's slot and width, and stores nil as a set null bit.
extension Optional: RowCodable where Wrapped: RowCodable {
    public static var rowDataType: RowDataType { Wrapped.rowDataType }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { Wrapped.rowFixedWidth }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Wrapped?, to writer: W, at index: Int) throws {
        guard let value else {
            writer.setNullAt(index)
            return
        }
        try Wrapped.writeRow(value, to: writer, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Wrapped? {
        try reader.isNull(at: index) ? nil : Wrapped.readRow(from: reader, at: index)
    }
}

extension Array: RowCodable where Element: RowCodable {
    public static var rowDataType: RowDataType { .list(.inferred("item", Element.self)) }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: [Element], to writer: W, at index: Int) throws {
        let array = try rowAppendArray(value, to: writer.buffer)
        try writer.setOffsetAndSize(at: index, absoluteStart: array.base, size: array.size)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> [Element] {
        guard let array = try reader.array(at: index) else {
            throw rowNullValue(Self.self)
        }
        var elements: [Element] = []
        elements.reserveCapacity(array.elementCount)
        for elementIndex in 0..<array.elementCount {
            elements.append(try Element.readRow(from: array, at: elementIndex))
        }
        return elements
    }
}

// A set is stored as a list; its elements come out in no particular order.
extension Set: RowCodable where Element: RowCodable {
    public static var rowDataType: RowDataType { .list(.inferred("item", Element.self)) }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Set<Element>, to writer: W, at index: Int) throws {
        let array = try rowAppendArray(value, to: writer.buffer)
        try writer.setOffsetAndSize(at: index, absoluteStart: array.base, size: array.size)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Set<Element> {
        guard let array = try reader.array(at: index) else {
            throw rowNullValue(Self.self)
        }
        var elements = Set<Element>(minimumCapacity: array.elementCount)
        for elementIndex in 0..<array.elementCount {
            elements.insert(try Element.readRow(from: array, at: elementIndex))
        }
        return elements
    }
}

// Map keys are never null and map values always may be, whatever the Swift types say.
extension Dictionary: RowCodable where Key: RowCodable, Value: RowCodable {
    public static var rowDataType: RowDataType { .mapOf(Key.rowDataType, Value.rowDataType) }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    // `keys` and `values` of one dictionary come out in the same order, so entry i of the keys
    // array pairs with entry i of the values array.
    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: [Key: Value], to writer: W, at index: Int) throws {
        var map = RowMapWriter(buffer: writer.buffer)
        map.reset()
        _ = try rowAppendArray(value.keys, to: writer.buffer)
        map.finishKeys()
        _ = try rowAppendArray(value.values, to: writer.buffer)
        try writer.setOffsetAndSize(at: index, absoluteStart: map.base, size: map.size)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> [Key: Value] {
        guard let map = try reader.map(at: index) else {
            throw rowNullValue(Self.self)
        }
        var entries = [Key: Value](minimumCapacity: map.count)
        for entryIndex in 0..<map.count {
            let key = try Key.readRow(from: map.keys, at: entryIndex)
            entries[key] = try Value.readRow(from: map.values, at: entryIndex)
        }
        return entries
    }
}

// Data is a list of int8 elements that are never null, the same as [Int8], but its bytes are copied
// in one go instead of element by element.
extension Data: RowCodable {
    public static var rowDataType: RowDataType { .list(RowField(name: "item", dataType: .int8, nullable: false)) }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    public static func writeRow<W: RowValueWriter>(_ value: Data, to writer: W, at index: Int) throws {
        var array = RowArrayWriter(elementWidth: 1, buffer: writer.buffer)
        try array.reset(count: value.count)
        array.writeInt8Elements(value)
        try writer.setOffsetAndSize(at: index, absoluteStart: array.base, size: array.size)
    }

    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Data {
        guard let array = try reader.array(at: index) else {
            throw rowNullValue(Data.self)
        }
        guard let bytes = array.int8Elements() else {
            throw rowNullValue(Int8.self)
        }
        return bytes
    }
}

/// Stores an enum as the string of its raw value. Opt in with an empty `extension MyEnum: RowCodable {}`.
extension RowCodable where Self: RawRepresentable, Self.RawValue == String {
    public static var rowDataType: RowDataType { .string }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Self, to writer: W, at index: Int) throws {
        try writer.writeString(value.rawValue, at: index)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Self {
        guard let name = try reader.string(at: index) else {
            throw rowNullValue(Self.self)
        }
        guard let value = Self(rawValue: name) else {
            throw rowUnknownCase(name, of: Self.self)
        }
        return value
    }
}

/// A struct stored as its own row. `@ForyRow` adopts it.
public protocol RowStruct: RowCodable {
    static var rowSchema: RowSchema { get }
    static func writeRowFields(_ value: Self, to writer: RowWriter) throws
    static func readRowFields(from row: Row) throws -> Self
}

// A struct field holds a complete child row written after the parent's slots.
extension RowStruct {
    public static var rowDataType: RowDataType { .struct(rowSchema.fields) }
    public static var rowNullable: Bool { true }
    public static var rowFixedWidth: Int? { nil }

    @inlinable
    public static func writeRow<W: RowValueWriter>(_ value: Self, to writer: W, at index: Int) throws {
        var child = RowWriter(schema: rowSchema, buffer: writer.buffer)
        child.reset()
        try writeRowFields(value, to: child)
        try writer.setOffsetAndSize(at: index, absoluteStart: child.base, size: child.size)
    }

    @inlinable
    public static func readRow<R: RowValueReader>(from reader: R, at index: Int) throws -> Self {
        guard let row = try reader.row(at: index) else {
            throw rowNullValue(Self.self)
        }
        return try readRowFields(from: row)
    }
}

/// Converts a ``RowStruct`` to and from row bytes. It reuses one buffer, so use an encoder from one
/// thread at a time.
public final class RowEncoder<T: RowStruct> {
    public let schema: RowSchema
    public let schemaHash: Int64
    private let buffer = ByteBuffer()

    public init() {
        schema = T.rowSchema
        schemaHash = schema.hash
    }

    public func toRow(_ value: T) throws -> Data {
        buffer.clear()
        try writeRow(value)
        return buffer.toData()
    }

    public func fromRow(_ data: Data) throws -> T {
        try T.readRowFields(from: Row(schema: schema, bytes: Array(data)))
    }

    /// The schema hash as 8 little-endian bytes, then the row.
    public func encode(_ value: T) throws -> Data {
        buffer.clear()
        buffer.writeInt64(schemaHash)
        try writeRow(value)
        return buffer.toData()
    }

    /// Throws if the data is shorter than the hash or was written with a different schema.
    public func decode(_ data: Data) throws -> T {
        guard data.count >= 8 else {
            throw rowEncodedTooShort(data.count)
        }
        let bytes = Array(data)
        let hash = Int64(littleEndian: bytes.withUnsafeBytes { $0.loadUnaligned(as: Int64.self) })
        guard hash == schemaHash else {
            throw rowSchemaHashMismatch(found: hash, expected: schemaHash)
        }
        return try T.readRowFields(from: Row(fields: schema.fields, bytes: bytes, base: 8, count: bytes.count - 8))
    }

    private func writeRow(_ value: T) throws {
        var writer = RowWriter(schema: schema, buffer: buffer)
        writer.reset()
        try T.writeRowFields(value, to: writer)
    }
}

// Appends `elements` as an array at the end of the buffer. The returned writer's base and size are
// what the parent slot points at.
@inlinable
func rowAppendArray<C: Collection>(_ elements: C, to buffer: ByteBuffer) throws -> RowArrayWriter
where C.Element: RowCodable {
    var array = RowArrayWriter(elementWidth: C.Element.rowFixedWidth ?? 8, buffer: buffer)
    try array.reset(count: elements.count)
    for (elementIndex, element) in elements.enumerated() {
        try C.Element.writeRow(element, to: array, at: elementIndex)
    }
    return array
}

@inlinable
@inline(__always)
func rowNonNull<T>(_ value: T?) throws -> T {
    guard let value else {
        throw rowNullValue(T.self)
    }
    return value
}

@usableFromInline
@inline(never)
func rowNullValue(_ type: Any.Type) -> ForyError {
    .invalidData("null row value cannot be read into non-optional \(type)")
}

@usableFromInline
@inline(never)
func rowIntOutOfRange(_ value: Int64) -> ForyError {
    .invalidData("int64 \(value) does not fit in Int")
}

@usableFromInline
@inline(never)
func rowUnknownCase(_ name: String, of type: Any.Type) -> ForyError {
    .invalidData("unknown case \"\(name)\" for \(type)")
}

@inline(never)
private func rowEncodedTooShort(_ count: Int) -> ForyError {
    .invalidData("encoded row of \(count) bytes is shorter than its 8-byte schema hash")
}

@inline(never)
private func rowSchemaHashMismatch(found: Int64, expected: Int64) -> ForyError {
    .invalidData("row schema hash \(found) does not match the expected \(expected)")
}
