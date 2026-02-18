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
    public static var staticTypeId: ForyTypeId { .bool }

    public static func defaultValue() -> Bool { false }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeUInt8(self ? 1 : 0)
    }

    public static func readData(_ context: ReadContext) throws -> Bool {
        try context.reader.readUInt8() != 0
    }
}

extension Int8: Serializer {
    public static var staticTypeId: ForyTypeId { .int8 }

    public static func defaultValue() -> Int8 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeInt8(self)
    }

    public static func readData(_ context: ReadContext) throws -> Int8 {
        try context.reader.readInt8()
    }
}

extension Int16: Serializer {
    public static var staticTypeId: ForyTypeId { .int16 }

    public static func defaultValue() -> Int16 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeInt16(self)
    }

    public static func readData(_ context: ReadContext) throws -> Int16 {
        try context.reader.readInt16()
    }
}

extension Int32: Serializer {
    public static var staticTypeId: ForyTypeId { .varint32 }

    public static func defaultValue() -> Int32 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarInt32(self)
    }

    public static func readData(_ context: ReadContext) throws -> Int32 {
        try context.reader.readVarInt32()
    }
}

extension Int64: Serializer {
    public static var staticTypeId: ForyTypeId { .varint64 }

    public static func defaultValue() -> Int64 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarInt64(self)
    }

    public static func readData(_ context: ReadContext) throws -> Int64 {
        try context.reader.readVarInt64()
    }
}

extension UInt8: Serializer {
    public static var staticTypeId: ForyTypeId { .uint8 }

    public static func defaultValue() -> UInt8 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeUInt8(self)
    }

    public static func readData(_ context: ReadContext) throws -> UInt8 {
        try context.reader.readUInt8()
    }
}

extension UInt16: Serializer {
    public static var staticTypeId: ForyTypeId { .uint16 }

    public static func defaultValue() -> UInt16 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeUInt16(self)
    }

    public static func readData(_ context: ReadContext) throws -> UInt16 {
        try context.reader.readUInt16()
    }
}

extension UInt32: Serializer {
    public static var staticTypeId: ForyTypeId { .varUInt32 }

    public static func defaultValue() -> UInt32 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarUInt32(self)
    }

    public static func readData(_ context: ReadContext) throws -> UInt32 {
        try context.reader.readVarUInt32()
    }
}

extension UInt64: Serializer {
    public static var staticTypeId: ForyTypeId { .varUInt64 }

    public static func defaultValue() -> UInt64 { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarUInt64(self)
    }

    public static func readData(_ context: ReadContext) throws -> UInt64 {
        try context.reader.readVarUInt64()
    }
}

#if arch(arm64) || arch(x86_64)
extension Int: Serializer {
    public static var staticTypeId: ForyTypeId { .varint64 }

    public static func defaultValue() -> Int { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarInt64(Int64(self))
    }

    public static func readData(_ context: ReadContext) throws -> Int {
        Int(try context.reader.readVarInt64())
    }
}

extension UInt: Serializer {
    public static var staticTypeId: ForyTypeId { .varUInt64 }

    public static func defaultValue() -> UInt { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarUInt64(UInt64(self))
    }

    public static func readData(_ context: ReadContext) throws -> UInt {
        UInt(try context.reader.readVarUInt64())
    }
}
#endif

extension Float: Serializer {
    public static var staticTypeId: ForyTypeId { .float32 }

    public static func defaultValue() -> Float { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeFloat32(self)
    }

    public static func readData(_ context: ReadContext) throws -> Float {
        try context.reader.readFloat32()
    }
}

extension Double: Serializer {
    public static var staticTypeId: ForyTypeId { .float64 }

    public static func defaultValue() -> Double { 0 }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeFloat64(self)
    }

    public static func readData(_ context: ReadContext) throws -> Double {
        try context.reader.readFloat64()
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
    public static var staticTypeId: ForyTypeId { .string }

    public static func defaultValue() -> String { "" }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        let utf8Bytes = Array(self.utf8)
        let header = (UInt64(utf8Bytes.count) << 2) | StringEncoding.utf8.rawValue
        context.writer.writeVarUInt36Small(header)
        context.writer.writeBytes(utf8Bytes)
    }

    public static func readData(_ context: ReadContext) throws -> String {
        let header = try context.reader.readVarUInt36Small()
        let encoding = header & 0x03
        let byteLength = Int(header >> 2)
        let bytes = try context.reader.readBytes(count: byteLength)

        switch encoding {
        case StringEncoding.utf8.rawValue:
            return String(decoding: bytes, as: UTF8.self)
        case StringEncoding.latin1.rawValue:
            return decodeLatin1(bytes)
        case StringEncoding.utf16.rawValue:
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
    public static var staticTypeId: ForyTypeId { .binary }

    public static func defaultValue() -> Data { Data() }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.writer.writeVarUInt32(UInt32(self.count))
        context.writer.writeData(self)
    }

    public static func readData(_ context: ReadContext) throws -> Data {
        let length = try context.reader.readVarUInt32()
        let bytes = try context.reader.readBytes(count: Int(length))
        return Data(bytes)
    }
}
