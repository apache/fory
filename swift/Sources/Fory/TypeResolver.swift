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

@inline(__always)
func normalizeRegisteredTypeID(_ typeID: TypeId) -> TypeId {
    switch typeID {
    case .namedEnum:
        return .enumType
    case .compatibleStruct, .namedCompatibleStruct, .namedStruct:
        return .structType
    case .namedExt:
        return .ext
    case .namedUnion, .union:
        return .typedUnion
    default:
        return typeID
    }
}

@inline(__always)
func namedRegisteredTypeID(for baseTypeID: TypeId, compatible: Bool, evolving: Bool) -> TypeId {
    switch baseTypeID {
    case .structType:
        return compatible && evolving ? .namedCompatibleStruct : .namedStruct
    case .enumType:
        return .namedEnum
    case .ext:
        return .namedExt
    case .typedUnion:
        return .namedUnion
    default:
        return baseTypeID
    }
}

@inline(__always)
func idRegisteredTypeID(for baseTypeID: TypeId, compatible: Bool, evolving: Bool) -> TypeId {
    switch baseTypeID {
    case .structType:
        return compatible && evolving ? .compatibleStruct : .structType
    default:
        return baseTypeID
    }
}

@inline(__always)
func resolveRegisteredWireTypeID(
    declaredTypeID: TypeId,
    registerByName: Bool,
    compatible: Bool,
    evolving: Bool = true
) -> TypeId {
    let baseTypeID = normalizeRegisteredTypeID(declaredTypeID)
    if registerByName {
        return namedRegisteredTypeID(for: baseTypeID, compatible: compatible, evolving: evolving)
    }
    return idRegisteredTypeID(for: baseTypeID, compatible: compatible, evolving: evolving)
}

@inline(__always)
func isAllowedRegisteredWireTypeID(
    _ wireTypeID: TypeId,
    declaredTypeID: TypeId,
    registerByName: Bool,
    compatible: Bool,
    evolving: Bool = true
) -> Bool {
    let baseTypeID = normalizeRegisteredTypeID(declaredTypeID)
    let expected = resolveRegisteredWireTypeID(
        declaredTypeID: declaredTypeID,
        registerByName: registerByName,
        compatible: compatible,
        evolving: evolving
    )
    if wireTypeID == expected {
        return true
    }
    if baseTypeID == .structType, compatible {
        return wireTypeID == .compatibleStruct ||
            wireTypeID == .namedCompatibleStruct ||
            wireTypeID == .structType ||
            wireTypeID == .namedStruct
    }
    if baseTypeID == .typedUnion {
        return wireTypeID == .union || (registerByName && wireTypeID == .namedUnion)
    }
    return false
}

@inline(__always)
func registeredWireTypeNeedsUserTypeID(_ wireTypeID: TypeId) -> Bool {
    switch wireTypeID {
    case .enumType, .structType, .ext, .typedUnion, .union:
        return true
    default:
        return false
    }
}

@inline(__always)
private func encodedTypeDefHeader(_ bytes: [UInt8]) throws -> UInt64 {
    guard bytes.count >= 8 else {
        throw ForyError.invalidData("encoded compatible type metadata must include an 8-byte header")
    }
    let buffer = ByteBuffer(bytes: bytes)
    return try buffer.readUInt64()
}

@inline(__always)
private func encodedTypeDefHeaderHash(_ bytes: [UInt8]) throws -> UInt64 {
    guard bytes.count >= 8 else {
        throw ForyError.invalidData("encoded compatible type metadata must include an 8-byte header")
    }
    let buffer = ByteBuffer(bytes: bytes)
    let header = try buffer.readUInt64()
    return header >> 14
}

private func fieldNeedsTypeInfo(_ fieldType: TypeMeta.FieldType) -> Bool {
    if let typeID = TypeId(rawValue: fieldType.typeID),
       TypeId.needsTypeInfoForField(typeID) {
        return true
    }
    return fieldType.generics.contains { fieldNeedsTypeInfo($0) }
}

private func encodedTypeDefHasUserTypeFields(_ fields: [TypeMeta.FieldInfo]) -> Bool {
    fields.contains { fieldNeedsTypeInfo($0.fieldType) }
}

public final class TypeInfo: @unchecked Sendable {
    static let uncached = TypeInfo(typeID: .unknown)

