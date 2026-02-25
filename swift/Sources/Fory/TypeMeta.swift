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

private let smallNumFieldsThreshold = 0b1_1111
private let registerByNameFlag: UInt8 = 0b10_0000
private let fieldNameSizeThreshold = 0b1111
private let bigNameThreshold = 0b11_1111

private let typeMetaHasFieldsMetaFlag: UInt64 = 1 << 8
private let typeMetaCompressedFlag: UInt64 = 1 << 9
private let typeMetaSizeMask: UInt64 = 0xFF
private let typeMetaNumHashBits: UInt64 = 50
private let typeMetaHashSeed: UInt64 = 47
private let noUserTypeID: UInt32 = UInt32.max

public let namespaceMetaStringEncodings: [MetaStringEncoding] = [
    .utf8,
    .allToLowerSpecial,
    .lowerUpperDigitSpecial,
]

public let typeNameMetaStringEncodings: [MetaStringEncoding] = [
    .utf8,
    .allToLowerSpecial,
    .lowerUpperDigitSpecial,
    .firstToLowerSpecial,
]

public let fieldNameMetaStringEncodings: [MetaStringEncoding] = [
    .utf8,
    .allToLowerSpecial,
    .lowerUpperDigitSpecial,
]

public struct TypeMetaFieldType: Equatable, Sendable {
    public var typeID: UInt32
    public var nullable: Bool
    public var trackRef: Bool
    public var generics: [TypeMetaFieldType]

    public init(
        typeID: UInt32,
        nullable: Bool,
        trackRef: Bool = false,
        generics: [TypeMetaFieldType] = []
    ) {
        self.typeID = typeID
        self.nullable = nullable
        self.trackRef = trackRef
        self.generics = generics
    }

    fileprivate func write(
        _ buffer: ByteBuffer,
        writeFlags: Bool,
        nullableOverride: Bool? = nil
    ) {
        if writeFlags {
            var header = typeID << 2
            if nullableOverride ?? nullable {
                header |= 0b10
            }
            if trackRef {
                header |= 0b1
            }
            buffer.writeVarUInt32(header)
        } else {
            buffer.writeUInt8(UInt8(truncatingIfNeeded: typeID))
        }

        if typeID == TypeId.list.rawValue || typeID == TypeId.set.rawValue {
            let element = generics.first ?? TypeMetaFieldType(typeID: TypeId.unknown.rawValue, nullable: true)
            element.write(buffer, writeFlags: true, nullableOverride: element.nullable)
        } else if typeID == TypeId.map.rawValue {
            let key = generics.first ?? TypeMetaFieldType(typeID: TypeId.unknown.rawValue, nullable: true)
            let value = generics.dropFirst().first ?? TypeMetaFieldType(typeID: TypeId.unknown.rawValue, nullable: true)
            key.write(buffer, writeFlags: true, nullableOverride: key.nullable)
            value.write(buffer, writeFlags: true, nullableOverride: value.nullable)
        }
    }

    fileprivate static func read(
        _ buffer: ByteBuffer,
        readFlags: Bool,
        nullable: Bool? = nil,
        trackRef: Bool? = nil
    ) throws -> TypeMetaFieldType {
        let header: UInt32
        if readFlags {
            header = try buffer.readVarUInt32()
        } else {
            header = UInt32(try buffer.readUInt8())
        }

        let typeID: UInt32
        let resolvedNullable: Bool
        let resolvedTrackRef: Bool

        if readFlags {
            typeID = header >> 2
            resolvedNullable = (header & 0b10) != 0
            resolvedTrackRef = (header & 0b1) != 0
        } else {
            typeID = header
            resolvedNullable = nullable ?? false
            resolvedTrackRef = trackRef ?? false
        }

        if typeID == TypeId.list.rawValue || typeID == TypeId.set.rawValue {
            let element = try read(buffer, readFlags: true)
            return TypeMetaFieldType(
                typeID: typeID,
                nullable: resolvedNullable,
                trackRef: resolvedTrackRef,
                generics: [element]
            )
        }
        if typeID == TypeId.map.rawValue {
            let key = try read(buffer, readFlags: true)
            let value = try read(buffer, readFlags: true)
            return TypeMetaFieldType(
                typeID: typeID,
                nullable: resolvedNullable,
                trackRef: resolvedTrackRef,
                generics: [key, value]
            )
        }

        return TypeMetaFieldType(
            typeID: typeID,
            nullable: resolvedNullable,
            trackRef: resolvedTrackRef,
            generics: []
        )
    }
}

