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

extension Bool: Serializer {
    public static var staticTypeId: TypeId { .bool }

    public static func defaultValue(_ context: ReadContext) throws -> Bool { false }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeUInt8(value ? 1 : 0)
    }

    public static func readData(_ context: ReadContext) throws -> Bool {
        try context.buffer.readUInt8() != 0
    }
}

extension Int8: Serializer {
    public static var staticTypeId: TypeId { .int8 }

    public static func defaultValue(_ context: ReadContext) throws -> Int8 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeInt8(value)
    }

    public static func readData(_ context: ReadContext) throws -> Int8 {
        try context.buffer.readInt8()
    }
}

extension Int16: Serializer {
    public static var staticTypeId: TypeId { .int16 }

    public static func defaultValue(_ context: ReadContext) throws -> Int16 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeInt16(value)
    }

    public static func readData(_ context: ReadContext) throws -> Int16 {
        try context.buffer.readInt16()
    }
}

extension Int32: Serializer {
    public static var staticTypeId: TypeId { .varint32 }

    public static func defaultValue(_ context: ReadContext) throws -> Int32 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeVarInt32(value)
    }

    public static func readData(_ context: ReadContext) throws -> Int32 {
        try context.buffer.readVarInt32()
    }
}

extension Int64: Serializer {
    public static var staticTypeId: TypeId { .varint64 }

    public static func defaultValue(_ context: ReadContext) throws -> Int64 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeVarInt64(value)
    }

    public static func readData(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readVarInt64()
    }
}

extension UInt8: Serializer {
    public static var staticTypeId: TypeId { .uint8 }

    public static func defaultValue(_ context: ReadContext) throws -> UInt8 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeUInt8(value)
    }

    public static func readData(_ context: ReadContext) throws -> UInt8 {
        try context.buffer.readUInt8()
    }
}

extension UInt16: Serializer {
    public static var staticTypeId: TypeId { .uint16 }

    public static func defaultValue(_ context: ReadContext) throws -> UInt16 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeUInt16(value)
    }

    public static func readData(_ context: ReadContext) throws -> UInt16 {
        try context.buffer.readUInt16()
    }
}

extension UInt32: Serializer {
    public static var staticTypeId: TypeId { .varUInt32 }

    public static func defaultValue(_ context: ReadContext) throws -> UInt32 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeVarUInt32(value)
    }

    public static func readData(_ context: ReadContext) throws -> UInt32 {
        try context.buffer.readVarUInt32()
    }
}

extension UInt64: Serializer {
    public static var staticTypeId: TypeId { .varUInt64 }

    public static func defaultValue(_ context: ReadContext) throws -> UInt64 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeVarUInt64(value)
    }

    public static func readData(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readVarUInt64()
    }
}

#if arch(arm64) || arch(x86_64)
    extension Int: Serializer {
        public static var staticTypeId: TypeId { .varint64 }

        public static func defaultValue(_ context: ReadContext) throws -> Int { 0 }

        public static func writeTypeInfo(_ context: WriteContext) throws {
            context.writeStaticTypeInfo(staticTypeId)
        }

        public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
            try context.readStaticTypeInfo(staticTypeId)
        }

        public static func writeData(_ value: Self, _ context: WriteContext) throws {
            context.buffer.writeVarInt64(Int64(value))
        }

        public static func readData(_ context: ReadContext) throws -> Int {
            Int(try context.buffer.readVarInt64())
        }
    }

    extension UInt: Serializer {
        public static var staticTypeId: TypeId { .varUInt64 }

        public static func defaultValue(_ context: ReadContext) throws -> UInt { 0 }

        public static func writeTypeInfo(_ context: WriteContext) throws {
            context.writeStaticTypeInfo(staticTypeId)
        }

        public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
            try context.readStaticTypeInfo(staticTypeId)
        }

        public static func writeData(_ value: Self, _ context: WriteContext) throws {
            context.buffer.writeVarUInt64(UInt64(value))
        }

        public static func readData(_ context: ReadContext) throws -> UInt {
            UInt(try context.buffer.readVarUInt64())
        }
    }
#endif

extension Float: Serializer {
    public static var staticTypeId: TypeId { .float32 }

    public static func defaultValue(_ context: ReadContext) throws -> Float { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeFloat32(value)
    }

    public static func readData(_ context: ReadContext) throws -> Float {
        try context.buffer.readFloat32()
    }
}

extension Double: Serializer {
    public static var staticTypeId: TypeId { .float64 }

    public static func defaultValue(_ context: ReadContext) throws -> Double { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeFloat64(value)
    }

    public static func readData(_ context: ReadContext) throws -> Double {
        try context.buffer.readFloat64()
    }
}

