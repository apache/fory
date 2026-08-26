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

import Testing
@testable import Fory

@ForyStruct
private struct DeepTypeMetaV1: Equatable {
    @ForyField(id: 1)
    var removed: [[[[[[Int32]]]]]] = []

    @ForyField(id: 2)
    var keep: Int32 = 0
}

@ForyStruct
private struct DeepTypeMetaV2: Equatable {
    @ForyField(id: 2)
    var keep: Int32 = 0
}

@Test
func typeMetaFieldDepthUsesLimit() throws {
    let maxDepth = 20
    let decoded = try TypeMeta.decode(encodedListTypeMeta(depth: maxDepth))
    var fieldType = try #require(decoded.fields.first?.fieldType)
    for _ in 0..<maxDepth {
        #expect(fieldType.typeID == TypeId.list.rawValue)
        fieldType = try #require(fieldType.generics.first)
    }
    #expect(fieldType.typeID == TypeId.int32.rawValue)

    #expect(throws: ForyError.self) {
        _ = try TypeMeta.decode(encodedListTypeMeta(depth: maxDepth + 1))
    }
}

@Test
func typeMetaTruncationFailsCleanly() {
    #expect(throws: ForyError.self) {
        _ = try TypeMeta.decode(encodedListTypeMeta(depth: 3, includeLeaf: false))
    }
}

@Test
func remoteTypeMetaUsesFixedDepth() throws {
    let config = Config(compatible: true, maxDepth: 2)
    let resolver = TypeResolver(config: config)
    try resolver.register(Address.self, id: 902)
    try resolver.finishRegistration()

    func context(_ encoded: [UInt8]) -> ReadContext {
        let buffer = ByteBuffer()
        buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.compatibleStruct.rawValue))
        buffer.writeUInt8(0)
        buffer.writeBytes(encoded)
        return ReadContext(buffer: buffer, typeResolver: resolver, config: config)
    }

    let rejectedBytes = encodedListTypeMeta(
        depth: 21,
        compatible: true,
        userTypeID: 902
    )
    let rejected = (rejectedBytes, try ByteBuffer(bytes: rejectedBytes).readUInt64())
    #expect(throws: ForyError.self) {
        _ = try context(rejected.0).readTypeInfo(for: Address.self)
    }
    #expect(resolver.getTypeInfo(forHeaderHash: typeMetaHashFromHeader(rejected.1)) == nil)

    let acceptedBytes = encodedListTypeMeta(
        depth: 20,
        compatible: true,
        userTypeID: 902
    )
    let accepted = (acceptedBytes, try ByteBuffer(bytes: acceptedBytes).readUInt64())
    _ = try context(accepted.0).readTypeInfo(for: Address.self)
    #expect(resolver.getTypeInfo(forHeaderHash: typeMetaHashFromHeader(accepted.1)) != nil)
}

@Test
func typeMetaEncodeUsesDepthLimit() throws {
    let source = try constructedTypeMeta(depth: 20)
    let encoded = try source.encode()
    let decoded = try TypeMeta.decode(encoded)
    #expect(decoded.fields.first?.fieldType == source.fields.first?.fieldType)

    #expect(throws: ForyError.self) {
        _ = try constructedTypeMeta(depth: 21).encode()
    }
}

@Test
func registeredTypeMetaIgnoresDynamicDepth() throws {
    let config = Config(trackRef: false, compatible: true, maxDepth: 5)
    let writer = Fory(config: config)
    try writer.register(DeepTypeMetaV1.self, id: 904)
    let source = DeepTypeMetaV1(removed: [], keep: 91)
    let encoded = try writer.serialize(source)

    let exactReader = Fory(config: config)
    try exactReader.register(DeepTypeMetaV1.self, id: 904)
    let exact: DeepTypeMetaV1 = try exactReader.deserialize(encoded)
    #expect(exact == source)

    let evolvedReader = Fory(config: config)
    try evolvedReader.register(DeepTypeMetaV2.self, id: 904)
    let evolved: DeepTypeMetaV2 = try evolvedReader.deserialize(encoded)
    #expect(evolved.keep == source.keep)
}

