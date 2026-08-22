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
        return wireTypeID == .compatibleStruct || wireTypeID == .namedCompatibleStruct
            || wireTypeID == .structType || wireTypeID == .namedStruct
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

@inline(never)
private func encodedTypeDefHeaderHash(_ bytes: [UInt8]) throws -> UInt64 {
    guard bytes.count >= 8 else {
        throw ForyError.invalidData("encoded compatible type metadata must include an 8-byte header")
    }
    let buffer = ByteBuffer(bytes: bytes)
    let header = try buffer.readUInt64()
    return typeMetaHashFromHeader(header)
}

private func fieldNeedsTypeInfo(_ fieldType: TypeMeta.FieldType) -> Bool {
    if let typeID = TypeId(rawValue: fieldType.typeID),
        TypeId.needsTypeInfoForField(typeID)
    {
        return true
    }
    return fieldType.generics.contains { fieldNeedsTypeInfo($0) }
}

private func encodedTypeDefHasUserTypeFields(_ fields: [TypeMeta.FieldInfo]) -> Bool {
    fields.contains { fieldNeedsTypeInfo($0.fieldType) }
}

@inline(never)
private func registeredTargetMismatch<S: Serializer>(
    _ value: Any,
    serializer: S.Type
) throws -> Never {
    throw ForyError.invalidData(
        "registered serializer \(serializer) targets \(S.Target.self), got \(Swift.type(of: value))")
}

@inline(never)
private func builtinTargetMismatch(_ value: Any, expected: Any.Type) throws -> Never {
    throw ForyError.invalidData(
        "dynamic target expected \(expected), got \(Swift.type(of: value))")
}

@inline(never)
private func missingSerializerTypeInfo(_ type: Any.Type) throws -> Never {
    throw ForyError.typeNotRegistered("\(type) is not registered")
}

@inline(never)
private func missingTargetTypeInfo(_ type: Any.Type) throws -> Never {
    throw ForyError.typeNotRegistered("dynamic target \(type) is not registered")
}

@inline(never)
private func missingUserTypeInfo(_ userTypeID: UInt32) throws -> Never {
    throw ForyError.typeNotRegistered("user_type_id=\(userTypeID)")
}

@inline(never)
private func missingNamedTypeInfo(namespace: String, typeName: String) throws -> Never {
    throw ForyError.typeNotRegistered("namespace=\(namespace), type=\(typeName)")
}

@inline(__always)
private func writeRegisteredValue<S: Serializer>(
    _ value: Any,
    _ context: WriteContext,
    as serializer: S.Type
) throws {
    guard let target = value as? S.Target else {
        try registeredTargetMismatch(value, serializer: serializer)
    }
    if S.isRefType {
        context.markDynamicRefStateUsed()
        try S.write(target, context, refMode: .tracking, writeTypeInfo: false)
    } else {
        try S.writeData(target, context)
    }
}

@inline(__always)
private func readRegisteredValue<S: Serializer>(
    _ context: ReadContext,
    as _: S.Type
) throws -> Any {
    if S.isRefType {
        return try S.read(context, refMode: .tracking, readTypeInfo: false)
    }
    return try S.readData(context)
}

@inline(__always)
private func readCompatibleRegisteredValue<S: Serializer>(
    _ context: ReadContext,
    as _: S.Type,
    remoteTypeInfo: TypeInfo
) throws -> Any {
    try context.withTypeInfo(remoteTypeInfo, for: S.self) {
        try readRegisteredValue(context, as: S.self)
    }
}

@inline(__always)
private func readRegisteredBody<S: Serializer>(
    _ context: ReadContext,
    as _: S.Type,
    remoteTypeInfo: TypeInfo?
) throws -> Any {
    if let remoteTypeInfo {
        return try context.withTypeInfo(remoteTypeInfo, for: S.self) {
            try S.read(context, refMode: .none, readTypeInfo: false)
        }
    }
    return try S.read(context, refMode: .none, readTypeInfo: false)
}

private func registeredBodyReader<S: Serializer>(
    for _: S.Type
) -> ((ReadContext, TypeInfo?) throws -> Any)? {
    guard S.isRefType else {
        return nil
    }
    return { context, remoteTypeInfo in
        try readRegisteredBody(context, as: S.self, remoteTypeInfo: remoteTypeInfo)
    }
}

private func registeredFields<S: Serializer>(
    for serializer: S.Type,
    trackRef: Bool,
    resolver: TypeResolver
) throws -> [TypeMeta.FieldInfo] {
    guard let structural = serializer as? any StructSerializer.Type else {
        return []
    }
    return try structural.foryFieldsInfo(trackRef: trackRef) { type in
        try resolver.fieldTypeID(for: type)
    }
}

@inline(__always)
private func registeredDynamicBoxBytes<S: Serializer>(for _: S.Type) -> Int {
    if S.isRefType {
        return 0
    }
    let inlineBytes = 3 * MemoryLayout<UnsafeRawPointer>.size
    if MemoryLayout<S.Target>.size <= inlineBytes
        && MemoryLayout<S.Target>.alignment <= MemoryLayout<UnsafeRawPointer>.alignment
    {
        return 0
    }
    return MemoryLayout<S.Target>.stride
}

public final class TypeInfo: @unchecked Sendable {
    static let uncached = TypeInfo(typeID: .unknown)

