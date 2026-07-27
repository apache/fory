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

private let typeMetaSizeMask = 0xFF

@inline(never)
private func invalidReadDynamicDepth(_ maxDepth: Int) throws -> Never {
    throw ForyError.invalidData("configured maxDepth \(maxDepth) is negative")
}

@inline(never)
private func readDynamicDepthExceeded(_ depth: Int, maxDepth: Int) throws -> Never {
    throw ForyError.invalidData(
        "dynamic Any nesting depth \(depth) exceeds configured maxDepth \(maxDepth)")
}

public final class ReadContext {
    public let buffer: ByteBuffer
    let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let checkClassVersion: Bool
    public let maxDepth: Int
    public let refReader: RefReader
    private let compatibleTypeDefTypeInfos = ReusableArray<TypeInfo?>(defaultValue: nil, reserve: 2)
    private let metaStrings = ReusableArray<MetaString?>(defaultValue: nil, reserve: 16)
    private var dynamicAnyDepth = 0

    private var typeInfoStack = UInt64Map<TypeInfo>(initialCapacity: 8)
    private var typeInfoScopeStack: [(typeKey: UInt64, previousTypeInfo: TypeInfo?)] = []
    private var lastTypeInfo = TypeInfo.uncached
    private let config: Config
    var remainingGraphMemoryBytes = 0

    init(
        buffer: ByteBuffer,
        typeResolver: TypeResolver,
        config: Config
    ) {
        self.buffer = buffer
        self.typeResolver = typeResolver
        self.trackRef = config.trackRef
        self.compatible = config.compatible
        self.checkClassVersion = config.checkClassVersion
        self.maxDepth = config.maxDepth
        self.config = config
        self.refReader = RefReader()
    }

    @inline(__always)
    public func reserveGraphMemory(_ bytes: Int) throws {
        if _slowPath(bytes < 0) {
            try throwGraphMemoryOverflow()
        }
        if _slowPath(bytes > remainingGraphMemoryBytes) {
            try throwGraphMemoryExceeded(bytes: bytes)
        }
        remainingGraphMemoryBytes -= bytes
    }

    @inline(never)
    private func throwGraphMemoryOverflow() throws -> Never {
        throw ForyError.invalidData("graph memory estimate overflows")
    }

    @inline(never)
    private func throwGraphMemoryExceeded(bytes: Int) throws -> Never {
        let message =
            "estimated graph memory request \(bytes) bytes exceeds maxGraphMemoryBytes "
            + "remaining budget \(remainingGraphMemoryBytes) bytes"
        throw ForyError.invalidData(message)
    }

    @inline(__always)
    func enterDynamicAnyDepth() throws {
        if maxDepth < 0 {
            try invalidReadDynamicDepth(maxDepth)
        }
        let nextDepth = dynamicAnyDepth + 1
        if nextDepth > maxDepth {
            try readDynamicDepthExceeded(nextDepth, maxDepth: maxDepth)
        }
        dynamicAnyDepth = nextDepth
    }

    @inline(__always)
    func leaveDynamicAnyDepth() {
        if dynamicAnyDepth > 0 {
            dynamicAnyDepth -= 1
        }
    }

    @usableFromInline
    @inline(__always)
    internal func ensureCollectionLength(_ length: Int, label: String) throws {
        if length < 0 {
            throw invalidCollectionLength(label: label)
        }
    }

    @usableFromInline
    @inline(__always)
    internal func ensureRemainingBytes(_ byteCount: Int, label: String) throws {
        if byteCount < 0 {
            throw invalidRemainingByteCount(label: label)
        }
        let remainingBytes = buffer.remaining
        if byteCount > remainingBytes {
            throw insufficientRemainingBytes(
                byteCount,
                remaining: remainingBytes,
                label: label
            )
        }
    }

    @usableFromInline
    @inline(never)
    internal func invalidCollectionLength(label: String) -> ForyError {
        ForyError.invalidData("\(label) length is negative")
    }

    @usableFromInline
    @inline(never)
    internal func invalidRemainingByteCount(label: String) -> ForyError {
        ForyError.invalidData("\(label) size is negative")
    }