public struct TypeMetaFieldInfo: Equatable, Sendable {
    public var fieldID: Int16?
    public var fieldName: String
    public var fieldType: TypeMetaFieldType

    public init(fieldID: Int16?, fieldName: String, fieldType: TypeMetaFieldType) {
        self.fieldID = fieldID
        self.fieldName = fieldName
        self.fieldType = fieldType
    }

    fileprivate func write(_ buffer: ByteBuffer) throws {
        var header: UInt8 = 0
        if fieldType.trackRef {
            header |= 0b1
        }
        if fieldType.nullable {
            header |= 0b10
        }

        if let fieldID {
            if fieldID < 0 {
                throw ForyError.encodingError("negative field id is invalid")
            }
            let size = Int(fieldID)
            header |= UInt8(0b11 << 6)
            if size >= fieldNameSizeThreshold {
                header |= 0b0011_1100
                buffer.writeUInt8(header)
                buffer.writeVarUInt32(UInt32(size - fieldNameSizeThreshold))
            } else {
                header |= UInt8(size << 2)
                buffer.writeUInt8(header)
            }
            fieldType.write(buffer, writeFlags: false)
            return
        }

        let snakeName = lowerCamelToLowerUnderscore(fieldName)
        let encoded = try MetaStringEncoder.fieldName.encode(snakeName, allowedEncodings: fieldNameMetaStringEncodings)
        guard let encodingIndex = fieldNameMetaStringEncodings.firstIndex(of: encoded.encoding) else {
            throw ForyError.encodingError("unsupported field name encoding")
        }

        let size = encoded.bytes.count - 1
        header |= UInt8(encodingIndex << 6)
        if size >= fieldNameSizeThreshold {
            header |= 0b0011_1100
            buffer.writeUInt8(header)
            buffer.writeVarUInt32(UInt32(size - fieldNameSizeThreshold))
        } else {
            header |= UInt8(size << 2)
            buffer.writeUInt8(header)
        }

        fieldType.write(buffer, writeFlags: false)
        buffer.writeBytes(encoded.bytes)
    }

    fileprivate static func read(_ buffer: ByteBuffer) throws -> TypeMetaFieldInfo {
        let header = try buffer.readUInt8()
        let encodingFlags = Int((header >> 6) & 0b11)
        var size = Int((header >> 2) & 0b1111)
        if size == fieldNameSizeThreshold {
            size += Int(try buffer.readVarUInt32())
        }
        size += 1

        let nullable = (header & 0b10) != 0
        let trackRef = (header & 0b1) != 0
        let fieldType = try TypeMetaFieldType.read(
            buffer,
            readFlags: false,
            nullable: nullable,
            trackRef: trackRef
        )

        if encodingFlags == 3 {
            let fieldID = Int16(size - 1)
            return TypeMetaFieldInfo(
                fieldID: fieldID,
                fieldName: "$tag\(fieldID)",
                fieldType: fieldType
            )
        }

        guard encodingFlags < fieldNameMetaStringEncodings.count else {
            throw ForyError.invalidData("invalid field name encoding id")
        }
        let nameBytes = try buffer.readBytes(count: size)
        let name = try MetaStringDecoder.fieldName
            .decode(bytes: nameBytes, encoding: fieldNameMetaStringEncodings[encodingFlags])
            .value

        return TypeMetaFieldInfo(fieldID: nil, fieldName: name, fieldType: fieldType)
    }
}

public struct TypeMeta: Equatable, Sendable {
    public let typeID: UInt32?
    public let userTypeID: UInt32?
    public let namespace: MetaString
    public let typeName: MetaString
    public let registerByName: Bool
    public let fields: [TypeMetaFieldInfo]
    public let hasFieldsMeta: Bool
    public let compressed: Bool
    public let headerHash: UInt64

