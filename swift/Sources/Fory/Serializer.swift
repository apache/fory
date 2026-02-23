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

private struct CompatibleTypeMetaCacheKey: Hashable {
    let swiftType: ObjectIdentifier
    let wireTypeID: ForyTypeId
    let trackRef: Bool
    let registerByName: Bool
    let userTypeID: UInt32?
    let namespace: MetaString?
    let typeName: MetaString
}

private struct CompatibleTypeMetaCacheEntry {
    let encodedTypeMeta: [UInt8]
}

private enum CompatibleTypeMetaCache {
    nonisolated(unsafe) static var values: [CompatibleTypeMetaCacheKey: CompatibleTypeMetaCacheEntry] = [:]
    static let lock = NSLock()
}

public protocol Serializer {
    static func foryDefault() -> Self
    static var staticTypeId: ForyTypeId { get }

    static var isNullableType: Bool { get }
    static var isReferenceTrackableType: Bool { get }

    var foryIsNone: Bool { get }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws
    static func foryReadData(_ context: ReadContext) throws -> Self

    func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws

    static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Self

    static func foryWriteTypeInfo(_ context: WriteContext) throws
    static func foryReadTypeInfo(_ context: ReadContext) throws
    static func foryCompatibleTypeMetaFields(trackRef: Bool) -> [TypeMetaFieldInfo]
    func foryWriteTypeInfo(_ context: WriteContext) throws
}

public extension Serializer {
    @inlinable
    static var isNullableType: Bool { false }

    @inlinable
    static var isReferenceTrackableType: Bool { false }

    @inlinable
    var foryIsNone: Bool { false }

    @inlinable
    static func foryCompatibleTypeMetaFields(trackRef _: Bool) -> [TypeMetaFieldInfo] {
        []
    }

    @inlinable
    func foryWriteTypeInfo(_ context: WriteContext) throws {
        try Self.foryWriteTypeInfo(context)
    }

    @inlinable
    func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        if refMode != .none {
            if refMode == .tracking, Self.isReferenceTrackableType, let object = self as AnyObject? {
                if context.refWriter.tryWriteReference(buffer: context.buffer, object: object) {
                    return
                }
            } else {
                context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try Self.foryWriteTypeInfo(context)
        }

        try foryWriteData(context, hasGenerics: hasGenerics)
    }

    @inlinable
    static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Self {
        if refMode != .none {
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return Self.foryDefault()
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRef(refID, as: Self.self)
            case .refValue:
                let reservedRefID = context.refReader.reserveRefID()
                context.pushPendingReference(reservedRefID)
                if readTypeInfo {
                    try Self.foryReadTypeInfo(context)
                }
                let value = try Self.foryReadData(context)
                context.finishPendingReferenceIfNeeded(value)
                context.popPendingReference()
                return value
            case .notNullValue:
                break
            }
        }