    @usableFromInline
    @inline(never)
    internal func insufficientRemainingBytes(
        _ byteCount: Int,
        remaining: Int,
        label: String
    ) -> ForyError {
        ForyError.invalidData(
            "\(label) requires \(byteCount) bytes but only \(remaining) remain in buffer"
        )
    }

    @inline(__always)
    func typeInfo<T: Serializer>(for type: T.Type) throws -> TypeInfo {
        let typeID = ObjectIdentifier(type)
        if lastTypeInfo.serializerTypeID == typeID {
            return lastTypeInfo
        }
        let info = try typeResolver.requireTypeInfo(for: type)
        lastTypeInfo = info
        return info
    }

    @inline(__always)
    func readStaticTypeInfo(_ typeID: TypeId) throws -> TypeInfo? {
        let rawTypeID = UInt32(try buffer.readUInt8())
        guard let actualTypeID = TypeId(rawValue: rawTypeID) else {
            throw unknownStaticTypeID(rawTypeID)
        }
        if actualTypeID != typeID {
            throw staticTypeMismatch(expected: typeID.rawValue, actual: rawTypeID)
        }
        return nil
    }

    func readTypeInfo() throws -> TypeInfo {
        let rawTypeID = UInt32(try buffer.readUInt8())
        guard let wireTypeID = TypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown dynamic type id \(rawTypeID)")
        }