    let serializerTypeID: ObjectIdentifier
    let targetTypeID: ObjectIdentifier
    let typeID: TypeId
    let userTypeID: UInt32?
    let registerByName: Bool
    let evolving: Bool
    let namespace: MetaString
    let typeName: MetaString
    /// Finalized local metadata. Generated compatible readers use this for local field comparison;
    /// remote metadata remains exposed through `compatibleTypeMeta`.
    public private(set) var typeMeta: TypeMeta?
    public var compatibleTypeMeta: TypeMeta? { remoteCompatibleTypeMeta ?? typeMeta }
    private(set) var typeDefBytes: [UInt8]?
    public private(set) var typeDefHeaderHash: UInt64?
    public private(set) var typeDefHasUserTypeFields: Bool
    let isRefType: Bool
    let readDataAlwaysAdvances: Bool

    private let writer: (Any, WriteContext) throws -> Void
    private let reader: (ReadContext) throws -> Any
    private let compatibleReader: (ReadContext, TypeInfo) throws -> Any
    private let nativeWireTypeID: TypeId
    private let compatibleWireTypeID: TypeId
    private var typeMetaFieldsBuilder: ((TypeResolver) throws -> [TypeMeta.FieldInfo])?
    private let remoteCompatibleTypeMeta: TypeMeta?
    private let bodyReader: ((ReadContext, TypeInfo?) throws -> Any)?
    let dynamicBoxBytes: Int

    init(
        serializerTypeID: ObjectIdentifier,
        targetTypeID: ObjectIdentifier,
        typeID: TypeId,
        userTypeID: UInt32?,
        registerByName: Bool,
        evolving: Bool,
        namespace: MetaString,
        typeName: MetaString,
        typeMetaFieldsBuilder: ((TypeResolver) throws -> [TypeMeta.FieldInfo])? = nil,
        typeMeta: TypeMeta? = nil,
        compatibleTypeMeta: TypeMeta? = nil,
        typeDefBytes: [UInt8]? = nil,
        typeDefHeaderHash: UInt64? = nil,
        typeDefHasUserTypeFields: Bool = true,
        isRefType: Bool,
        readDataAlwaysAdvances: Bool = false,
        dynamicBoxBytes: Int = 0,
        writer: @escaping (Any, WriteContext) throws -> Void,
        reader: @escaping (ReadContext) throws -> Any,
        compatibleReader: @escaping (ReadContext, TypeInfo) throws -> Any,
        bodyReader: ((ReadContext, TypeInfo?) throws -> Any)? = nil
    ) {
        self.serializerTypeID = serializerTypeID
        self.targetTypeID = targetTypeID
        self.typeID = typeID
        self.userTypeID = userTypeID
        self.registerByName = registerByName
        self.evolving = evolving
        self.namespace = namespace
        self.typeName = typeName
        self.typeMetaFieldsBuilder = typeMetaFieldsBuilder
        self.remoteCompatibleTypeMeta = compatibleTypeMeta
        self.typeMeta = typeMeta
        self.typeDefBytes = typeDefBytes
        self.typeDefHeaderHash = typeDefHeaderHash
        self.typeDefHasUserTypeFields = typeDefHasUserTypeFields
        self.isRefType = isRefType
        self.readDataAlwaysAdvances = readDataAlwaysAdvances
        self.dynamicBoxBytes = dynamicBoxBytes
        self.writer = writer
        self.reader = reader
        self.compatibleReader = compatibleReader
        self.bodyReader = bodyReader
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
        serializerTypeID: ObjectIdentifier,
        targetTypeID: ObjectIdentifier,
        typeID: TypeId,
        userTypeID: UInt32?,
        registerByName: Bool,
        evolving: Bool,
        namespace: MetaString,
        typeName: MetaString,
        fields: @escaping (TypeResolver) throws -> [TypeMeta.FieldInfo],
        isRefType: Bool,
        writer: @escaping (Any, WriteContext) throws -> Void,
        reader: @escaping (ReadContext) throws -> Any,
        compatibleReader: @escaping (ReadContext, TypeInfo) throws -> Any
    ) throws {
        self.init(
            serializerTypeID: serializerTypeID,
            targetTypeID: targetTypeID,
            typeID: typeID,
            userTypeID: userTypeID,
            registerByName: registerByName,
            evolving: evolving,
            namespace: namespace,
            typeName: typeName,
            typeMetaFieldsBuilder: fields,
            typeDefHasUserTypeFields: true,
            isRefType: isRefType,
            writer: writer,
            reader: reader,
            compatibleReader: compatibleReader
        )
    }

    convenience init(typeID: TypeId) {
        self.init(
            serializerTypeID: ObjectIdentifier(TypeInfo.self),
            targetTypeID: ObjectIdentifier(TypeInfo.self),
            typeID: typeID,
            userTypeID: nil,
            registerByName: false,
            evolving: true,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            isRefType: false,
            readDataAlwaysAdvances: typeID.readDataAlwaysAdvances,
            writer: { _, _ in
                throw ForyError.invalidData("dynamic type \(typeID) uses runtime-only encode path")
            },
            reader: { _ in
                throw ForyError.invalidData("dynamic type \(typeID) uses runtime-only decode path")
            },
            compatibleReader: { _, _ in
                throw ForyError.invalidData(
                    "dynamic compatible type \(typeID) uses runtime-only decode path")
            }
        )
    }