@Test
func cachedMetaUsesHeaderHash() throws {
    let config = Config(compatible: true)
    let resolver = TypeResolver(config: config)
    try resolver.register(Person.self, id: 901)
    try resolver.register(Address.self, id: 902)
    try resolver.finishRegistration()
    let remote = try TypeMeta(
        typeID: TypeId.compatibleStruct.rawValue,
        userTypeID: 901,
        namespace: .empty(specialChar1: ".", specialChar2: "_"),
        typeName: .empty(specialChar1: "$", specialChar2: "_"),
        registerByName: false,
        fields: [
            TypeMeta.FieldInfo(
                fieldID: -1,
                fieldName: "remoteId",
                fieldType: TypeMeta.FieldType(typeID: TypeId.int32.rawValue, nullable: false)
            )
        ]
    )
    let encoded = try remote.encode()
    let originalHeader = try ByteBuffer(bytes: encoded).readUInt64()
    let headerHash = typeMetaHashFromHeader(originalHeader)

    let missBuffer = compatibleTypeInfoFrame(header: originalHeader)
    missBuffer.writeBytes(Array(encoded.dropFirst(8)))
    let missContext = ReadContext(buffer: missBuffer, typeResolver: resolver, config: config)
    let missOwner = try missContext.readTypeInfo(for: Person.self)
    let owner = try #require(missOwner)
    let localTypeInfo = try resolver.requireTypeInfo(for: Person.self)
    #expect(owner !== localTypeInfo)
    #expect(resolver.getTypeInfo(forHeaderHash: headerHash) === owner)

    let currentBody: [UInt8] = [0xA1, 0xB2]
    let currentHeader = (headerHash << 12) | UInt64(currentBody.count)
    #expect((currentHeader & 0xFFF) != (originalHeader & 0xFFF))
    #expect(typeMetaHashFromHeader(currentHeader) == headerHash)
    let hitBuffer = compatibleTypeInfoFrame(header: currentHeader)
    hitBuffer.writeBytes(currentBody)
    hitBuffer.writeUInt8(0xC3)
    let hitContext = ReadContext(buffer: hitBuffer, typeResolver: resolver, config: config)
    let cachedOwner = try hitContext.readTypeInfo(for: Person.self)
    let hitOwner = try #require(cachedOwner)
    #expect(hitOwner === owner)
    #expect(try hitBuffer.readUInt8() == 0xC3)

    let truncatedHeader = (headerHash << 12) | 2
    let truncatedBuffer = compatibleTypeInfoFrame(header: truncatedHeader)
    truncatedBuffer.writeUInt8(0xA1)
    let truncatedContext = ReadContext(
        buffer: truncatedBuffer,
        typeResolver: resolver,
        config: config
    )
    #expect(throws: ForyError.self) {
        _ = try truncatedContext.readTypeInfo(for: Person.self)
    }
}

@Test
func localMetaUsesHeaderHash() throws {
    let config = Config(compatible: true)
    let resolver = TypeResolver(config: config)
    try resolver.register(Person.self, id: 901)
    try resolver.register(Address.self, id: 902)
    try resolver.finishRegistration()
    let firstTypeInfo = try resolver.requireTypeInfo(for: Person.self)
    let firstBytes = try #require(firstTypeInfo.typeDefBytes)
    let localTypeInfo = try resolver.requireTypeInfo(for: Address.self)
    let headerHash = try #require(localTypeInfo.typeDefHeaderHash)
    let currentBody: [UInt8] = [0xD1, 0xD2, 0xD3]
    let currentHeader = (headerHash << 12) | UInt64(currentBody.count)
    let localBytes = try #require(localTypeInfo.typeDefBytes)
    let localHeader = try ByteBuffer(bytes: localBytes).readUInt64()
    #expect((currentHeader & 0xFFF) != (localHeader & 0xFFF))

    let firstHeader = try ByteBuffer(bytes: firstBytes).readUInt64()
    let buffer = compatibleTypeInfoFrame(header: firstHeader)
    buffer.writeBytes(Array(firstBytes.dropFirst(8)))
    buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.compatibleStruct.rawValue))
    buffer.writeUInt8(0)
    buffer.writeUInt64(currentHeader)
    buffer.writeBytes(currentBody)
    buffer.writeUInt8(0xE4)
    let context = ReadContext(buffer: buffer, typeResolver: resolver, config: config)
    _ = try context.readTypeInfo(for: Person.self)
    let localOwner = try context.readTypeInfo(for: Address.self)
    #expect(localOwner === localTypeInfo)
    #expect(try buffer.readUInt8() == 0xE4)
}

