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
    #expect(resolver.getTypeInfo(forHeader: rejected.1) == nil)

    let acceptedBytes = encodedListTypeMeta(
        depth: 20,
        compatible: true,
        userTypeID: 902
    )
    let accepted = (acceptedBytes, try ByteBuffer(bytes: acceptedBytes).readUInt64())
    _ = try context(accepted.0).readTypeInfo(for: Address.self)
    #expect(resolver.getTypeInfo(forHeader: accepted.1) != nil)
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
        fields: [TypeMeta.FieldInfo(fieldID: nil, fieldName: "value", fieldType: fieldType)]
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