    convenience init(dynamic typeInfo: TypeInfo, compatibleTypeMeta: TypeMeta) {
        let remoteSchemaCanChangeBody: Bool
        switch typeInfo.typeID {
        case .structType, .compatibleStruct, .namedStruct, .namedCompatibleStruct:
            remoteSchemaCanChangeBody = true
        default:
            remoteSchemaCanChangeBody = false
        }
        self.init(
            serializerTypeID: typeInfo.serializerTypeID,
            targetTypeID: typeInfo.targetTypeID,
            typeID: typeInfo.typeID,
            userTypeID: typeInfo.userTypeID,
            registerByName: typeInfo.registerByName,
            evolving: typeInfo.evolving,
            namespace: typeInfo.namespace,
            typeName: typeInfo.typeName,
            typeMeta: typeInfo.typeMeta,
            compatibleTypeMeta: compatibleTypeMeta,
            typeDefBytes: typeInfo.typeDefBytes,
            typeDefHeaderHash: typeInfo.typeDefHeaderHash,
            typeDefHasUserTypeFields: typeInfo.typeDefHasUserTypeFields,
            isRefType: typeInfo.isRefType,
            readDataAlwaysAdvances:
                remoteSchemaCanChangeBody
                ? compatibleTypeMeta.readDataAlwaysAdvances
                : typeInfo.readDataAlwaysAdvances,
            dynamicBoxBytes: typeInfo.dynamicBoxBytes,
            writer: typeInfo.writer,
            reader: typeInfo.reader,
            compatibleReader: typeInfo.compatibleReader,
            bodyReader: typeInfo.bodyReader
        )
    }

    @inline(__always)
    func matches(
        typeID: TypeId,
        userTypeID: UInt32?,
        registerByName: Bool,
        evolving: Bool,
        typeName: (namespace: String, name: String)
    ) -> Bool {
        self.typeID == typeID && self.userTypeID == userTypeID && self.registerByName == registerByName
            && self.evolving == evolving && self.namespace.value == typeName.namespace
            && self.typeName.value == typeName.name
    }

    @inline(__always)
    func wireTypeID(compatible: Bool) -> TypeId {
        compatible ? compatibleWireTypeID : nativeWireTypeID
    }

    @usableFromInline
    internal func writeTypeInfo(_ context: WriteContext) throws {
        let wireTypeID = wireTypeID(compatible: context.compatible)
        context.writeStaticTypeInfo(wireTypeID)
        switch wireTypeID {
        case .compatibleStruct, .namedCompatibleStruct:
            guard typeDefBytes != nil else {
                throw ForyError.invalidData("missing compatible type definition for \(typeID)")
            }
            try context.writeTypeMeta(self)
        case .namedEnum, .namedStruct, .namedExt, .namedUnion:
            if context.compatible {
                guard typeDefBytes != nil else {
                    throw ForyError.invalidData("missing compatible type definition for \(typeID)")
                }
                try context.writeTypeMeta(self)
            } else {
                try writeMetaString(
                    context: context,
                    value: namespace,
                    encodings: namespaceMetaStringEncodings,
                    encoder: .namespace
                )
                try writeMetaString(
                    context: context,
                    value: typeName,
                    encodings: typeNameMetaStringEncodings,
                    encoder: .typeName
                )
            }
        default:
            if !registerByName && registeredWireTypeNeedsUserTypeID(wireTypeID) {
                guard let userTypeID else {
                    throw ForyError.invalidData("missing user type id for id-registered type")
                }
                context.buffer.writeVarUInt32(userTypeID)
            }
        }
    }

    @inline(never)
    func finalizeTypeMeta(resolver: TypeResolver) throws {
        guard typeDefBytes == nil, let typeMetaFieldsBuilder else {
            return
        }
        let fields = try typeMetaFieldsBuilder(resolver)
        let typeMeta = try TypeMeta(
            typeID: compatibleWireTypeID.rawValue,
            userTypeID: registerByName ? nil : userTypeID,
            namespace: namespace,
            typeName: typeName,
            registerByName: registerByName,
            fields: fields
        )
        let typeDefBytes = try typeMeta.encode()
        let typeDefHeaderHash = try encodedTypeDefHeaderHash(typeDefBytes)
        self.typeMeta = try TypeMeta(
            typeID: compatibleWireTypeID.rawValue,
            userTypeID: registerByName ? nil : userTypeID,
            namespace: namespace,
            typeName: typeName,
            registerByName: registerByName,
            fields: fields,
            headerHash: typeDefHeaderHash
        )
        self.typeDefBytes = typeDefBytes
        self.typeDefHeaderHash = typeDefHeaderHash
        self.typeDefHasUserTypeFields = encodedTypeDefHasUserTypeFields(fields)
        self.typeMetaFieldsBuilder = nil
    }

    @inline(__always)
    func writeDynamic(_ value: Any, _ context: WriteContext) throws {
        try writer(value, context)
    }

    @inline(__always)
    func readDynamic(_ context: ReadContext, typeInfo: TypeInfo? = nil) throws -> Any {
        try context.enterReadDepth()
        if dynamicBoxBytes != 0 {
            try context.reserveGraphMemory(dynamicBoxBytes)
        }
        let value = try readValue(context, typeInfo: typeInfo)
        context.leaveReadDepth()
        return value
    }