@Test
func genericMetaUsesResolvedHash() throws {
    let config = Config(compatible: true, maxSchemaVersionsPerType: 1)
    let resolver = TypeResolver(config: config)
    let emptyNamespace = MetaString.empty(specialChar1: ".", specialChar2: "_")
    let emptyTypeName = MetaString.empty(specialChar1: "$", specialChar2: "_")

    func typeMeta(fields: [TypeMeta.FieldInfo]) throws -> TypeMeta {
        try TypeMeta(
            typeID: TypeId.compatibleStruct.rawValue,
            userTypeID: 903,
            namespace: emptyNamespace,
            typeName: emptyTypeName,
            registerByName: false,
            fields: fields
        )
    }

    func field(_ name: String) -> TypeMeta.FieldInfo {
        TypeMeta.FieldInfo(
            fieldID: -1,
            fieldName: name,
            fieldType: TypeMeta.FieldType(typeID: TypeId.int32.rawValue, nullable: false)
        )
    }

    let received = try typeMeta(fields: [field("remoteId")])
    let receivedBytes = try received.encode()
    let receivedHeader = try ByteBuffer(bytes: receivedBytes).readUInt64()
    let retainedBytes = try typeMeta(fields: []).encode()
    let retainedHeader = try ByteBuffer(bytes: retainedBytes).readUInt64()
    #expect((receivedHeader & 0xFFF) != (retainedHeader & 0xFFF))

    let frame = compatibleTypeInfoFrame(header: receivedHeader)
    frame.writeBytes(Array(receivedBytes.dropFirst(8)))
    #expect(try frame.readUInt8() == UInt8(truncatingIfNeeded: TypeId.compatibleStruct.rawValue))
    #expect(try frame.readVarUInt32() == 0)
    let decoded = try TypeMeta.decode(
        frame,
        maxTypeFields: config.maxTypeFields,
        maxTypeMetaBytes: config.maxTypeMetaBytes
    )
    #expect(frame.remaining == 0)

    let localMeta = try TypeMeta(
        typeID: TypeId.compatibleStruct.rawValue,
        userTypeID: 903,
        namespace: emptyNamespace,
        typeName: emptyTypeName,
        registerByName: false,
        fields: [],
        headerHash: decoded.headerHash
    )
    let localTypeInfo = TypeInfo(
        serializerTypeID: ObjectIdentifier(TypeInfo.self),
        targetTypeID: ObjectIdentifier(TypeInfo.self),
        typeID: .structType,
        userTypeID: 903,
        registerByName: false,
        evolving: true,
        namespace: emptyNamespace,
        typeName: emptyTypeName,
        typeMeta: localMeta,
        typeDefBytes: retainedBytes,
        typeDefHeaderHash: decoded.headerHash,
        typeDefHasUserTypeFields: false,
        isRefType: false,
        writer: { _, _ in },
        reader: { _ in () },
        compatibleReader: { _, _ in () }
    )
    let localOwner = try resolver.cacheTypeInfo(
        decoded,
        forHeaderHash: decoded.headerHash,
        localTypeInfo: localTypeInfo,
        config: config
    )
    #expect(localOwner === localTypeInfo)
    #expect(resolver.getTypeInfo(forHeaderHash: decoded.headerHash) == nil)

    let changed = try typeMeta(fields: [field("remoteId"), field("remoteValue")])
    let changedBytes = try changed.encode()
    let changedBuffer = ByteBuffer(bytes: changedBytes)
    let changedHeader = try changedBuffer.readUInt64()
    changedBuffer.setCursor(0)
    let changedMeta = try TypeMeta.decode(
        changedBuffer,
        maxTypeFields: config.maxTypeFields,
        maxTypeMetaBytes: config.maxTypeMetaBytes
    )
    #expect(typeMetaHashFromHeader(changedHeader) != decoded.headerHash)
    let remoteOwner = try resolver.cacheTypeInfo(
        changedMeta,
        forHeaderHash: changedMeta.headerHash,
        localTypeInfo: localTypeInfo,
        config: config
    )
    #expect(remoteOwner !== localTypeInfo)
    #expect(resolver.getTypeInfo(forHeaderHash: changedMeta.headerHash) === remoteOwner)
}