    public init(
        typeID: UInt32?,
        userTypeID: UInt32?,
        namespace: MetaString,
        typeName: MetaString,
        registerByName: Bool,
        fields: [TypeMetaFieldInfo],
        hasFieldsMeta: Bool = true,
        compressed: Bool = false,
        headerHash: UInt64 = 0
    ) throws {
        if registerByName {
            if typeName.value.isEmpty {
                throw ForyError.encodingError("type name is required in register-by-name mode")
            }
        } else {
            guard typeID != nil else {
                throw ForyError.encodingError("type id is required in register-by-id mode")
            }
            guard let userTypeID, userTypeID != noUserTypeID else {
                throw ForyError.encodingError("user type id is required in register-by-id mode")
            }
        }

        self.typeID = typeID
        self.userTypeID = userTypeID
        self.namespace = namespace
        self.typeName = typeName
        self.registerByName = registerByName
        self.fields = fields
        self.hasFieldsMeta = hasFieldsMeta
        self.compressed = compressed
        self.headerHash = headerHash
    }

    public func encode() throws -> [UInt8] {
        if compressed {
            throw ForyError.encodingError("compressed TypeMeta is not supported yet")
        }

        let body = try encodeBody()
        let bodyHash = MurmurHash3.x64_128(body, seed: typeMetaHashSeed).0
        let shifted = bodyHash << (64 - typeMetaNumHashBits)
        let signed = Int64(bitPattern: shifted)
        let absSigned = signed == Int64.min ? signed : Swift.abs(signed)

        var header = UInt64(bitPattern: absSigned)
        if hasFieldsMeta {
            header |= typeMetaHasFieldsMetaFlag
        }
        if compressed {
            header |= typeMetaCompressedFlag
        }
        header |= UInt64(min(body.count, Int(typeMetaSizeMask)))

        let buffer = ByteBuffer(capacity: body.count + 16)
        buffer.writeUInt64(header)
        if body.count >= Int(typeMetaSizeMask) {
            buffer.writeVarUInt32(UInt32(body.count - Int(typeMetaSizeMask)))
        }
        buffer.writeBytes(body)
        return buffer.storage
    }

    public static func decode(_ bytes: [UInt8]) throws -> TypeMeta {
        try decode(ByteBuffer(bytes: bytes))
    }

    public static func decode(_ buffer: ByteBuffer) throws -> TypeMeta {
        let header = try buffer.readUInt64()
        let compressed = (header & typeMetaCompressedFlag) != 0
        let hasFieldsMeta = (header & typeMetaHasFieldsMetaFlag) != 0

        var metaSize = Int(header & typeMetaSizeMask)
        if metaSize == Int(typeMetaSizeMask) {
            metaSize += Int(try buffer.readVarUInt32())
        }

        let encodedBody = try buffer.readBytes(count: metaSize)
        if compressed {
            throw ForyError.encodingError("compressed TypeMeta is not supported yet")
        }

        let bodyReader = ByteBuffer(bytes: encodedBody)
        let metaHeader = try bodyReader.readUInt8()

        var numFields = Int(metaHeader & UInt8(smallNumFieldsThreshold))
        if numFields == smallNumFieldsThreshold {
            numFields += Int(try bodyReader.readVarUInt32())
        }

        let registerByName = (metaHeader & registerByNameFlag) != 0

        let typeID: UInt32?
        let userTypeID: UInt32?
        let namespace: MetaString
        let typeName: MetaString

        if registerByName {
            namespace = try readName(bodyReader, decoder: .namespace, encodings: namespaceMetaStringEncodings)
            typeName = try readName(bodyReader, decoder: .typeName, encodings: typeNameMetaStringEncodings)
            typeID = nil
            userTypeID = nil
        } else {
            let rawTypeID = try bodyReader.readUInt8()
            typeID = UInt32(rawTypeID)
            userTypeID = try bodyReader.readVarUInt32()
            namespace = MetaString.empty(specialChar1: ".", specialChar2: "_")
            typeName = MetaString.empty(specialChar1: "$", specialChar2: "_")
        }

        var fieldInfos: [TypeMetaFieldInfo] = []
        fieldInfos.reserveCapacity(numFields)
        for _ in 0..<numFields {
            fieldInfos.append(try TypeMetaFieldInfo.read(bodyReader))
        }

        if bodyReader.remaining != 0 {
            throw ForyError.invalidData("unexpected trailing bytes in TypeMeta body")
        }

        return try TypeMeta(
            typeID: typeID,
            userTypeID: userTypeID,
            namespace: namespace,
            typeName: typeName,
            registerByName: registerByName,
            fields: fieldInfos,
            hasFieldsMeta: hasFieldsMeta,
            compressed: compressed,
            headerHash: header >> (64 - typeMetaNumHashBits)
        )
    }