    @inline(__always)
    func readDeclared(_ context: ReadContext) throws -> Any {
        // TypeInfo dispatch bypasses SerializerCodec, so this owner accounts for
        // the selected materialization exactly once, including compatible skips.
        if isRefType {
            let rawFlag = try context.buffer.readInt8()
            context.buffer.moveBack(1)
            if rawFlag != RefFlag.refValue.rawValue
                && rawFlag != RefFlag.notNullValue.rawValue
            {
                return try readValue(context, typeInfo: nil)
            }
        }
        try context.enterReadDepth()
        if dynamicBoxBytes != 0 {
            try context.reserveGraphMemory(dynamicBoxBytes)
        }
        let value = try readValue(context, typeInfo: nil)
        context.leaveReadDepth()
        return value
    }

    @inline(__always)
    private func readValue(_ context: ReadContext, typeInfo: TypeInfo?) throws -> Any {
        let value: Any
        if let typeInfo {
            value = try compatibleReader(context, typeInfo)
        } else if context.compatible
            && (compatibleWireTypeID == .compatibleStruct
                || compatibleWireTypeID == .namedCompatibleStruct)
        {
            value = try compatibleReader(context, self)
        } else if remoteCompatibleTypeMeta != nil {
            value = try compatibleReader(context, self)
        } else {
            value = try reader(context)
        }
        return value
    }

    @inline(__always)
    func readBody(_ context: ReadContext) throws -> Any {
        guard let bodyReader else {
            throw ForyError.invalidData("type \(typeID) has no registered body reader")
        }
        try context.enterReadDepth()
        if dynamicBoxBytes != 0 {
            try context.reserveGraphMemory(dynamicBoxBytes)
        }
        let value: Any
        if context.compatible
            && (compatibleWireTypeID == .compatibleStruct
                || compatibleWireTypeID == .namedCompatibleStruct)
        {
            value = try bodyReader(context, self)
        } else if remoteCompatibleTypeMeta != nil {
            value = try bodyReader(context, self)
        } else {
            value = try bodyReader(context, nil)
        }
        context.leaveReadDepth()
        return value
    }
}

private struct TypeNameKey: Hashable {
    let namespace: String
    let typeName: String
}

final class TypeResolver {
    private static let minRemoteTypeMetaVersions = 8192
    private static let maxRemoteTypeMetaKeys = 8192

    private let trackRef: Bool
    private var registrationFinished = false

    private var bySerializerType = UInt64Map<TypeInfo>(initialCapacity: 64)
    private var byTargetType = UInt64Map<TypeInfo>(initialCapacity: 64)
    private var byUserTypeID = UInt64Map<TypeInfo>(initialCapacity: 64)
    private var byTypeName: [TypeNameKey: TypeInfo] = [:]
    private var registeredTypeInfos: [TypeInfo] = []
    private var builtinTypeInfoByID: [TypeInfo?] = []
    // Never key this cache by the complete header: its low 12 framing bits may vary on a hit.
    private var typeInfoByHeaderHash = UInt64Map<TypeInfo>(initialCapacity: 64)
    private var remoteSchemaVersionsByType: [String: Int] = [:]
    private var totalAcceptedSchemaVersions = 0

    init(trackRef: Bool = false) {
        self.trackRef = trackRef
        seedBuiltinTypeInfos()
    }

    convenience init(config: Config) {
        self.init(trackRef: config.trackRef)
    }

    private func seedBuiltinTypeInfos() {
        seedBuiltin(Bool.self)
        seedBuiltin(Int8.self)
        seedBuiltin(Int16.self)
        seedBuiltin(Int32.self)
        seedBuiltin(Int64.self)
        seedBuiltin(UInt8.self)
        seedBuiltin(UInt16.self)
        seedBuiltin(UInt32.self)
        seedBuiltin(UInt64.self)
        #if arch(arm64) || arch(x86_64)
            seedBuiltin(Int.self, wireLookup: false)
            seedBuiltin(UInt.self, wireLookup: false)
        #endif
        seedBuiltin(Float16.self)
        seedBuiltin(BFloat16.self)
        seedBuiltin(Float.self)
        seedBuiltin(Double.self)
        seedBuiltin(String.self)
        seedBuiltin(Duration.self)
        seedBuiltin(Date.self)
        seedBuiltin(LocalDate.self)
        seedBuiltin(Decimal.self)
        seedBuiltin(Data.self)
        seedBuiltin(ForyAnyNullValue.self)

        seedWireTypeInfo(.int32) { try $0.buffer.readInt32() }
        seedWireTypeInfo(.int64) { try $0.buffer.readInt64() }
        seedWireTypeInfo(.taggedInt64) { try $0.buffer.readTaggedInt64() }
        seedWireTypeInfo(.uint32) { try $0.buffer.readUInt32() }
        seedWireTypeInfo(.uint64) { try $0.buffer.readUInt64() }
        seedWireTypeInfo(.taggedUInt64) { try $0.buffer.readTaggedUInt64() }

        seedPrimitiveArray(Bool.self, typeID: .boolArray)
        seedPrimitiveArray(Int8.self, typeID: .int8Array)
        seedPrimitiveArray(Int16.self, typeID: .int16Array)
        seedPrimitiveArray(Int32.self, typeID: .int32Array)
        seedPrimitiveArray(Int64.self, typeID: .int64Array)
        seedPrimitiveArray(UInt8.self, typeID: .uint8Array)
        seedPrimitiveArray(UInt16.self, typeID: .uint16Array)
        seedPrimitiveArray(UInt32.self, typeID: .uint32Array)
        seedPrimitiveArray(UInt64.self, typeID: .uint64Array)
        seedPrimitiveArray(Float16.self, typeID: .float16Array)
        seedPrimitiveArray(BFloat16.self, typeID: .bfloat16Array)
        seedPrimitiveArray(Float.self, typeID: .float32Array)
        seedPrimitiveArray(Double.self, typeID: .float64Array)

        seedBuiltin(
            ArraySerializer<DynamicSerializer<Any>>.self,
            serializerLookup: false
        )
        seedWireTypeInfo(.array) {
            try ArraySerializer<DynamicSerializer<Any>>.readData($0)
        }
        seedBuiltin(
            SetSerializer<AnyHashable>.self,
            serializerLookup: false
        )
        seedBuiltin(
            DictionarySerializer<AnyHashable, DynamicSerializer<Any>>.self,
            serializerLookup: false
        )
        seedBuiltin(
            DictionarySerializer<String, DynamicSerializer<Any>>.self,
            wireLookup: false,
            serializerLookup: false
        )
        seedBuiltin(
            DictionarySerializer<Int32, DynamicSerializer<Any>>.self,
            wireLookup: false,
            serializerLookup: false
        )
    }