private func compatibleTypeInfoFrame(header: UInt64) -> ByteBuffer {
    let buffer = ByteBuffer()
    buffer.writeUInt8(UInt8(truncatingIfNeeded: TypeId.compatibleStruct.rawValue))
    buffer.writeUInt8(0)
    buffer.writeUInt64(header)
    return buffer
}

private func encodedListTypeMeta(
    depth: Int,
    includeLeaf: Bool = true,
    compatible: Bool = false,
    userTypeID: UInt32 = 901
) -> [UInt8] {
    precondition(depth > 0)
    let body = ByteBuffer()
    body.writeUInt8((compatible ? 0b1100_0000 : 0b1000_0000) | 1)
    body.writeVarUInt32(userTypeID)
    body.writeUInt8(0)
    body.writeUInt8(UInt8(TypeId.list.rawValue))
    for _ in 1..<depth {
        body.writeVarUInt32(TypeId.list.rawValue << 2)
    }
    if includeLeaf {
        body.writeVarUInt32(TypeId.int32.rawValue << 2)
        body.writeUInt8(0x66)
    }
    return encodedTypeMetaBody(body)
}

private func constructedTypeMeta(depth: Int) throws -> TypeMeta {
    var fieldType = TypeMeta.FieldType(typeID: TypeId.int32.rawValue, nullable: false)
    for _ in 0..<depth {
        fieldType = TypeMeta.FieldType(
            typeID: TypeId.list.rawValue,
            nullable: false,
            generics: [fieldType]
        )
    }
    return try TypeMeta(
        typeID: TypeId.compatibleStruct.rawValue,
        userTypeID: 903,
        namespace: .empty(specialChar1: ".", specialChar2: "_"),
        typeName: .empty(specialChar1: "$", specialChar2: "_"),
        registerByName: false,
        fields: [TypeMeta.FieldInfo(fieldID: -1, fieldName: "value", fieldType: fieldType)]
    )
}

private func encodedTypeMetaBody(_ body: ByteBuffer) -> [UInt8] {
    let bodyBytes = Array(body.storage.prefix(body.count))
    let headerLowBits = UInt64(min(bodyBytes.count, 255))
    var hashInput = bodyBytes
    hashInput.append(UInt8(truncatingIfNeeded: headerLowBits))
    hashInput.append(UInt8(truncatingIfNeeded: headerLowBits >> 8))
    let shifted = MurmurHash3.x64_128(hashInput, seed: 47).0 << 12
    let signed = Int64(bitPattern: shifted)
    let absSigned = signed == Int64.min ? signed : Swift.abs(signed)
    let hash = UInt64(bitPattern: absSigned) & (UInt64.max << 12)

    let encoded = ByteBuffer()
    encoded.writeUInt64(hash | headerLowBits)
    if bodyBytes.count >= 255 {
        encoded.writeVarUInt32(UInt32(bodyBytes.count - 255))
    }
    encoded.writeBytes(bodyBytes)
    return Array(encoded.storage.prefix(encoded.count))
}