    let swiftTypeID: ObjectIdentifier
    let typeID: TypeId
    let userTypeID: UInt32?
    let registerByName: Bool
    let evolving: Bool
    let namespace: MetaString
    let typeName: MetaString
    let typeMeta: TypeMeta?
    public let compatibleTypeMeta: TypeMeta?
    let typeDefBytes: [UInt8]?
    let firstTypeDefBytes: [UInt8]?
    let typeDefHeader: UInt64?
    public let typeDefHeaderHash: UInt64?
    public let typeDefHasUserTypeFields: Bool

    private let reader: (ReadContext) throws -> Any
    private let compatibleReader: (ReadContext, TypeInfo) throws -> Any
    private let nativeWireTypeID: TypeId
    private let compatibleWireTypeID: TypeId

    init(
        swiftTypeID: ObjectIdentifier,
        typeID: TypeId,
        userTypeID: UInt32?,
        registerByName: Bool,
        evolving: Bool,
        namespace: MetaString,
        typeName: MetaString,
        typeMeta: TypeMeta? = nil,
        compatibleTypeMeta: TypeMeta? = nil,
        typeDefBytes: [UInt8]? = nil,
        firstTypeDefBytes: [UInt8]? = nil,
        typeDefHeader: UInt64? = nil,
        typeDefHeaderHash: UInt64? = nil,
        typeDefHasUserTypeFields: Bool = true,
        reader: @escaping (ReadContext) throws -> Any,
        compatibleReader: @escaping (ReadContext, TypeInfo) throws -> Any
    ) {
        self.swiftTypeID = swiftTypeID
        self.typeID = typeID
        self.userTypeID = userTypeID
        self.registerByName = registerByName
        self.evolving = evolving
        self.namespace = namespace
        self.typeName = typeName
        self.typeMeta = typeMeta
        self.compatibleTypeMeta = compatibleTypeMeta ?? typeMeta
        self.typeDefBytes = typeDefBytes
        self.firstTypeDefBytes = firstTypeDefBytes
        self.typeDefHeader = typeDefHeader
        self.typeDefHeaderHash = typeDefHeaderHash
        self.typeDefHasUserTypeFields = typeDefHasUserTypeFields
        self.reader = reader
        self.compatibleReader = compatibleReader
        nativeWireTypeID = resolveRegisteredWireTypeID(
            declaredTypeID: typeID,
            registerByName: registerByName,
            compatible: false,
            evolving: evolving
        )
        compatibleWireTypeID = resolveRegisteredWireTypeID(
            declaredTypeID: typeID,
            registerByName: registerByName,
            compatible: true,
            evolving: evolving
        )
    }

    convenience init(
        swiftTypeID: ObjectIdentifier,
        typeID: TypeId,
        userTypeID: UInt32?,
        registerByName: Bool,
        evolving: Bool,
        namespace: MetaString,
        typeName: MetaString,
        fields: [TypeMeta.FieldInfo],
        reader: @escaping (ReadContext) throws -> Any,
        compatibleReader: @escaping (ReadContext, TypeInfo) throws -> Any
    ) throws {
        let compatibleWireTypeID = resolveRegisteredWireTypeID(
            declaredTypeID: typeID,
            registerByName: registerByName,
            compatible: true,
            evolving: evolving
        )
        let typeMeta = try TypeMeta(
            typeID: registerByName ? nil : compatibleWireTypeID.rawValue,
            userTypeID: registerByName ? nil : userTypeID,
            namespace: namespace,
            typeName: typeName,
            registerByName: registerByName,
            fields: fields,
            hasFieldsMeta: !fields.isEmpty
        )
        let typeDefBytes = try typeMeta.encode()
        var firstTypeDefBytes = [UInt8]()
        firstTypeDefBytes.reserveCapacity(typeDefBytes.count + 1)
        firstTypeDefBytes.append(0)
        firstTypeDefBytes.append(contentsOf: typeDefBytes)
        let typeDefHeader = try encodedTypeDefHeader(typeDefBytes)
        let typeDefHeaderHash = try encodedTypeDefHeaderHash(typeDefBytes)
        let canonicalTypeMeta = try TypeMeta(
            typeID: registerByName ? nil : compatibleWireTypeID.rawValue,
            userTypeID: registerByName ? nil : userTypeID,
            namespace: namespace,
            typeName: typeName,
            registerByName: registerByName,
            fields: fields,
            hasFieldsMeta: !fields.isEmpty,
            headerHash: typeDefHeaderHash
        )
        self.init(
            swiftTypeID: swiftTypeID,
            typeID: typeID,
            userTypeID: userTypeID,
            registerByName: registerByName,
            evolving: evolving,
            namespace: namespace,
            typeName: typeName,
            typeMeta: canonicalTypeMeta,
            compatibleTypeMeta: canonicalTypeMeta,
            typeDefBytes: typeDefBytes,
            firstTypeDefBytes: firstTypeDefBytes,
            typeDefHeader: typeDefHeader,
            typeDefHeaderHash: typeDefHeaderHash,
            typeDefHasUserTypeFields: encodedTypeDefHasUserTypeFields(fields),
            reader: reader,
            compatibleReader: compatibleReader
        )
    }