public struct BFloat16: Serializer, Equatable, Hashable, Sendable {
    public var rawValue: UInt16

    public init(rawValue: UInt16 = 0) {
        self.rawValue = rawValue
    }

    public static func defaultValue(_ context: ReadContext) throws -> BFloat16 { .init() }
    public static var staticTypeId: TypeId { .bfloat16 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeUInt16(value.rawValue)
    }

    public static func readData(_ context: ReadContext) throws -> BFloat16 {
        .init(rawValue: try context.buffer.readUInt16())
    }
}

extension Float16: Serializer {
    public static var staticTypeId: TypeId { .float16 }

    public static func defaultValue(_ context: ReadContext) throws -> Float16 { 0 }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeUInt16(value.bitPattern)
    }

    public static func readData(_ context: ReadContext) throws -> Float16 {
        Float16(bitPattern: try context.buffer.readUInt16())
    }
}

private enum StringEncoding: UInt64 {
    case latin1 = 0
    case utf16 = 1
    case utf8 = 2
}

private func decodeLatin1(_ bytes: [UInt8]) -> String {
    var scalarView = String.UnicodeScalarView()
    scalarView.reserveCapacity(bytes.count)
    for byte in bytes {
        scalarView.append(UnicodeScalar(UInt32(byte))!)
    }
    return String(scalarView)
}

extension String: Serializer {
    public static var staticTypeId: TypeId { .string }

    public static func defaultValue(_ context: ReadContext) throws -> String { "" }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        try context.readStaticTypeInfo(staticTypeId)
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        let utf8Bytes = value.utf8
        let header = (UInt64(utf8Bytes.count) << 2) | StringEncoding.utf8.rawValue
        if utf8Bytes.withContiguousStorageIfAvailable({ contiguousBytes in
            let totalBytes = UnsafeUtil.varUInt64Size(header) + contiguousBytes.count
            UnsafeUtil.writeRegion(buffer: context.buffer, exactCount: totalBytes) { base in
                var index = UnsafeUtil.writeVarUInt64(header, to: base, index: 0)
                index = UnsafeUtil.copyBytes(contiguousBytes, to: base, index: index)
                assert(index == totalBytes)
            }
            return true
        }) != nil {
            return
        }
        context.buffer.writeVarUInt36Small(header)
        context.buffer.writeBytes(utf8Bytes)
    }

    public static func readData(_ context: ReadContext) throws -> String {
        let header = try context.buffer.readVarUInt36Small()
        let encoding = header & 0x03
        let byteLength = Int(header >> 2)

        switch encoding {
        case StringEncoding.utf8.rawValue:
            return try context.buffer.readUTF8String(count: byteLength)
        case StringEncoding.latin1.rawValue:
            let bytes = try context.buffer.readBytes(count: byteLength)
            return decodeLatin1(bytes)
        case StringEncoding.utf16.rawValue:
            let bytes = try context.buffer.readBytes(count: byteLength)
            if (byteLength & 1) != 0 {
                throw ForyError.encodingError("utf16 byte length is not even")
            }
            var units: [UInt16] = []
            units.reserveCapacity(byteLength / 2)
            var index = 0
            while index < bytes.count {
                let lo = UInt16(bytes[index])
                let hi = UInt16(bytes[index + 1]) << 8
                units.append(lo | hi)
                index += 2
            }
            return String(decoding: units, as: UTF16.self)
        default:
            throw ForyError.encodingError("unsupported string encoding \(encoding)")
        }
    }
}

extension Data: Serializer {
    public static var staticTypeId: TypeId { .binary }

    public static func defaultValue(_ context: ReadContext) throws -> Data { Data() }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        context.writeStaticTypeInfo(staticTypeId)
    }

    public static func readTypeInfo(_ context: ReadContext) throws -> TypeInfo? {
        let rawTypeID = try context.buffer.readVarUInt32()
        guard let typeID = TypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown type id \(rawTypeID)")
        }
        if typeID != .binary && typeID != .uint8Array {
            throw ForyError.typeMismatch(expected: TypeId.binary.rawValue, actual: rawTypeID)
        }
        return nil
    }

    public static func writeData(_ value: Self, _ context: WriteContext) throws {
        context.buffer.writeVarUInt32(UInt32(value.count))
        context.buffer.writeData(value)
    }

    public static func readData(_ context: ReadContext) throws -> Data {
        let length = try context.buffer.readVarUInt32()
        let byteLength = Int(length)
        try context.ensureRemainingBytes(byteLength, label: "binary")
        let bytes = try context.buffer.readBytes(count: byteLength)
        return Data(bytes)
    }
}