    private func seedBuiltin<S: Serializer>(
        _ serializer: S.Type,
        wireLookup: Bool = true,
        serializerLookup: Bool = true
    ) {
        let typeInfo = TypeInfo(
            serializerTypeID: ObjectIdentifier(serializer),
            targetTypeID: ObjectIdentifier(S.Target.self),
            typeID: S.staticTypeId,
            userTypeID: nil,
            registerByName: false,
            evolving: true,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            typeDefHasUserTypeFields: false,
            isRefType: S.isRefType,
            readDataAlwaysAdvances: S.readDataAlwaysAdvances,
            dynamicBoxBytes: registeredDynamicBoxBytes(for: S.self),
            writer: { value, context in
                try writeRegisteredValue(value, context, as: S.self)
            },
            reader: { context in
                try readRegisteredValue(context, as: S.self)
            },
            compatibleReader: { context, _ in
                try readRegisteredValue(context, as: S.self)
            }
        )
        if serializerLookup {
            bySerializerType.set(
                typeInfo,
                for: UInt64(UInt(bitPattern: typeInfo.serializerTypeID))
            )
        }
        byTargetType.set(
            typeInfo,
            for: UInt64(UInt(bitPattern: typeInfo.targetTypeID))
        )
        if wireLookup {
            setBuiltinTypeInfo(typeInfo, for: S.staticTypeId)
        }
    }

    private func seedWireTypeInfo(
        _ typeID: TypeId,
        reader: @escaping (ReadContext) throws -> Any
    ) {
        let typeInfo = TypeInfo(
            serializerTypeID: ObjectIdentifier(TypeInfo.self),
            targetTypeID: ObjectIdentifier(TypeInfo.self),
            typeID: typeID,
            userTypeID: nil,
            registerByName: false,
            evolving: true,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            typeDefHasUserTypeFields: false,
            isRefType: false,
            readDataAlwaysAdvances: typeID.readDataAlwaysAdvances,
            writer: { _, _ in
                throw ForyError.invalidData("wire-only dynamic type \(typeID) cannot be written")
            },
            reader: reader,
            compatibleReader: { context, _ in try reader(context) }
        )
        setBuiltinTypeInfo(typeInfo, for: typeID)
    }

    private func seedPrimitiveArray<Element: Serializer>(
        _ element: Element.Type,
        typeID: TypeId
    ) where Element.Target == Element {
        let arrayType = [Element].self
        let typeInfo = TypeInfo(
            serializerTypeID: ObjectIdentifier(arrayType),
            targetTypeID: ObjectIdentifier(arrayType),
            typeID: typeID,
            userTypeID: nil,
            registerByName: false,
            evolving: true,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            typeDefHasUserTypeFields: false,
            isRefType: false,
            readDataAlwaysAdvances: true,
            writer: { value, context in
                guard let array = value as? [Element] else {
                    try builtinTargetMismatch(value, expected: arrayType)
                }
                writePrimitiveArray(array, context: context)
            },
            reader: { context in
                try readPrimitiveArray(context, reserveGraphStorage: true) as [Element]
            },
            compatibleReader: { context, _ in
                try readPrimitiveArray(context, reserveGraphStorage: true) as [Element]
            }
        )
        byTargetType.set(
            typeInfo,
            for: UInt64(UInt(bitPattern: typeInfo.targetTypeID))
        )
        setBuiltinTypeInfo(typeInfo, for: typeID)
    }

    private func setBuiltinTypeInfo(_ typeInfo: TypeInfo, for typeID: TypeId) {
        let index = Int(typeID.rawValue)
        if index >= builtinTypeInfoByID.count {
            builtinTypeInfoByID.append(
                contentsOf: repeatElement(nil, count: index - builtinTypeInfoByID.count + 1)
            )
        }
        builtinTypeInfoByID[index] = typeInfo
    }

    @inline(__always)
    func finishRegistration() throws {
        if registrationFinished {
            return
        }
        try finishRegistrationSlow()
    }

    @inline(never)
    private func finishRegistrationSlow() throws {
        for typeInfo in registeredTypeInfos {
            try typeInfo.finalizeTypeMeta(resolver: self)
        }
        registrationFinished = true
    }

    func register<T: Serializer>(_ type: T.Type, id: UInt32) throws {
        try registerByID(type, id: id)
    }

    @inline(__always)
    private func evolving<T: Serializer>(for type: T.Type) -> Bool {
        guard let type = type as? any StructSerializer.Type else {
            return true
        }
        return type.foryEvolving
    }

