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

    public static func foryDefault() -> Bool { false }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeUInt8(self ? 1 : 0)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Bool {
        try context.buffer.readUInt8() != 0
    }
}

extension Int8: Serializer {
    public static var staticTypeId: TypeId { .int8 }

    public static func foryDefault() -> Int8 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeInt8(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Int8 {
        try context.buffer.readInt8()
    }
}

extension Int16: Serializer {
    public static var staticTypeId: TypeId { .int16 }

    public static func foryDefault() -> Int16 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeInt16(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Int16 {
        try context.buffer.readInt16()
    }
}

extension Int32: Serializer {
    public static var staticTypeId: TypeId { .varint32 }

    public static func foryDefault() -> Int32 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarInt32(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Int32 {
        try context.buffer.readVarInt32()
    }
}

extension Int64: Serializer {
    public static var staticTypeId: TypeId { .varint64 }

    public static func foryDefault() -> Int64 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarInt64(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Int64 {
        try context.buffer.readVarInt64()
    }
}

extension UInt8: Serializer {
    public static var staticTypeId: TypeId { .uint8 }

    public static func foryDefault() -> UInt8 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeUInt8(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> UInt8 {
        try context.buffer.readUInt8()
    }
}

extension UInt16: Serializer {
    public static var staticTypeId: TypeId { .uint16 }

    public static func foryDefault() -> UInt16 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeUInt16(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> UInt16 {
        try context.buffer.readUInt16()
    }
}

extension UInt32: Serializer {
    public static var staticTypeId: TypeId { .varUInt32 }

    public static func foryDefault() -> UInt32 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarUInt32(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> UInt32 {
        try context.buffer.readVarUInt32()
    }
}

extension UInt64: Serializer {
    public static var staticTypeId: TypeId { .varUInt64 }

    public static func foryDefault() -> UInt64 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarUInt64(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> UInt64 {
        try context.buffer.readVarUInt64()
    }
}

public struct ForyInt32Fixed: Serializer, Equatable {
    public var rawValue: Int32 = 0
    public init(rawValue: Int32 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> ForyInt32Fixed { .init() }
    public static var staticTypeId: TypeId { .int32 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeInt32(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyInt32Fixed {
        .init(rawValue: try context.buffer.readInt32())
    }
}

public struct ForyInt64Fixed: Serializer, Equatable {
    public var rawValue: Int64 = 0
    public init(rawValue: Int64 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> ForyInt64Fixed { .init() }
    public static var staticTypeId: TypeId { .int64 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeInt64(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyInt64Fixed {
        .init(rawValue: try context.buffer.readInt64())
    }
}

public struct ForyInt64Tagged: Serializer, Equatable {
    public var rawValue: Int64 = 0
    public init(rawValue: Int64 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> ForyInt64Tagged { .init() }
    public static var staticTypeId: TypeId { .taggedInt64 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeTaggedInt64(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyInt64Tagged {
        .init(rawValue: try context.buffer.readTaggedInt64())
    }
}

public struct ForyUInt32Fixed: Serializer, Equatable {
    public var rawValue: UInt32 = 0
    public init(rawValue: UInt32 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> ForyUInt32Fixed { .init() }
    public static var staticTypeId: TypeId { .uint32 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeUInt32(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyUInt32Fixed {
        .init(rawValue: try context.buffer.readUInt32())
    }
}

public struct ForyUInt64Fixed: Serializer, Equatable {
    public var rawValue: UInt64 = 0
    public init(rawValue: UInt64 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> ForyUInt64Fixed { .init() }
    public static var staticTypeId: TypeId { .uint64 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeUInt64(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyUInt64Fixed {
        .init(rawValue: try context.buffer.readUInt64())
    }
}

public struct ForyUInt64Tagged: Serializer, Equatable {
    public var rawValue: UInt64 = 0
    public init(rawValue: UInt64 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> ForyUInt64Tagged { .init() }
    public static var staticTypeId: TypeId { .taggedUInt64 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeTaggedUInt64(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyUInt64Tagged {
        .init(rawValue: try context.buffer.readTaggedUInt64())
    }
}

#if arch(arm64) || arch(x86_64)
extension Int: Serializer {
    public static var staticTypeId: TypeId { .varint64 }

    public static func foryDefault() -> Int { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarInt64(Int64(self))
    }

    public static func foryReadData(_ context: ReadContext) throws -> Int {
        Int(try context.buffer.readVarInt64())
    }
}

extension UInt: Serializer {
    public static var staticTypeId: TypeId { .varUInt64 }

    public static func foryDefault() -> UInt { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarUInt64(UInt64(self))
    }

    public static func foryReadData(_ context: ReadContext) throws -> UInt {
        UInt(try context.buffer.readVarUInt64())
    }
}
#endif

extension Float: Serializer {
    public static var staticTypeId: TypeId { .float32 }

    public static func foryDefault() -> Float { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeFloat32(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Float {
        try context.buffer.readFloat32()
    }
}

extension Double: Serializer {
    public static var staticTypeId: TypeId { .float64 }

    public static func foryDefault() -> Double { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeFloat64(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Double {
        try context.buffer.readFloat64()
    }
}

public struct BFloat16: Serializer, Equatable, Hashable, Sendable {
    public var rawValue: UInt16

    public init(rawValue: UInt16 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> BFloat16 { .init() }
    public static var staticTypeId: TypeId { .bfloat16 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeUInt16(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> BFloat16 {
        .init(rawValue: try context.buffer.readUInt16())
    }
}

extension Float16: Serializer {
    public static var staticTypeId: TypeId { .float16 }

    public static func foryDefault() -> Float16 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeUInt16(bitPattern)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Float16 {
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

    public static func foryDefault() -> String { "" }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        let utf8Bytes = self.utf8
        let header = (UInt64(utf8Bytes.count) << 2) | StringEncoding.utf8.rawValue
        context.buffer.writeVarUInt36Small(header)
        context.buffer.writeBytes(utf8Bytes)
    }

    public static func foryReadData(_ context: ReadContext) throws -> String {
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

    public static func foryDefault() -> Data { Data() }

    public static func foryReadTypeInfo(_ context: ReadContext) throws {
        let rawTypeID = try context.buffer.readVarUInt32()
        guard let typeID = TypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown type id \(rawTypeID)")
        }
        if typeID != .binary && typeID != .uint8Array {
            throw ForyError.typeMismatch(expected: TypeId.binary.rawValue, actual: rawTypeID)
        }
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        context.buffer.writeVarUInt32(UInt32(self.count))
        context.buffer.writeData(self)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Data {
        let length = try context.buffer.readVarUInt32()
        let byteLength = Int(length)
        try context.ensureBinaryLength(byteLength, label: "binary")
        try context.ensureRemainingBytes(byteLength, label: "binary")
        let bytes = try context.buffer.readBytes(count: byteLength)
        return Data(bytes)
    }
}