    convenience init(typeID: TypeId) {
        self.init(
            swiftTypeID: ObjectIdentifier(TypeInfo.self),
            typeID: typeID,
            userTypeID: nil,
            registerByName: false,
            evolving: true,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            reader: { _ in
                throw ForyError.invalidData("dynamic type \(typeID) uses runtime-only decode path")
            },
            compatibleReader: { _, _ in
                throw ForyError.invalidData("dynamic compatible type \(typeID) uses runtime-only decode path")
            }
        )
    }

    convenience init(dynamic typeInfo: TypeInfo, compatibleTypeMeta: TypeMeta) {
        self.init(
            swiftTypeID: typeInfo.swiftTypeID,
            typeID: typeInfo.typeID,
            userTypeID: typeInfo.userTypeID,
            registerByName: typeInfo.registerByName,
            evolving: typeInfo.evolving,
            namespace: typeInfo.namespace,
            typeName: typeInfo.typeName,
            typeMeta: typeInfo.typeMeta,
            compatibleTypeMeta: compatibleTypeMeta,
            typeDefBytes: typeInfo.typeDefBytes,
            firstTypeDefBytes: typeInfo.firstTypeDefBytes,
            typeDefHeader: typeInfo.typeDefHeader,
            typeDefHeaderHash: typeInfo.typeDefHeaderHash,
            typeDefHasUserTypeFields: typeInfo.typeDefHasUserTypeFields,
            reader: typeInfo.reader,
            compatibleReader: typeInfo.compatibleReader
        )
    }

    @inline(__always)
    func matches(
        typeID: TypeId,
        userTypeID: UInt32?,
        registerByName: Bool,
        evolving: Bool,
        namespace: String,
        typeName: String
    ) -> Bool {
        self.typeID == typeID &&
            self.userTypeID == userTypeID &&
            self.registerByName == registerByName &&
            self.evolving == evolving &&
            self.namespace.value == namespace &&
            self.typeName.value == typeName
    }

    @inline(__always)
    func wireTypeID(compatible: Bool) -> TypeId {
        compatible ? compatibleWireTypeID : nativeWireTypeID
    }

    @inline(__always)
    func read(_ context: ReadContext, typeInfo: TypeInfo? = nil) throws -> Any {
        if let typeInfo {
            return try compatibleReader(context, typeInfo)
        }
        if context.compatible &&
            (compatibleWireTypeID == .compatibleStruct || compatibleWireTypeID == .namedCompatibleStruct) {
            return try compatibleReader(context, self)
        }
        if compatibleTypeMeta !== typeMeta {
            return try compatibleReader(context, self)
        }
        return try reader(context)
    }
}

private struct TypeNameKey: Hashable {
    let namespace: String
    let typeName: String
}

final class TypeResolver {
    private let trackRef: Bool

    private var bySwiftType = UInt64Map<TypeInfo>(initialCapacity: 64)
    private var byUserTypeID = UInt64Map<TypeInfo>(initialCapacity: 64)
    private var byTypeName: [TypeNameKey: TypeInfo] = [:]
    private var builtinTypeInfoByID: [TypeInfo?] = []
    private var typeInfoByHeader = UInt64Map<TypeInfo>(initialCapacity: 64)

    init(trackRef: Bool = false) {
        self.trackRef = trackRef
    }

    func register<T: Serializer>(_ type: T.Type, id: UInt32) {
        do {
            try registerByID(type, id: id)
        } catch {
            preconditionFailure("conflicting registration for \(type): \(error)")
        }
    }

    @inline(__always)
    private func evolving<T: Serializer>(for type: T.Type) -> Bool {
        guard let type = type as? any StructSerializer.Type else {
            return true
        }
        return type.foryEvolving
    }