    private func registerByID<T: Serializer>(_ type: T.Type, id: UInt32) throws {
        try ensureRegistrationAllowed()
        let serializerTypeID = ObjectIdentifier(type)
        let targetTypeID = ObjectIdentifier(T.Target.self)
        try validateRegistrationCategory(type)
        try validateIDRegistration(
            serializerTypeID: serializerTypeID,
            targetTypeID: targetTypeID,
            type: type,
            id: id
        )
        let evolving = evolving(for: type)
        let trackRef = self.trackRef

        let typeInfo = TypeInfo(
            serializerTypeID: serializerTypeID,
            targetTypeID: targetTypeID,
            typeID: T.staticTypeId,
            userTypeID: id,
            registerByName: false,
            evolving: evolving,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            typeMetaFieldsBuilder: { resolver in
                try registeredFields(for: T.self, trackRef: trackRef, resolver: resolver)
            },
            isRefType: T.isRefType,
            readDataAlwaysAdvances: T.readDataAlwaysAdvances,
            dynamicBoxBytes: registeredDynamicBoxBytes(for: T.self),
            writer: { value, context in
                try writeRegisteredValue(value, context, as: T.self)
            },
            reader: { context in
                try readRegisteredValue(context, as: T.self)
            },
            compatibleReader: { context, remoteTypeInfo in
                try readCompatibleRegisteredValue(context, as: T.self, remoteTypeInfo: remoteTypeInfo)
            },
            bodyReader: registeredBodyReader(for: T.self)
        )

        if let existing = bySerializerType.value(
            for: UInt64(UInt(bitPattern: serializerTypeID))),
            existing.matches(
                typeID: T.staticTypeId,
                userTypeID: id,
                registerByName: false,
                evolving: evolving,
                typeName: (namespace: "", name: "")
            )
        {
            return
        }

        store(typeInfo, userTypeID: id)
    }

    func register<T: Serializer>(_ type: T.Type, namespace: String, typeName: String) throws {
        try ensureRegistrationAllowed()
        guard !typeName.isEmpty else {
            throw ForyError.invalidData("registered type name must not be empty")
        }
        guard !typeName.contains(".") else {
            throw ForyError.invalidData("registered type name must not contain '.'")
        }
        let namespaceMeta = try MetaStringEncoder.namespace.encode(
            namespace,
            allowedEncodings: namespaceMetaStringEncodings
        )
        let typeNameMeta = try MetaStringEncoder.typeName.encode(
            typeName,
            allowedEncodings: typeNameMetaStringEncodings
        )
        let serializerTypeID = ObjectIdentifier(type)
        let targetTypeID = ObjectIdentifier(T.Target.self)
        try validateRegistrationCategory(type)
        try validateNameRegistration(
            serializerTypeID: serializerTypeID,
            targetTypeID: targetTypeID,
            type: type,
            namespace: namespace,
            typeName: typeName
        )
        let evolving = evolving(for: type)
        let trackRef = self.trackRef

        let typeInfo = TypeInfo(
            serializerTypeID: serializerTypeID,
            targetTypeID: targetTypeID,
            typeID: T.staticTypeId,
            userTypeID: nil,
            registerByName: true,
            evolving: evolving,
            namespace: namespaceMeta,
            typeName: typeNameMeta,
            typeMetaFieldsBuilder: { resolver in
                try registeredFields(for: T.self, trackRef: trackRef, resolver: resolver)
            },
            isRefType: T.isRefType,
            readDataAlwaysAdvances: T.readDataAlwaysAdvances,
            dynamicBoxBytes: registeredDynamicBoxBytes(for: T.self),
            writer: { value, context in
                try writeRegisteredValue(value, context, as: T.self)
            },
            reader: { context in
                try readRegisteredValue(context, as: T.self)
            },
            compatibleReader: { context, remoteTypeInfo in
                try readCompatibleRegisteredValue(context, as: T.self, remoteTypeInfo: remoteTypeInfo)
            },
            bodyReader: registeredBodyReader(for: T.self)
        )

        if let existing = bySerializerType.value(
            for: UInt64(UInt(bitPattern: serializerTypeID))),
            existing.matches(
                typeID: T.staticTypeId,
                userTypeID: nil,
                registerByName: true,
                evolving: evolving,
                typeName: (namespace: namespace, name: typeName)
            )
        {
            return
        }

        store(typeInfo, typeNameKey: TypeNameKey(namespace: namespace, typeName: typeName))
    }

    func register<T: Serializer>(_ type: T.Type, name: String) throws {
        guard !name.isEmpty else {
            throw ForyError.invalidData("registered name must not be empty")
        }
        guard let separator = name.lastIndex(of: ".") else {
            try register(type, namespace: "", typeName: name)
            return
        }

        let resolvedTypeName = String(name[name.index(after: separator)...])
        let resolvedNamespace = String(name[..<separator])
        try register(type, namespace: resolvedNamespace, typeName: resolvedTypeName)
    }

    @inline(__always)
    func requireTypeInfo<T: Serializer>(for type: T.Type) throws -> TypeInfo {
        if let info = bySerializerType.value(
            for: UInt64(UInt(bitPattern: ObjectIdentifier(type))))
        {
            return info
        }
        try missingSerializerTypeInfo(type)
    }

    @inline(__always)
    func requireTypeInfo(forTarget type: Any.Type) throws -> TypeInfo {
        if let info = byTargetType.value(
            for: UInt64(UInt(bitPattern: ObjectIdentifier(type))))
        {
            return info
        }
        try missingTargetTypeInfo(type)
    }