    private func encodeBody() throws -> [UInt8] {
        let buffer = ByteBuffer(capacity: 128)

        var metaHeader = UInt8(min(fields.count, smallNumFieldsThreshold))
        if registerByName {
            metaHeader |= registerByNameFlag
        }
        buffer.writeUInt8(metaHeader)

        if fields.count >= smallNumFieldsThreshold {
            buffer.writeVarUInt32(UInt32(fields.count - smallNumFieldsThreshold))
        }

        if registerByName {
            try Self.writeName(buffer, name: namespace, encodings: namespaceMetaStringEncodings)
            try Self.writeName(buffer, name: typeName, encodings: typeNameMetaStringEncodings)
        } else {
            guard let typeID else {
                throw ForyError.encodingError("type id is required in register-by-id mode")
            }
            guard let userTypeID, userTypeID != noUserTypeID else {
                throw ForyError.encodingError("user type id is required in register-by-id mode")
            }
            buffer.writeUInt8(UInt8(truncatingIfNeeded: typeID))
            buffer.writeVarUInt32(userTypeID)
        }

        for field in fields {
            try field.write(buffer)
        }

        return buffer.storage
    }

    private static func writeName(
        _ buffer: ByteBuffer,
        name: MetaString,
        encodings: [MetaStringEncoding]
    ) throws {
        let normalizedName: MetaString
        if encodings.contains(name.encoding) {
            normalizedName = name
        } else {
            let encoder: MetaStringEncoder
            if encodings == namespaceMetaStringEncodings {
                encoder = .namespace
            } else if encodings == typeNameMetaStringEncodings {
                encoder = .typeName
            } else {
                encoder = .fieldName
            }
            normalizedName = try encoder.encode(name.value, allowedEncodings: encodings)
        }

        guard let encodingIndex = encodings.firstIndex(of: normalizedName.encoding) else {
            throw ForyError.encodingError("failed to normalize meta string encoding")
        }

        let bytes = normalizedName.bytes
        if bytes.count >= bigNameThreshold {
            buffer.writeUInt8(UInt8((bigNameThreshold << 2) | encodingIndex))
            buffer.writeVarUInt32(UInt32(bytes.count - bigNameThreshold))
        } else {
            buffer.writeUInt8(UInt8((bytes.count << 2) | encodingIndex))
        }
        buffer.writeBytes(bytes)
    }

    private static func readName(
        _ buffer: ByteBuffer,
        decoder: MetaStringDecoder,
        encodings: [MetaStringEncoding]
    ) throws -> MetaString {
        let header = try buffer.readUInt8()
        let encodingIndex = Int(header & 0b11)
        guard encodingIndex < encodings.count else {
            throw ForyError.invalidData("invalid meta string encoding index")
        }

        var length = Int(header >> 2)
        if length >= bigNameThreshold {
            length = bigNameThreshold + Int(try buffer.readVarUInt32())
        }
        let bytes = try buffer.readBytes(count: length)
        return try decoder.decode(bytes: bytes, encoding: encodings[encodingIndex])
    }
}

private func lowerCamelToLowerUnderscore(_ name: String) -> String {
    if name.isEmpty {
        return name
    }

    let chars = Array(name)
    var result = String()
    result.reserveCapacity(name.count + 4)

    for (index, char) in chars.enumerated() {
        if char.isUppercase {
            if index > 0 {
                let prevUpper = chars[index - 1].isUppercase
                let nextUpperOrEnd = (index + 1 >= chars.count) || chars[index + 1].isUppercase
                if !prevUpper || !nextUpperOrEnd {
                    result.append("_")
                }
            }
            result.append(char.lowercased())
        } else {
            result.append(char)
        }
    }

    return result
}