    private func registerByID<T: Serializer>(_ type: T.Type, id: UInt32) throws {
        let swiftTypeID = ObjectIdentifier(type)
        try validateIDRegistration(key: swiftTypeID, type: type, id: id)
        let evolving = evolving(for: type)

        let typeInfo = try TypeInfo(
            swiftTypeID: swiftTypeID,
            typeID: T.staticTypeId,
            userTypeID: id,
            registerByName: false,
            evolving: evolving,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            fields: T.foryFieldsInfo(trackRef: trackRef),
            reader: { context in
                try T.foryRead(context, refMode: .none, readTypeInfo: false)
            },
            compatibleReader: { context, remoteTypeInfo in
                try T.foryReadCompatibleData(context, remoteTypeInfo: remoteTypeInfo)
            }
        )

        if let existing = bySwiftType.value(for: UInt64(UInt(bitPattern: swiftTypeID))),
           existing.matches(
               typeID: T.staticTypeId,
               userTypeID: id,
               registerByName: false,
               evolving: evolving,
               namespace: "",
               typeName: ""
           ) {
            return
        }

        try store(typeInfo, for: swiftTypeID, userTypeID: id)
    }

    func register<T: Serializer>(_ type: T.Type, namespace: String, typeName: String) throws {
        let namespaceMeta = try MetaStringEncoder.namespace.encode(
            namespace,
            allowedEncodings: namespaceMetaStringEncodings
        )
        let typeNameMeta = try MetaStringEncoder.typeName.encode(
            typeName,
            allowedEncodings: typeNameMetaStringEncodings
        )
        let swiftTypeID = ObjectIdentifier(type)
        try validateNameRegistration(
            key: swiftTypeID,
            type: type,
            namespace: namespace,
            typeName: typeName
        )
        let evolving = evolving(for: type)

        let typeInfo = try TypeInfo(
            swiftTypeID: swiftTypeID,
            typeID: T.staticTypeId,
            userTypeID: nil,
            registerByName: true,
            evolving: evolving,
            namespace: namespaceMeta,
            typeName: typeNameMeta,
            fields: T.foryFieldsInfo(trackRef: trackRef),
            reader: { context in
                try T.foryRead(context, refMode: .none, readTypeInfo: false)
            },
            compatibleReader: { context, remoteTypeInfo in
                try T.foryReadCompatibleData(context, remoteTypeInfo: remoteTypeInfo)
            }
        )

        if let existing = bySwiftType.value(for: UInt64(UInt(bitPattern: swiftTypeID))),
           existing.matches(
               typeID: T.staticTypeId,
               userTypeID: nil,
               registerByName: true,
               evolving: evolving,
               namespace: namespace,
               typeName: typeName
           ) {
            return
        }

        try store(typeInfo, for: swiftTypeID, typeNameKey: TypeNameKey(namespace: namespace, typeName: typeName))
    }

    func register<T: Serializer>(_ type: T.Type, name: String) throws {
        let parts = name.components(separatedBy: ".")
        if parts.count <= 1 {
            try register(type, namespace: "", typeName: name)
            return
        }

        let resolvedTypeName = parts[parts.count - 1]
        let resolvedNamespace = parts.dropLast().joined(separator: ".")
        try register(type, namespace: resolvedNamespace, typeName: resolvedTypeName)
    }

    func requireTypeInfo<T: Serializer>(for type: T.Type) throws -> TypeInfo {
        if let info = bySwiftType.value(for: UInt64(UInt(bitPattern: ObjectIdentifier(type)))) {
            return info
        }
        throw ForyError.typeNotRegistered("\(type) is not registered")
    }

    @inline(__always)
    func getTypeInfo(forHeader header: UInt64) -> TypeInfo? {
        typeInfoByHeader.value(for: header)
    }

    @inline(__always)
    func cacheTypeInfo(_ typeMeta: TypeMeta, forHeader header: UInt64) throws -> TypeInfo {
        if let cached = typeInfoByHeader.value(for: header) {
            return cached
        }
        let localTypeInfo = try requireTypeInfo(for: typeMeta)
        if header == localTypeInfo.typeDefHeader {
            typeInfoByHeader.set(localTypeInfo, for: header)
            return localTypeInfo
        }
        let canonicalTypeMeta: TypeMeta
        if let localTypeMeta = localTypeInfo.typeMeta,
           let remapped = try? typeMeta.assigningFieldIDs(from: localTypeMeta) {
            canonicalTypeMeta = remapped
        } else {
            canonicalTypeMeta = typeMeta
        }
        let typeInfo = TypeInfo(dynamic: localTypeInfo, compatibleTypeMeta: canonicalTypeMeta)
        typeInfoByHeader.set(typeInfo, for: header)
        return typeInfo
    }