    @inline(__always)
    func getTypeInfo(forHeaderHash headerHash: UInt64) -> TypeInfo? {
        typeInfoByHeaderHash.value(for: headerHash)
    }

    @inline(never)
    func cacheTypeInfo(
        _ typeMeta: TypeMeta,
        forHeaderHash headerHash: UInt64,
        localTypeInfo: TypeInfo,
        config: Config
    ) throws -> TypeInfo {
        if let cached = typeInfoByHeaderHash.value(for: headerHash) {
            return cached
        }
        if localTypeInfo.typeDefHeaderHash == headerHash {
            // A validated 52-bit hash is the complete schema identity. The local metadata bytes
            // may use different current-frame low bits, so byte equality must not decide ownership.
            // Local identity is not remote checked-cache state and must not be published there.
            return localTypeInfo
        }
        guard let localTypeMeta = localTypeInfo.typeMeta else {
            throw ForyError.invalidData("local type metadata for \(localTypeInfo.typeID) is not finalized")
        }
        let canonicalTypeMeta = try typeMeta.assigningFieldIDs(from: localTypeMeta)
        // Failed compatibility checks must not consult or mutate persistent remote accounting.
        let remoteSchemaKey = try checkRemoteTypeMetaLimit(typeMeta, config: config)
        let typeInfo = TypeInfo(dynamic: localTypeInfo, compatibleTypeMeta: canonicalTypeMeta)
        typeInfoByHeaderHash.set(typeInfo, for: headerHash)
        recordRemoteTypeMeta(remoteSchemaKey)
        return typeInfo
    }

    @inline(never)
    private func checkRemoteTypeMetaLimit(_ typeMeta: TypeMeta, config: Config) throws -> String {
        let key: String
        if typeMeta.registerByName {
            key = "n\(typeMeta.namespace.value)\0\(typeMeta.typeName.value)"
        } else {
            key = "i\(typeMeta.userTypeID ?? UInt32.max)"
        }

        let versionsForType = remoteSchemaVersionsByType[key] ?? 0
        let isNewType = versionsForType == 0
        let acceptedTypeCount = remoteSchemaVersionsByType.count
        // Filling the key table must not disable schema evolution for accepted logical types.
        if isNewType && acceptedTypeCount >= Self.maxRemoteTypeMetaKeys {
            throw ForyError.invalidData(
                "remote TypeMeta logical type limit exceeded. The data may be malicious"
            )
        }
        let maxSchemaVersionsPerType = config.maxSchemaVersionsPerType
        if versionsForType >= maxSchemaVersionsPerType {
            throw ForyError.invalidData(
                "remote schema version limit exceeded for one type. The data may be malicious. "
                    + "If the data is not malicious, please increase "
                    + "maxSchemaVersionsPerType=\(maxSchemaVersionsPerType)"
            )
        }
        // The preceding fixed cap proves this addition cannot overflow.
        let resultingTypeCount = acceptedTypeCount + (isNewType ? 1 : 0)
        let maxAverageSchemaVersionsPerType = config.maxAverageSchemaVersionsPerType
        if totalAcceptedSchemaVersions == Int.max
            || (totalAcceptedSchemaVersions >= Self.minRemoteTypeMetaVersions
                && totalAcceptedSchemaVersions / resultingTypeCount
                    >= maxAverageSchemaVersionsPerType)
        {
            throw ForyError.invalidData(
                "remote schema version limit exceeded globally. The data may be malicious. "
                    + "If the data is not malicious, please increase "
                    + "maxAverageSchemaVersionsPerType=\(maxAverageSchemaVersionsPerType)"
            )
        }
        return key
    }

    private func recordRemoteTypeMeta(_ key: String) {
        let versionsForType = remoteSchemaVersionsByType[key] ?? 0
        // The per-type and total checks prove both cold-path increments are representable.
        remoteSchemaVersionsByType[key] = versionsForType + 1
        totalAcceptedSchemaVersions += 1
    }

    private func store(
        _ typeInfo: TypeInfo,
        userTypeID: UInt32? = nil,
        typeNameKey: TypeNameKey? = nil
    ) {
        bySerializerType.set(
            typeInfo,
            for: UInt64(UInt(bitPattern: typeInfo.serializerTypeID))
        )
        byTargetType.set(
            typeInfo,
            for: UInt64(UInt(bitPattern: typeInfo.targetTypeID))
        )
        if let userTypeID {
            byUserTypeID.set(typeInfo, for: UInt64(userTypeID))
        }
        if let typeNameKey {
            byTypeName[typeNameKey] = typeInfo
        }
        registeredTypeInfos.append(typeInfo)
    }

    @inline(never)
    func fieldTypeID(for type: Any.Type) throws -> TypeId {
        guard let serializerType = type as? any Serializer.Type else {
            throw ForyError.invalidData("field type \(type) does not conform to Serializer")
        }
        let staticTypeID = serializerType.staticTypeId
        guard staticTypeID.isUserTypeKind else {
            return staticTypeID
        }

        if let typeInfo = bySerializerType.value(
            for: UInt64(UInt(bitPattern: ObjectIdentifier(type))))
        {
            let wireTypeID = typeInfo.wireTypeID(compatible: true)
            // Field metadata normalizes enum and union families the same way as the
            // protocol comparison rules; struct and ext keep the resolved registered kind.
            switch wireTypeID {
            case .namedEnum:
                return .enumType
            case .typedUnion, .namedUnion:
                return .union
            default:
                return wireTypeID
            }
        }

        switch staticTypeID {
        case .enumType, .namedEnum:
            return .enumType
        case .typedUnion, .namedUnion:
            return .union
        default:
            throw ForyError.typeNotRegistered("\(type) is not registered")
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
            builtinTypeInfoByID.append(
                contentsOf: repeatElement(nil, count: index - builtinTypeInfoByID.count + 1))
        }
        builtinTypeInfoByID[index] = info
        return info
    }

