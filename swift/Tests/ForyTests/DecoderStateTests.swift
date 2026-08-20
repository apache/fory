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

private enum TypeInfoScopeTestError: Error {
    case expected
}

@Test
func readContextResetReleasesMetaStrings() throws {
    let config = Config()
    let context = ReadContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(config: config),
        config: config
    )
    weak var first: MetaString?
    weak var second: MetaString?

    do {
        let firstValue = try MetaStringEncoder.fieldName.encode("firstResetValue")
        let secondValue = try MetaStringEncoder.fieldName.encode("secondResetValue")
        first = firstValue
        second = secondValue
        context.appendReadMetaString(firstValue)
        context.appendReadMetaString(secondValue)
    }

    #expect(first != nil)
    #expect(second != nil)
    context.reset()
    #expect(first == nil)
    #expect(second == nil)
    #expect(context.getReadMetaString(at: 0) == nil)

    let reusedValue = try MetaStringEncoder.fieldName.encode("reusedValue")
    context.appendReadMetaString(reusedValue)
    let reused = try #require(context.getReadMetaString(at: 0))
    #expect(reused === reusedValue)
}

@Test
func typeInfoScopesRestoreOnSuccess() throws {
    let config = Config()
    let context = ReadContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(config: config),
        config: config
    )
    let outer = TypeInfo(typeID: .int32)
    let inner = TypeInfo(typeID: .int64)

    let result = context.withTypeInfo(outer, for: Int32.self) {
        #expect(context.getTypeInfo(for: Int32.self) === outer)
        return context.withTypeInfo(inner, for: Int32.self) {
            #expect(context.getTypeInfo(for: Int32.self) === inner)
            return 42
        }
    }

    #expect(result == 42)
    #expect(context.getTypeInfo(for: Int32.self) == nil)
}

@Test
func failedTypeInfoScopeWaitsForReset() throws {
    let config = Config()
    let context = ReadContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(config: config),
        config: config
    )
    let outer = TypeInfo(typeID: .int32)
    let inner = TypeInfo(typeID: .int64)

    do {
        try context.withTypeInfo(outer, for: Int32.self) {
            try context.withTypeInfo(inner, for: Int32.self) {
                throw TypeInfoScopeTestError.expected
            }
        }
        Issue.record("expected scoped read failure")
    } catch TypeInfoScopeTestError.expected {
        #expect(context.getTypeInfo(for: Int32.self) === inner)
    }

    context.reset()
    #expect(context.getTypeInfo(for: Int32.self) == nil)
}

@Test
func typeInfoScopeResetAllowsReuse() throws {
    let config = Config()
    let context = ReadContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(config: config),
        config: config
    )
    let failed = TypeInfo(typeID: .int32)
    let nextRoot = TypeInfo(typeID: .int64)

    do {
        try context.withTypeInfo(failed, for: Int32.self) {
            throw TypeInfoScopeTestError.expected
        }
        Issue.record("expected scoped read failure")
    } catch TypeInfoScopeTestError.expected {
        #expect(context.getTypeInfo(for: Int32.self) === failed)
    }

    context.reset()
    context.withTypeInfo(nextRoot, for: Int32.self) {
        #expect(context.getTypeInfo(for: Int32.self) === nextRoot)
    }
    #expect(context.getTypeInfo(for: Int32.self) == nil)
}

@Test
func remoteSchemaLogicalKeyLimitPersists() throws {
    let keyLimit = 8192
    let firstUserTypeID: UInt32 = 10_000
    let config = Config(
        maxSchemaVersionsPerType: 2,
        maxAverageSchemaVersionsPerType: 3
    )
    let resolver = TypeResolver(config: config)
    try resolver.register(Person.self, id: 901)
    try resolver.register(Address.self, id: 902)
    try resolver.finishRegistration()
    let localTypeInfo = try resolver.requireTypeInfo(for: Person.self)

    func remoteTypeMeta(
        userTypeID: UInt32,
        fieldName: String? = nil
    ) throws -> TypeMeta {
        let fields: [TypeMeta.FieldInfo]
        if let fieldName {
            fields = [
                TypeMeta.FieldInfo(
                    fieldID: nil,
                    fieldName: fieldName,
                    fieldType: TypeMeta.FieldType(
                        typeID: TypeId.int32.rawValue,
                        nullable: false
                    )
                )
            ]
        } else {
            fields = []
        }
        return try TypeMeta(
            typeID: TypeId.structType.rawValue,
            userTypeID: userTypeID,
            namespace: .empty(specialChar1: ".", specialChar2: "_"),
            typeName: .empty(specialChar1: "$", specialChar2: "_"),
            registerByName: false,
            fields: fields
        )
    }

    func cache(
        _ typeMeta: TypeMeta,
        exactLocal: Bool = false
    ) throws -> (header: UInt64, typeInfo: TypeInfo) {
        let encoded = try typeMeta.encode()
        let buffer = ByteBuffer(bytes: encoded)
        let header = try buffer.readUInt64()
        buffer.setCursor(0)
        let decoded = try TypeMeta.decode(buffer)
        let typeInfo = try resolver.cacheTypeInfo(
            decoded,
            forHeader: header,
            localTypeInfo: localTypeInfo,
            exactLocal: exactLocal,
            config: config
        )
        return (header, typeInfo)
    }

    func expectLogicalKeyLimit(_ typeMeta: TypeMeta) {
        do {
            _ = try cache(typeMeta)
            Issue.record("expected remote logical type limit")
        } catch ForyError.invalidData(let message) {
            #expect(message.contains("logical type limit"))
        } catch {
            Issue.record("expected invalid data, got \(error)")
        }
    }

    var firstTypeInfo: TypeInfo?
    for offset in 0..<keyLimit {
        let accepted = try cache(
            remoteTypeMeta(userTypeID: firstUserTypeID + UInt32(offset))
        )
        if offset == 0 {
            firstTypeInfo = accepted.typeInfo
        }
    }

    let firstMeta = try remoteTypeMeta(userTypeID: firstUserTypeID)
    let cachedHit = try cache(firstMeta)
    #expect(cachedHit.typeInfo === firstTypeInfo)

    let rejectedTypeID = firstUserTypeID + UInt32(keyLimit)
    expectLogicalKeyLimit(
        try remoteTypeMeta(userTypeID: rejectedTypeID, fieldName: "rejectedA")
    )
    // A rejected key must not become an existing key on a later version.
    expectLogicalKeyLimit(
        try remoteTypeMeta(userTypeID: rejectedTypeID, fieldName: "rejectedB")
    )

    let exactLocalMeta = try remoteTypeMeta(userTypeID: rejectedTypeID + 1)
    let exactLocal = try cache(exactLocalMeta, exactLocal: true)
    #expect(exactLocal.typeInfo === localTypeInfo)
    let exactLocalHit = try cache(exactLocalMeta)
    #expect(exactLocalHit.typeInfo === localTypeInfo)

    let existingVersion = try remoteTypeMeta(
        userTypeID: firstUserTypeID,
        fieldName: "existingKeyVersion"
    )
    let existing = try cache(existingVersion)
    #expect(existing.typeInfo !== firstTypeInfo)
    let existingHit = try cache(existingVersion)
    #expect(existingHit.typeInfo === existing.typeInfo)

    expectLogicalKeyLimit(
        try remoteTypeMeta(userTypeID: rejectedTypeID + 2)
    )
}
