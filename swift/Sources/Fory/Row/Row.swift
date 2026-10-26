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

/// A read-only view of one row's bytes. Each getter reads its field in place and returns nil when the
/// field is null. An index outside `0..<fieldCount` stops the program, like an array subscript.
public struct Row {
    public let fields: [RowField]
    public let sizeInBytes: Int
    // A nested row shares its parent's array, so every position is counted from `base`.
    private let bytes: [UInt8]
    private let base: Int
    private let bitmapWidth: Int

    public init(schema: RowSchema, bytes: [UInt8]) throws {
        try self.init(fields: schema.fields, bytes: bytes, base: 0, count: bytes.count)
    }

    // The bitmap and slots are checked once here, therefore fixed-width getters can read them without
    // checking again.
    init(fields: [RowField], bytes: [UInt8], base: Int, count: Int) throws {
        precondition(base >= 0 && count >= 0 && count <= bytes.count - base, "row range is outside its buffer")
        let bitmapWidth = rowBitmapWidth(fields.count)
        let fixedSize = bitmapWidth + fields.count * 8
        guard count >= fixedSize else {
            throw rowTooShort(count: count, fixedSize: fixedSize)
        }
        self.fields = fields
        self.sizeInBytes = count
        self.bytes = bytes
        self.base = base
        self.bitmapWidth = bitmapWidth
    }

    public var fieldCount: Int {
        fields.count
    }

    public func isNull(at index: Int) -> Bool {
        precondition(index >= 0 && index < fields.count, "field index \(index) is outside 0..<\(fields.count)")
        return bytes.withUnsafeBytes { rowIsBitSet($0, bitmapOffset: base, index: index) }
    }

    public func bool(at index: Int) -> Bool? {
        isNull(at: index) ? nil : loadSlot(UInt8.self, at: index) != 0
    }

    public func int8(at index: Int) -> Int8? {
        isNull(at: index) ? nil : Int8(bitPattern: loadSlot(UInt8.self, at: index))
    }

    public func int16(at index: Int) -> Int16? {
        isNull(at: index) ? nil : Int16(bitPattern: loadSlot(UInt16.self, at: index))
    }

    public func int32(at index: Int) -> Int32? {
        isNull(at: index) ? nil : Int32(bitPattern: loadSlot(UInt32.self, at: index))
    }

    public func int64(at index: Int) -> Int64? {
        isNull(at: index) ? nil : Int64(bitPattern: loadSlot(UInt64.self, at: index))
    }

    public func float32(at index: Int) -> Float? {
        isNull(at: index) ? nil : Float(bitPattern: loadSlot(UInt32.self, at: index))
    }

    public func float64(at index: Int) -> Double? {
        isNull(at: index) ? nil : Double(bitPattern: loadSlot(UInt64.self, at: index))
    }

    public func localDate(at index: Int) -> LocalDate? {
        int32(at: index).map { LocalDate(epochDay: Int64($0)) }
    }

    public func timestamp(at index: Int) -> Date? {
        int64(at: index).map(rowDate(micros:))
    }

    public func duration(at index: Int) -> Duration? {
        int64(at: index).map { .microseconds($0) }
    }

    /// Throws if the stored offset and size point outside this row, or the bytes are not valid UTF-8.
    public func string(at index: Int) throws -> String? {
        guard let range = try valueRange(at: index) else {
            return nil
        }
        let decoded = bytes.withUnsafeBufferPointer { decodeValidatedUTF8(UnsafeBufferPointer(rebasing: $0[range])) }
        guard let decoded else {
            throw rowInvalidUTF8()
        }
        return decoded
    }

    /// Throws if the stored offset and size point outside this row.
    public func binary(at index: Int) throws -> [UInt8]? {
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return Array(bytes[range])
    }

    /// The field must have a struct type.
    public func row(at index: Int) throws -> Row? {
        guard case .struct(let childFields) = fields[index].dataType else {
            preconditionFailure("field \(index) has type \(fields[index].dataType), not a struct")
        }
        guard let range = try valueRange(at: index) else {
            return nil
        }
        return try Row(fields: childFields, bytes: bytes, base: range.lowerBound, count: range.count)
    }

    /// A copy of only this row's bytes, even when it sits inside a larger buffer.
    public func toBytes() -> [UInt8] {
        Array(bytes[base..<(base + sizeInBytes)])
    }

    @inline(__always)
    private func loadSlot<T: FixedWidthInteger>(_: T.Type, at index: Int) -> T {
        bytes.withUnsafeBytes {
            T(littleEndian: $0.loadUnaligned(fromByteOffset: base + bitmapWidth + index * 8, as: T.self))
        }
    }

    // The null bit is checked before the slot is read, because a null field's slot can hold anything.
    private func valueRange(at index: Int) throws -> Range<Int>? {
        if isNull(at: index) {
            return nil
        }
        let ticket = rowDecodeOffsetAndSize(loadSlot(UInt64.self, at: index))
        return try rowCheckedRange(offset: ticket.offset, size: ticket.size, regionBase: base, regionCount: sizeInBytes)
    }
}

@inline(never)
func rowTooShort(count: Int, fixedSize: Int) -> ForyError {
    .invalidData("row of \(count) bytes is shorter than its \(fixedSize)-byte fixed region")
}

@inline(never)
func rowInvalidUTF8() -> ForyError {
    .invalidData("invalid UTF-8 sequence")
}