    @inline(__always)
    func requireTypeInfo(userTypeID: UInt32) throws -> TypeInfo {
        guard let typeInfo = byUserTypeID.value(for: UInt64(userTypeID)) else {
            try missingUserTypeInfo(userTypeID)
        }
        return typeInfo
    }

    @inline(__always)
    func requireTypeInfo(namespace: String, typeName: String) throws -> TypeInfo {
        guard let typeInfo = byTypeName[TypeNameKey(namespace: namespace, typeName: typeName)] else {
            try missingNamedTypeInfo(namespace: namespace, typeName: typeName)
        }
        return typeInfo
    }

    private func validateIDRegistration<T: Serializer>(
        serializerTypeID: ObjectIdentifier,
        targetTypeID: ObjectIdentifier,
        type: T.Type,
        id: UInt32
    ) throws {
        let serializerKey = UInt64(UInt(bitPattern: serializerTypeID))
        if let existing = bySerializerType.value(for: serializerKey) {
            if existing.registerByName {
                throw ForyError.invalidData(
                    "\(type) was already registered by name, cannot re-register by id"
                )
            }
            if existing.typeID != T.staticTypeId || existing.userTypeID != id
                || existing.targetTypeID != targetTypeID
            {
                let existingID = existing.userTypeID.map { String($0) } ?? "nil"
                throw ForyError.invalidData(
                    "\(type) registration conflict: existing id=\(existingID), new id=\(id)"
                )
            }
        }

        if let existing = byTargetType.value(for: UInt64(UInt(bitPattern: targetTypeID))),
            existing.serializerTypeID != serializerTypeID
        {
            throw ForyError.invalidData(
                "target type \(T.Target.self) is already registered by \(existing.serializerTypeID)")
        }

        if let existing = byUserTypeID.value(for: UInt64(id)),
            existing.serializerTypeID != serializerTypeID
        {
            throw ForyError.invalidData("user type id \(id) is already registered by another type")
        }
    }

    private func validateNameRegistration<T: Serializer>(
        serializerTypeID: ObjectIdentifier,
        targetTypeID: ObjectIdentifier,
        type: T.Type,
        namespace: String,
        typeName: String
    ) throws {
        if let existing = bySerializerType.value(
            for: UInt64(UInt(bitPattern: serializerTypeID)))
        {
            if !existing.registerByName {
                throw ForyError.invalidData(
                    "\(type) was already registered by id, cannot re-register by name"
                )
            }
            if existing.typeID != T.staticTypeId || existing.namespace.value != namespace
                || existing.typeName.value != typeName || existing.targetTypeID != targetTypeID
            {
                throw ForyError.invalidData(
                    """
                    \(type) registration conflict: existing name=\(existing.namespace.value)::\(existing.typeName.value), \
                    new name=\(namespace)::\(typeName)
                    """
                )
            }
        }

        if let existing = byTargetType.value(for: UInt64(UInt(bitPattern: targetTypeID))),
            existing.serializerTypeID != serializerTypeID
        {
            throw ForyError.invalidData(
                "target type \(T.Target.self) is already registered by \(existing.serializerTypeID)")
        }

        let nameKey = TypeNameKey(namespace: namespace, typeName: typeName)
        if let existing = byTypeName[nameKey],
            existing.serializerTypeID != serializerTypeID
        {
            throw ForyError.invalidData(
                "type name \(namespace)::\(typeName) is already registered by another type")
        }
    }

    @inline(never)
    private func validateRegistrationCategory<T: Serializer>(_ type: T.Type) throws {
        if T.isWrapper || type is any FieldCodec.Type {
            throw ForyError.invalidData("\(type) is a runtime-owned serializer and cannot be registered")
        }

        let typeID = normalizeRegisteredTypeID(T.staticTypeId)
        let structural = type is any StructSerializer.Type
        if structural {
            guard typeID == .structType || typeID == .enumType || typeID == .typedUnion else {
                throw ForyError.invalidData(
                    "structural serializer \(type) must use STRUCT, ENUM, or UNION type identity")
            }
            if !T.isRefType, T.Target.self is AnyObject.Type {
                throw ForyError.invalidData(
                    "value structural serializer \(type) cannot target class type \(T.Target.self)")
            }
            return
        }

        guard typeID == .ext else {
            throw ForyError.invalidData(
                "custom serializer \(type) must use EXT type identity")
        }
    }

    @inline(never)
    func requireTypeInfo(for typeMeta: TypeMeta) throws -> TypeInfo {
        if typeMeta.registerByName {
            guard
                let typeInfo = byTypeName[
                    TypeNameKey(namespace: typeMeta.namespace.value, typeName: typeMeta.typeName.value)]
            else {
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

    private func ensureRegistrationAllowed() throws {
        guard !registrationFinished else {
            throw ForyError.invalidData(
                "cannot register more types after top-level serialize/deserialize has frozen registration"
            )
        }
    }

}