        switch wireTypeID {
        case .compatibleStruct, .namedCompatibleStruct:
            return try readCompatibleTypeInfo()
        case .namedEnum, .namedStruct, .namedExt, .namedUnion:
            if compatible {
                return try readCompatibleTypeInfo()
            }
            let namespace = try readMetaString(
                context: self,
                decoder: .namespace,
                encodings: namespaceMetaStringEncodings
            )
            let typeName = try readMetaString(
                context: self,
                decoder: .typeName,
                encodings: typeNameMetaStringEncodings
            )
            return try typeResolver.requireTypeInfo(namespace: namespace.value, typeName: typeName.value)
        case .structType, .enumType, .ext, .typedUnion, .union:
            let userTypeID = try buffer.readVarUInt32()
            return try typeResolver.requireTypeInfo(userTypeID: userTypeID)
        default:
            return typeResolver.builtinTypeInfo(for: wireTypeID)
        }
    }

    func readTypeInfo<T: Serializer>(for type: T.Type) throws -> TypeInfo? {
        let rawTypeID = UInt32(try buffer.readUInt8())
        guard let typeID = TypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown type id \(rawTypeID)")
        }

        guard T.staticTypeId.isUserTypeKind else {
            if typeID != T.staticTypeId {
                throw ForyError.typeMismatch(expected: T.staticTypeId.rawValue, actual: rawTypeID)
            }
            return nil
        }

        let localTypeInfo = try typeInfo(for: type)
        let expectedWireTypeID = localTypeInfo.wireTypeID(compatible: compatible)
        if !isAllowedRegisteredWireTypeID(
            typeID,
            declaredTypeID: localTypeInfo.typeID,
            registerByName: localTypeInfo.registerByName,
            compatible: compatible,
            evolving: localTypeInfo.evolving
        ) {
            throw ForyError.typeMismatch(expected: expectedWireTypeID.rawValue, actual: rawTypeID)
        }

        switch typeID {
        case .compatibleStruct, .namedCompatibleStruct:
            return try readCompatibleTypeInfoIfNeeded(
                for: localTypeInfo,
                wireTypeID: typeID
            )
        case .namedEnum, .namedStruct, .namedExt, .namedUnion:
            if compatible {
                _ = try readCompatibleTypeInfoIfNeeded(
                    for: localTypeInfo,
                    wireTypeID: typeID
                )
            } else {
                let namespace = try readMetaString(
                    context: self,
                    decoder: .namespace,
                    encodings: namespaceMetaStringEncodings
                )
                let typeName = try readMetaString(
                    context: self,
                    decoder: .typeName,
                    encodings: typeNameMetaStringEncodings
                )
                guard localTypeInfo.registerByName else {
                    throw ForyError.invalidData(
                        "received name-registered type info for id-registered local type")
                }
                if namespace.value != localTypeInfo.namespace.value
                    || typeName.value != localTypeInfo.typeName.value
                {
                    let expectedTypeName = "\(localTypeInfo.namespace.value)::\(localTypeInfo.typeName.value)"
                    let actualTypeName = "\(namespace.value)::\(typeName.value)"
                    throw ForyError.invalidData(
                        "type name mismatch: expected \(expectedTypeName), got \(actualTypeName)"
                    )
                }
            }
        default:
            if !localTypeInfo.registerByName && registeredWireTypeNeedsUserTypeID(typeID) {
                guard let localUserTypeID = localTypeInfo.userTypeID else {
                    throw ForyError.invalidData("missing user type id for id-registered type")
                }
                let remoteUserTypeID = try buffer.readVarUInt32()
                if remoteUserTypeID != localUserTypeID {
                    throw ForyError.typeMismatch(expected: localUserTypeID, actual: remoteUserTypeID)
                }
            }
        }
        return nil
    }

    @inline(__always)
    private func readCompatibleTypeInfoIfNeeded(
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws -> TypeInfo? {
        let buffer = self.buffer
        let compatibleTypeDefTypeInfos = self.compatibleTypeDefTypeInfos
        if !checkClassVersion,
            compatibleTypeDefTypeInfos.isEmpty,
            !localTypeInfo.typeDefHasUserTypeFields,
            let localTypeDefHeader = localTypeInfo.typeDefHeader
        {
            let indexMarker = try buffer.readVarUInt32()
            if indexMarker == 0 {
                let headerStart = buffer.getCursor()
                let header = try buffer.readUInt64()
                var bodySize = Int(header & UInt64(typeMetaSizeMask))
                if bodySize == typeMetaSizeMask {
                    bodySize += Int(try buffer.readVarUInt32())
                }
                if header == localTypeDefHeader {
                    // The declared local type owns this exact metadata header, so this is a
                    // local-schema hit rather than a remote cache publish. Keep it allocation-free:
                    // skip the body, add the local type to the per-read table, and do not parse/hash.
                    try buffer.skip(bodySize)
                    compatibleTypeDefTypeInfos.push(localTypeInfo)
                    return nil
                }
                if let cached = typeResolver.getTypeInfo(forHeader: header) {
                    // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
                    // after a successful TypeDef parse and 52-bit metadata-hash validation. Do not add
                    // body/hash/schema-limit/exact-local checks here; the miss path owns them before publish.
                    try buffer.skip(bodySize)
                    compatibleTypeDefTypeInfos.push(cached)
                    return try validateCompatibleTypeInfo(cached, for: localTypeInfo, wireTypeID: wireTypeID)
                }
                let cachedTypeInfo = try readTypeInfoBody(
                    start: headerStart,
                    header: header,
                    for: localTypeInfo,
                    wireTypeID: wireTypeID)
                compatibleTypeDefTypeInfos.push(cachedTypeInfo)
                if cachedTypeInfo === localTypeInfo {
                    return nil
                }
                return try validateCompatibleTypeInfo(
                    cachedTypeInfo, for: localTypeInfo, wireTypeID: wireTypeID)
            }
            return try readCompatibleTypeInfo(
                afterMarker: indexMarker,
                for: localTypeInfo,
                wireTypeID: wireTypeID)
        }
        return try readCompatibleTypeInfo(
            for: localTypeInfo,
            wireTypeID: wireTypeID
        )
    }

    private func readCompatibleTypeInfo() throws -> TypeInfo {
        let indexMarker = try buffer.readVarUInt32()
        return try readCompatibleTypeInfo(afterMarker: indexMarker)
    }

    private func readCompatibleTypeInfo(afterMarker indexMarker: UInt32) throws -> TypeInfo {
        let buffer = self.buffer
        let compatibleTypeDefTypeInfos = self.compatibleTypeDefTypeInfos
        let isRef = (indexMarker & 1) == 1
        let index = Int(indexMarker >> 1)
        if isRef {
            guard let typeInfo = compatibleTypeDefTypeInfos.get(index) else {
                throw ForyError.invalidData("unknown compatible type definition ref index \(index)")
            }
            return typeInfo
        }

        let typeMetaStart = buffer.getCursor()
        let header = try buffer.readUInt64()
        var bodySize = Int(header & UInt64(typeMetaSizeMask))
        if bodySize == typeMetaSizeMask {
            bodySize += Int(try buffer.readVarUInt32())
        }
        if let cached = typeResolver.getTypeInfo(forHeader: header) {
            // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
            // after a successful TypeDef parse and 52-bit metadata-hash validation. Do not add
            // body/hash/schema-limit/exact-local checks here; the miss path owns them before publish.
            try buffer.skip(bodySize)
            compatibleTypeDefTypeInfos.push(cached)
            return cached
        }

        let cachedTypeInfo = try readTypeInfoBody(start: typeMetaStart, header: header)
        compatibleTypeDefTypeInfos.push(cachedTypeInfo)
        return cachedTypeInfo
    }

    @inline(never)
    private func readCompatibleTypeInfo(
        afterMarker indexMarker: UInt32,
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws -> TypeInfo {
        let buffer = self.buffer
        let compatibleTypeDefTypeInfos = self.compatibleTypeDefTypeInfos
        let isRef = (indexMarker & 1) == 1
        let index = Int(indexMarker >> 1)
        if isRef {
            guard let typeInfo = compatibleTypeDefTypeInfos.get(index) else {
                throw ForyError.invalidData("unknown compatible type definition ref index \(index)")
            }
            return try validateCompatibleTypeInfo(typeInfo, for: localTypeInfo, wireTypeID: wireTypeID)
        }

        let typeMetaStart = buffer.getCursor()
        let header = try buffer.readUInt64()
        var bodySize = Int(header & UInt64(typeMetaSizeMask))
        if bodySize == typeMetaSizeMask {
            bodySize += Int(try buffer.readVarUInt32())
        }
        if let cached = typeResolver.getTypeInfo(forHeader: header) {
            // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
            // after a successful TypeDef parse and 52-bit metadata-hash validation. Do not add
            // body/hash/schema-limit/exact-local checks here; the miss path owns them before publish.
            try buffer.skip(bodySize)
            compatibleTypeDefTypeInfos.push(cached)
            return try validateCompatibleTypeInfo(cached, for: localTypeInfo, wireTypeID: wireTypeID)
        }

        let cachedTypeInfo = try readTypeInfoBody(
            start: typeMetaStart,
            header: header,
            for: localTypeInfo,
            wireTypeID: wireTypeID)
        compatibleTypeDefTypeInfos.push(cachedTypeInfo)
        return try validateCompatibleTypeInfo(
            cachedTypeInfo, for: localTypeInfo, wireTypeID: wireTypeID)
    }

    @inline(__always)
    private func readCompatibleTypeInfo(
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws -> TypeInfo {
        let buffer = self.buffer
        let compatibleTypeDefTypeInfos = self.compatibleTypeDefTypeInfos
        if compatibleTypeDefTypeInfos.isEmpty,
            let localTypeDefHeader = localTypeInfo.typeDefHeader
        {
            let indexMarker = try buffer.readVarUInt32()
            if indexMarker != 0 {
                return try readCompatibleTypeInfo(
                    afterMarker: indexMarker,
                    for: localTypeInfo,
                    wireTypeID: wireTypeID)
            } else {
                let headerStart = buffer.getCursor()
                let header = try buffer.readUInt64()
                var bodySize = Int(header & UInt64(typeMetaSizeMask))
                if bodySize == typeMetaSizeMask {
                    bodySize += Int(try buffer.readVarUInt32())
                }

                if header == localTypeDefHeader {
                    // The declared local type owns this exact metadata header, so this is a
                    // local-schema hit rather than a remote cache publish. Keep it allocation-free:
                    // skip the body, add the local type to the per-read table, and do not parse/hash.
                    try buffer.skip(bodySize)
                    compatibleTypeDefTypeInfos.push(localTypeInfo)
                    return localTypeInfo
                }

                if let cached = typeResolver.getTypeInfo(forHeader: header) {
                    // Header-cache hits intentionally skip without rehashing. Entries reach this cache only
                    // after a successful TypeDef parse and 52-bit metadata-hash validation. Do not add
                    // body/hash/schema-limit/exact-local checks here; the miss path owns them before publish.
                    try buffer.skip(bodySize)
                    compatibleTypeDefTypeInfos.push(cached)
                    return try validateCompatibleTypeInfo(cached, for: localTypeInfo, wireTypeID: wireTypeID)
                } else {
                    let remoteTypeInfo = try readTypeInfoBody(
                        start: headerStart,
                        header: header,
                        for: localTypeInfo,
                        wireTypeID: wireTypeID)
                    compatibleTypeDefTypeInfos.push(remoteTypeInfo)
                    return try validateCompatibleTypeInfo(
                        remoteTypeInfo, for: localTypeInfo, wireTypeID: wireTypeID)
                }
            }
        }
        let indexMarker = try buffer.readVarUInt32()
        return try readCompatibleTypeInfo(
            afterMarker: indexMarker,
            for: localTypeInfo,
            wireTypeID: wireTypeID)
    }

    @inline(never)
    private func readTypeInfoBody(start: Int, header: UInt64) throws -> TypeInfo {
        buffer.setCursor(start)
        let decoded = try TypeMeta.decode(
            buffer,
            maxTypeFields: config.maxTypeFields,
            maxTypeMetaBytes: config.maxTypeMetaBytes)
        let typeMetaEnd = buffer.getCursor()
        let localTypeInfo = try typeResolver.requireTypeInfo(for: decoded)
        return try typeResolver.cacheTypeInfo(
            decoded,
            forHeader: header,
            localTypeInfo: localTypeInfo,
            exactLocal: try matchesLocalTypeDefBytes(
                localTypeInfo: localTypeInfo,
                typeMeta: decoded,
                start: start,
                end: typeMetaEnd),
            config: config
        )
    }

    @inline(never)
    private func readTypeInfoBody(
        start: Int,
        header: UInt64,
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws -> TypeInfo {
        buffer.setCursor(start)
        let decoded = try TypeMeta.decode(
            buffer,
            maxTypeFields: config.maxTypeFields,
            maxTypeMetaBytes: config.maxTypeMetaBytes)
        let typeMetaEnd = buffer.getCursor()
        try validateCompatibleTypeMeta(decoded, for: localTypeInfo, wireTypeID: wireTypeID)
        // The typed path is owned by the declared local type. After identity validation, the
        // decoded metadata must describe this same TypeInfo; do not resolve another owner here.
        return try typeResolver.cacheTypeInfo(
            decoded,
            forHeader: header,
            localTypeInfo: localTypeInfo,
            exactLocal: try matchesLocalTypeDefBytes(
                localTypeInfo: localTypeInfo,
                typeMeta: decoded,
                start: start,
                end: typeMetaEnd),
            config: config
        )
    }

    @inline(never)
    private func matchesLocalTypeDefBytes(
        localTypeInfo: TypeInfo,
        typeMeta: TypeMeta,
        start: Int,
        end: Int
    ) throws -> Bool {
        guard typeMeta.typeID != nil else {
            return false
        }
        guard let localTypeDefBytes = localTypeInfo.typeDefBytes,
            end - start == localTypeDefBytes.count
        else {
            return false
        }
        return buffer.matchesBytes(start: start, bytes: localTypeDefBytes)
    }

    private func validateCompatibleTypeInfo(
        _ remoteTypeInfo: TypeInfo,
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws -> TypeInfo {
        guard let remoteTypeMeta = remoteTypeInfo.compatibleTypeMeta else {
            throw ForyError.invalidData("compatible type metadata is required")
        }
        try validateCompatibleTypeMeta(remoteTypeMeta, for: localTypeInfo, wireTypeID: wireTypeID)
        return remoteTypeInfo
    }

    @inline(never)
    private func validateCompatibleTypeMeta(
        _ remoteTypeMeta: TypeMeta,
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws {
        if let localTypeMeta = localTypeInfo.typeMeta,
            remoteTypeMeta === localTypeMeta
        {
            return
        }
        if remoteTypeMeta.registerByName {
            guard localTypeInfo.registerByName else {
                throw ForyError.invalidData(
                    "received name-registered compatible metadata for id-registered local type")
            }
            if remoteTypeMeta.namespace.value != localTypeInfo.namespace.value {
                throw ForyError.invalidData(
                    "namespace mismatch: expected \(localTypeInfo.namespace.value), got \(remoteTypeMeta.namespace.value)"
                )
            }
            if remoteTypeMeta.typeName.value != localTypeInfo.typeName.value {
                throw ForyError.invalidData(
                    "type name mismatch: expected \(localTypeInfo.typeName.value), got \(remoteTypeMeta.typeName.value)"
                )
            }
        } else {
            guard !localTypeInfo.registerByName else {
                throw ForyError.invalidData(
                    "received id-registered compatible metadata for name-registered local type")
            }
            guard let remoteUserTypeID = remoteTypeMeta.userTypeID else {
                throw ForyError.invalidData("missing user type id in compatible type metadata")
            }
            guard let localUserTypeID = localTypeInfo.userTypeID else {
                throw ForyError.invalidData("missing local user type id metadata for id-registered type")
            }
            if remoteUserTypeID != localUserTypeID {
                throw ForyError.typeMismatch(expected: localUserTypeID, actual: remoteUserTypeID)
            }
        }

        if let remoteTypeID = remoteTypeMeta.typeID,
            let remoteWireTypeID = TypeId(rawValue: remoteTypeID),
            !isAllowedRegisteredWireTypeID(
                remoteWireTypeID,
                declaredTypeID: localTypeInfo.typeID,
                registerByName: localTypeInfo.registerByName,
                compatible: compatible,
                evolving: localTypeInfo.evolving
            )
        {
            throw ForyError.typeMismatch(expected: wireTypeID.rawValue, actual: remoteTypeID)
        }
    }

    @inline(never)
    private func unknownStaticTypeID(_ rawTypeID: UInt32) -> ForyError {
        ForyError.invalidData("unknown type id \(rawTypeID)")
    }

    @inline(never)
    private func staticTypeMismatch(expected: UInt32, actual: UInt32) -> ForyError {
        ForyError.typeMismatch(expected: expected, actual: actual)
    }

    func readAnyValue(typeInfo: TypeInfo) throws -> Any {
        try typeInfo.readDynamic(self)
    }

    /// Returns compatible metadata currently scoped to the selected serializer.
    @inline(__always)
    public func getTypeInfo<T: Serializer>(for type: T.Type) -> TypeInfo? {
        typeInfoStack.value(for: UInt64(UInt(bitPattern: ObjectIdentifier(type))))
    }

    @usableFromInline
    internal func withTypeInfo<T: Serializer, R>(
        _ typeInfo: TypeInfo?,
        for type: T.Type,
        _ body: () throws -> R
    ) rethrows -> R {
        guard let typeInfo else {
            return try body()
        }

        let typeKey = UInt64(UInt(bitPattern: ObjectIdentifier(type)))
        let previousTypeInfo = typeInfoStack.value(for: typeKey)
        typeInfoScopeStack.append((typeKey: typeKey, previousTypeInfo: previousTypeInfo))
        typeInfoStack.set(typeInfo, for: typeKey)
        defer {
            if let scope = typeInfoScopeStack.popLast() {
                if let previousTypeInfo = scope.previousTypeInfo {
                    typeInfoStack.set(previousTypeInfo, for: scope.typeKey)
                } else {
                    _ = typeInfoStack.removeValue(for: scope.typeKey)
                }
            } else {
                assertionFailure("type info scope stack underflow")
            }
        }
        return try body()
    }

    @inline(__always)
    func getReadMetaString(at index: Int) -> MetaString? {
        metaStrings.get(index)
    }

    @inline(__always)
    func appendReadMetaString(_ value: MetaString) {
        metaStrings.push(value)
    }

    func reset() {
        if dynamicAnyDepth != 0 {
            dynamicAnyDepth = 0
        }
        refReader.reset()
        if !typeInfoStack.isEmpty {
            typeInfoStack.clear()
        }
        if !typeInfoScopeStack.isEmpty {
            typeInfoScopeStack.removeAll(keepingCapacity: true)
        }
        compatibleTypeDefTypeInfos.reset()
        metaStrings.reset()
    }
}
