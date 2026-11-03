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