        if readTypeInfo {
            try Self.foryReadTypeInfo(context)
        }
        return try Self.foryReadData(context)
    }

    static func foryWriteTypeInfo(_ context: WriteContext) throws {
        guard staticTypeId.isUserTypeKind else {
            context.buffer.writeUInt8(UInt8(truncatingIfNeeded: staticTypeId.rawValue))
            return
        }

        let info = try context.typeResolver.requireRegisteredTypeInfo(for: Self.self)
        let wireTypeID = resolveWireTypeID(
            declaredKind: info.kind,
            registerByName: info.registerByName,
            compatible: context.compatible
        )
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: wireTypeID.rawValue))
        switch wireTypeID {
        case .compatibleStruct, .namedCompatibleStruct:
            let cachedTypeMeta = try compatibleTypeMetaEntry(
                info: info,
                wireTypeID: wireTypeID,
                trackRef: context.trackRef
            )
            try context.writeCompatibleTypeMeta(
                for: Self.self,
                encodedTypeMeta: cachedTypeMeta.encodedTypeMeta
            )
        case .namedEnum, .namedStruct, .namedExt, .namedUnion:
            if context.compatible {
                let cachedTypeMeta = try compatibleTypeMetaEntry(
                    info: info,
                    wireTypeID: wireTypeID,
                    trackRef: context.trackRef
                )
                try context.writeCompatibleTypeMeta(
                    for: Self.self,
                    encodedTypeMeta: cachedTypeMeta.encodedTypeMeta
                )
            } else {
                guard let namespace = info.namespace else {
                    throw ForyError.invalidData("missing namespace metadata for name-registered type")
                }
                try writeMetaString(
                    context: context,
                    value: namespace,
                    encodings: namespaceMetaStringEncodings,
                    encoder: .namespace
                )
                try writeMetaString(
                    context: context,
                    value: info.typeName,
                    encodings: typeNameMetaStringEncodings,
                    encoder: .typeName
                )
            }
        default:
            if !info.registerByName && wireTypeNeedsUserTypeID(wireTypeID) {
                guard let userTypeID = info.userTypeID else {
                    throw ForyError.invalidData("missing user type id for id-registered type")
                }
                context.buffer.writeVarUInt32(userTypeID)
            }
        }
    }

    static func foryReadTypeInfo(_ context: ReadContext) throws {
        let rawTypeID = try context.buffer.readVarUInt32()
        guard let typeID = ForyTypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown type id \(rawTypeID)")
        }

        guard staticTypeId.isUserTypeKind else {
            if typeID != staticTypeId {
                throw ForyError.typeMismatch(expected: staticTypeId.rawValue, actual: rawTypeID)
            }
            return
        }

        let info = try context.typeResolver.requireRegisteredTypeInfo(for: Self.self)
        let allowed = allowedWireTypeIDs(
            declaredKind: info.kind,
            registerByName: info.registerByName,
            compatible: context.compatible
        )
        if !allowed.contains(typeID) {
            if let expected = allowed.first {
                throw ForyError.typeMismatch(expected: expected.rawValue, actual: rawTypeID)
            }
            throw ForyError.invalidData("no expected wire type ids for \(info.kind)")
        }

        switch typeID {
        case .compatibleStruct, .namedCompatibleStruct:
            let remoteTypeMeta = try context.readCompatibleTypeMeta()
            try validateCompatibleTypeMeta(
                remoteTypeMeta,
                localInfo: info,
                expectedWireTypes: allowed,
                actualWireTypeID: typeID
            )
            context.pushCompatibleTypeMeta(for: Self.self, remoteTypeMeta)
        case .namedEnum, .namedStruct, .namedExt, .namedUnion:
            if context.compatible {
                let remoteTypeMeta = try context.readCompatibleTypeMeta()
                try validateCompatibleTypeMeta(
                    remoteTypeMeta,
                    localInfo: info,
                    expectedWireTypes: allowed,
                    actualWireTypeID: typeID
                )
                if typeID == .namedStruct {
                    context.pushCompatibleTypeMeta(for: Self.self, remoteTypeMeta)
                }
            } else {
                let namespace = try readMetaString(
                    context: context,
                    decoder: .namespace,
                    encodings: namespaceMetaStringEncodings
                )
                let typeName = try readMetaString(
                    context: context,
                    decoder: .typeName,
                    encodings: typeNameMetaStringEncodings
                )
                guard info.registerByName else {
                    throw ForyError.invalidData("received name-registered type info for id-registered local type")
                }
                guard let localNamespace = info.namespace else {
                    throw ForyError.invalidData("missing local namespace metadata for name-registered type")
                }
                if namespace.value != localNamespace.value || typeName.value != info.typeName.value {
                    throw ForyError.invalidData(
                        "type name mismatch: expected \(localNamespace.value)::\(info.typeName.value), got \(namespace.value)::\(typeName.value)"
                    )
                }
            }
        default:
            if !info.registerByName && wireTypeNeedsUserTypeID(typeID) {
                guard let localUserTypeID = info.userTypeID else {
                    throw ForyError.invalidData("missing user type id for id-registered type")
                }
                let remoteUserTypeID = try context.buffer.readVarUInt32()
                if remoteUserTypeID != localUserTypeID {
                    throw ForyError.typeMismatch(expected: localUserTypeID, actual: remoteUserTypeID)
                }
            }
        }
    }

    private static func normalizeBaseKind(_ kind: ForyTypeId) -> ForyTypeId {
        switch kind {
        case .namedEnum:
            return .enumType
        case .compatibleStruct, .namedCompatibleStruct, .namedStruct:
            return .structType
        case .namedExt:
            return .ext
        case .namedUnion:
            return .typedUnion
        default:
            return kind
        }
    }

    private static func namedKind(for baseKind: ForyTypeId, compatible: Bool) -> ForyTypeId {
        switch baseKind {
        case .structType:
            return compatible ? .namedCompatibleStruct : .namedStruct
        case .enumType:
            return .namedEnum
        case .ext:
            return .namedExt
        case .typedUnion:
            return .namedUnion
        default:
            return baseKind
        }
    }

    private static func idKind(for baseKind: ForyTypeId, compatible: Bool) -> ForyTypeId {
        switch baseKind {
        case .structType:
            return compatible ? .compatibleStruct : .structType
        default:
            return baseKind
        }
    }

    private static func resolveWireTypeID(
        declaredKind: ForyTypeId,
        registerByName: Bool,
        compatible: Bool
    ) -> ForyTypeId {
        let baseKind = normalizeBaseKind(declaredKind)
        if registerByName {
            return namedKind(for: baseKind, compatible: compatible)
        }
        return idKind(for: baseKind, compatible: compatible)
    }

    private static func allowedWireTypeIDs(
        declaredKind: ForyTypeId,
        registerByName: Bool,
        compatible: Bool
    ) -> Set<ForyTypeId> {
        let baseKind = normalizeBaseKind(declaredKind)
        let expected = resolveWireTypeID(
            declaredKind: declaredKind,
            registerByName: registerByName,
            compatible: compatible
        )
        var allowed: Set<ForyTypeId> = [expected]
        if baseKind == .structType, compatible {
            // Be permissive across peers while struct compatibility converges.
            allowed.insert(.compatibleStruct)
            allowed.insert(.namedCompatibleStruct)
            allowed.insert(.structType)
            allowed.insert(.namedStruct)
        }
        return allowed
    }

    private static func wireTypeNeedsUserTypeID(_ typeID: ForyTypeId) -> Bool {
        switch typeID {
        case .enumType, .structType, .ext, .typedUnion:
            return true
        default:
            return false
        }
    }

    private static func compatibleTypeMetaEntry(
        info: RegisteredTypeInfo,
        wireTypeID: ForyTypeId,
        trackRef: Bool
    ) throws -> CompatibleTypeMetaCacheEntry {
        let cacheKey = CompatibleTypeMetaCacheKey(
            swiftType: ObjectIdentifier(Self.self),
            wireTypeID: wireTypeID,
            trackRef: trackRef,
            registerByName: info.registerByName,
            userTypeID: info.userTypeID,
            namespace: info.namespace,
            typeName: info.typeName
        )

        CompatibleTypeMetaCache.lock.lock()
        if let cached = CompatibleTypeMetaCache.values[cacheKey] {
            CompatibleTypeMetaCache.lock.unlock()
            return cached
        }
        CompatibleTypeMetaCache.lock.unlock()

        let typeMeta = try buildCompatibleTypeMeta(
            info: info,
            wireTypeID: wireTypeID,
            trackRef: trackRef
        )
        let cacheEntry = CompatibleTypeMetaCacheEntry(
            encodedTypeMeta: try typeMeta.encode()
        )

        CompatibleTypeMetaCache.lock.lock()
        if let cached = CompatibleTypeMetaCache.values[cacheKey] {
            CompatibleTypeMetaCache.lock.unlock()
            return cached
        }
        CompatibleTypeMetaCache.values[cacheKey] = cacheEntry
        CompatibleTypeMetaCache.lock.unlock()
        return cacheEntry
    }

    private static func buildCompatibleTypeMeta(
        info: RegisteredTypeInfo,
        wireTypeID: ForyTypeId,
        trackRef: Bool
    ) throws -> TypeMeta {
        let fields = foryCompatibleTypeMetaFields(trackRef: trackRef)
        let hasFieldsMeta = !fields.isEmpty
        if info.registerByName {
            guard let namespace = info.namespace else {
                throw ForyError.invalidData("missing namespace metadata for name-registered type")
            }
            return try TypeMeta(
                typeID: wireTypeID.rawValue,
                userTypeID: nil,
                namespace: namespace,
                typeName: info.typeName,
                registerByName: true,
                fields: fields,
                hasFieldsMeta: hasFieldsMeta
            )
        }

        guard let userTypeID = info.userTypeID else {
            throw ForyError.invalidData("missing user type id metadata for id-registered type")
        }
        return try TypeMeta(
            typeID: wireTypeID.rawValue,
            userTypeID: userTypeID,
            namespace: MetaString.empty(specialChar1: ".", specialChar2: "_"),
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_"),
            registerByName: false,
            fields: fields,
            hasFieldsMeta: hasFieldsMeta
        )
    }

    private static func validateCompatibleTypeMeta(
        _ remoteTypeMeta: TypeMeta,
        localInfo: RegisteredTypeInfo,
        expectedWireTypes: Set<ForyTypeId>,
        actualWireTypeID: ForyTypeId
    ) throws {
        if remoteTypeMeta.registerByName {
            guard localInfo.registerByName else {
                throw ForyError.invalidData("received name-registered compatible metadata for id-registered local type")
            }
            guard let localNamespace = localInfo.namespace else {
                throw ForyError.invalidData("missing local namespace metadata for name-registered type")
            }
            if remoteTypeMeta.namespace.value != localNamespace.value {
                throw ForyError.invalidData(
                    "namespace mismatch: expected \(localNamespace.value), got \(remoteTypeMeta.namespace.value)"
                )
            }
            if remoteTypeMeta.typeName.value != localInfo.typeName.value {
                throw ForyError.invalidData(
                    "type name mismatch: expected \(localInfo.typeName.value), got \(remoteTypeMeta.typeName.value)"
                )
            }
        } else {
            guard !localInfo.registerByName else {
                throw ForyError.invalidData("received id-registered compatible metadata for name-registered local type")
            }
            guard let remoteUserTypeID = remoteTypeMeta.userTypeID else {
                throw ForyError.invalidData("missing user type id in compatible type metadata")
            }
            guard let localUserTypeID = localInfo.userTypeID else {
                throw ForyError.invalidData("missing local user type id metadata for id-registered type")
            }
            if remoteUserTypeID != localUserTypeID {
                throw ForyError.typeMismatch(expected: localUserTypeID, actual: remoteUserTypeID)
            }
        }

        if let remoteTypeID = remoteTypeMeta.typeID,
           let remoteWireTypeID = ForyTypeId(rawValue: remoteTypeID),
           !expectedWireTypes.contains(remoteWireTypeID) {
            throw ForyError.typeMismatch(expected: actualWireTypeID.rawValue, actual: remoteTypeID)
        }
    }

    private static func writeMetaString(
        context: WriteContext,
        value: MetaString,
        encodings: [MetaStringEncoding],
        encoder: MetaStringEncoder
    ) throws {
        let normalized: MetaString
        if encodings.contains(value.encoding) {
            normalized = value
        } else {
            normalized = try encoder.encode(value.value, allowedEncodings: encodings)
        }

        guard encodings.contains(normalized.encoding) else {
            throw ForyError.encodingError("failed to normalize meta string encoding")
        }

        let bytes = normalized.bytes
        let assignment = context.metaStringWriteState.assignIndexIfAbsent(for: normalized)
        if assignment.isNew {
            context.buffer.writeVarUInt32(UInt32(bytes.count) << 1)
            if bytes.count > 16 {
                context.buffer.writeInt64(Int64(bitPattern: javaMetaStringHash(metaString: normalized)))
            } else if !bytes.isEmpty {
                context.buffer.writeUInt8(normalized.encoding.rawValue)
            }
            context.buffer.writeBytes(bytes)
        } else {
            context.buffer.writeVarUInt32(((assignment.index + 1) << 1) | 1)
        }
    }

    private static func readMetaString(
        context: ReadContext,
        decoder: MetaStringDecoder,
        encodings: [MetaStringEncoding]
    ) throws -> MetaString {
        let header = try context.buffer.readVarUInt32()
        let length = Int(header >> 1)
        let isRef = (header & 1) == 1
        if isRef {
            let index = length - 1
            guard let cached = context.metaStringReadState.value(at: index) else {
                throw ForyError.invalidData("unknown meta string ref index \(index)")
            }
            return cached
        }

        let value: MetaString
        if length == 0 {
            value = MetaString.empty(
                specialChar1: decoder.specialChar1,
                specialChar2: decoder.specialChar2
            )
        } else {
            let encoding: MetaStringEncoding
            if length > 16 {
                let hash = try context.buffer.readInt64()
                let rawEncoding = UInt8(truncatingIfNeeded: hash & 0xFF)
                guard let resolved = MetaStringEncoding(rawValue: rawEncoding) else {
                    throw ForyError.invalidData("invalid meta string encoding \(rawEncoding)")
                }
                encoding = resolved
            } else {
                let rawEncoding = try context.buffer.readUInt8()
                guard let resolved = MetaStringEncoding(rawValue: rawEncoding) else {
                    throw ForyError.invalidData("invalid meta string encoding \(rawEncoding)")
                }
                encoding = resolved
            }
            guard encodings.contains(encoding) else {
                throw ForyError.invalidData("meta string encoding \(encoding) not allowed in this context")
            }
            let bytes = try context.buffer.readBytes(count: length)
            value = try decoder.decode(bytes: bytes, encoding: encoding)
        }
        context.metaStringReadState.append(value)
        return value
    }

    private static func javaMetaStringHash(metaString: MetaString) -> UInt64 {
        var hash = Int64(bitPattern: MurmurHash3.x64_128(metaString.bytes, seed: 47).0)
        if hash != Int64.min {
            hash = Swift.abs(hash)
        }
        var result = UInt64(bitPattern: hash)
        if result == 0 {
            result &+= 256
        }
        result &= 0xffffffffffffff00
        result |= UInt64(metaString.encoding.rawValue & 0xFF)
        return result
    }
}