    private func store(
        _ typeInfo: TypeInfo,
        for swiftTypeID: ObjectIdentifier,
        userTypeID: UInt32? = nil,
        typeNameKey: TypeNameKey? = nil
    ) throws {
        bySwiftType.set(typeInfo, for: UInt64(UInt(bitPattern: swiftTypeID)))
        if let userTypeID {
            byUserTypeID.set(typeInfo, for: UInt64(userTypeID))
        }
        if let typeNameKey {
            byTypeName[typeNameKey] = typeInfo
        }
        if let typeMeta = typeInfo.typeMeta,
           let typeDefHeader = typeInfo.typeDefHeader {
            typeInfoByHeader.set(
                TypeInfo(
                    dynamic: typeInfo,
                    compatibleTypeMeta: typeMeta
                ),
                for: typeDefHeader
            )
        }
    }

    @inline(__always)
    func builtinTypeInfo(for typeID: TypeId) -> TypeInfo {
        let index = Int(typeID.rawValue)
        if index < builtinTypeInfoByID.count, let cached = builtinTypeInfoByID[index] {
            return cached
        }
        let info = TypeInfo(typeID: typeID)
        if index >= builtinTypeInfoByID.count {
            builtinTypeInfoByID.append(contentsOf: repeatElement(nil, count: index - builtinTypeInfoByID.count + 1))
        }
        builtinTypeInfoByID[index] = info
        return info
    }

    @inline(__always)
    func requireTypeInfo(userTypeID: UInt32) throws -> TypeInfo {
        guard let typeInfo = byUserTypeID.value(for: UInt64(userTypeID)) else {
            throw ForyError.typeNotRegistered("user_type_id=\(userTypeID)")
        }
        return typeInfo
    }

    @inline(__always)
    func requireTypeInfo(namespace: String, typeName: String) throws -> TypeInfo {
        guard let typeInfo = byTypeName[TypeNameKey(namespace: namespace, typeName: typeName)] else {
            throw ForyError.typeNotRegistered("namespace=\(namespace), type=\(typeName)")
        }
        return typeInfo
    }

    private func validateIDRegistration<T: Serializer>(
        key: ObjectIdentifier,
        type: T.Type,
        id: UInt32
    ) throws {
        let swiftKey = UInt64(UInt(bitPattern: key))
        if let existing = bySwiftType.value(for: swiftKey) {
            if existing.registerByName {
                throw ForyError.invalidData(
                    "\(type) was already registered by name, cannot re-register by id"
                )
            }
            if existing.typeID != T.staticTypeId || existing.userTypeID != id {
                let existingID = existing.userTypeID.map { String($0) } ?? "nil"
                throw ForyError.invalidData(
                    "\(type) registration conflict: existing id=\(existingID), new id=\(id)"
                )
            }
        }

        if let existing = byUserTypeID.value(for: UInt64(id)), existing.swiftTypeID != key {
            throw ForyError.invalidData("user type id \(id) is already registered by another type")
        }
    }

    private func validateNameRegistration<T: Serializer>(
        key: ObjectIdentifier,
        type: T.Type,
        namespace: String,
        typeName: String
    ) throws {
        if let existing = bySwiftType.value(for: UInt64(UInt(bitPattern: key))) {
            if !existing.registerByName {
                throw ForyError.invalidData(
                    "\(type) was already registered by id, cannot re-register by name"
                )
            }
            if existing.typeID != T.staticTypeId ||
                existing.namespace.value != namespace ||
                existing.typeName.value != typeName {
                throw ForyError.invalidData(
                    """
                    \(type) registration conflict: existing name=\(existing.namespace.value)::\(existing.typeName.value), \
                    new name=\(namespace)::\(typeName)
                    """
                )
            }
        }

        let nameKey = TypeNameKey(namespace: namespace, typeName: typeName)
        if let existing = byTypeName[nameKey], existing.swiftTypeID != key {
            throw ForyError.invalidData("type name \(namespace)::\(typeName) is already registered by another type")
        }
    }

    @inline(__always)
    private func requireTypeInfo(for typeMeta: TypeMeta) throws -> TypeInfo {
        if typeMeta.registerByName {
            guard let typeInfo = byTypeName[TypeNameKey(namespace: typeMeta.namespace.value, typeName: typeMeta.typeName.value)] else {
                throw ForyError.typeNotRegistered(
                    "namespace=\(typeMeta.namespace.value), type=\(typeMeta.typeName.value)"
                )
            }
            return typeInfo
        }
        if let userTypeID = typeMeta.userTypeID {
            guard let typeInfo = byUserTypeID.value(for: UInt64(userTypeID)) else {
                throw ForyError.typeNotRegistered("user_type_id=\(userTypeID)")
            }
            return typeInfo
        }
        throw ForyError.invalidData("missing user type id in compatible dynamic type meta")
    }

}
