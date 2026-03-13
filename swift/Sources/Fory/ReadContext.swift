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

public final class ReadContext {
    public let buffer: ByteBuffer
    let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let checkClassVersion: Bool
    public let maxCollectionSize: Int
    public let maxBinarySize: Int
    public let maxDepth: Int
    public let refReader: RefReader
    private let compatibleTypeDefTypeInfos = ReusableArray<TypeInfo?>(defaultValue: nil, reserve: 2)
    private let metaStrings = ReusableArray<MetaString?>(defaultValue: nil, reserve: 16)
    private var dynamicAnyDepth = 0

    private var typeInfoStack = UInt64Map<TypeInfo>(initialCapacity: 8)
    private var typeInfoScopeStack: [(typeKey: UInt64, previousTypeInfo: TypeInfo?)] = []
    private var lastTypeInfo = TypeInfo.uncached

    init(
        buffer: ByteBuffer,
        typeResolver: TypeResolver,
        trackRef: Bool,
        compatible: Bool = false,
        checkClassVersion: Bool = true,
        maxCollectionSize: Int = 1_000_000,
        maxBinarySize: Int = 64 * 1024 * 1024,
        maxDepth: Int = 5
    ) {
        self.buffer = buffer
        self.typeResolver = typeResolver
        self.trackRef = trackRef
        self.compatible = compatible
        self.checkClassVersion = checkClassVersion
        self.maxCollectionSize = maxCollectionSize
        self.maxBinarySize = maxBinarySize
        self.maxDepth = maxDepth
        self.refReader = RefReader()
    }

    @inline(__always)
    func enterDynamicAnyDepth() throws {
        if maxDepth < 0 {
            throw ForyError.invalidData("configured maxDepth \(maxDepth) is negative")
        }
        let nextDepth = dynamicAnyDepth + 1
        if nextDepth > maxDepth {
            throw ForyError.invalidData(
                "dynamic Any nesting depth \(nextDepth) exceeds configured maxDepth \(maxDepth)"
            )
        }
        dynamicAnyDepth = nextDepth
    }

    @inline(__always)
    func leaveDynamicAnyDepth() {
        if dynamicAnyDepth > 0 {
            dynamicAnyDepth -= 1
        }
    }

    @inline(__always)
    func ensureCollectionLength(_ length: Int, label: String) throws {
        if length < 0 {
            throw ForyError.invalidData("\(label) length is negative")
        }
        if length > maxCollectionSize {
            throw ForyError.invalidData(
                "\(label) length \(length) exceeds configured maxCollectionSize \(maxCollectionSize)"
            )
        }
    }

    @inline(__always)
    func ensureBinaryLength(_ length: Int, label: String) throws {
        if length < 0 {
            throw ForyError.invalidData("\(label) size is negative")
        }
        if length > maxBinarySize {
            throw ForyError.invalidData(
                "\(label) size \(length) exceeds configured maxBinarySize \(maxBinarySize)"
            )
        }
    }

    @inline(__always)
    func ensureRemainingBytes(_ byteCount: Int, label: String) throws {
        if byteCount < 0 {
            throw ForyError.invalidData("\(label) size is negative")
        }
        let remainingBytes = buffer.remaining
        if byteCount > remainingBytes {
            throw ForyError.invalidData(
                "\(label) requires \(byteCount) bytes but only \(remainingBytes) remain in buffer"
            )
        }
    }

    @inline(__always)
    func typeInfo<T: Serializer>(for type: T.Type) throws -> TypeInfo {
        let typeID = ObjectIdentifier(type)
        if lastTypeInfo.swiftTypeID == typeID {
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
            throw ForyError.invalidData("unknown type id \(rawTypeID)")
        }
        if actualTypeID != typeID {
            throw ForyError.typeMismatch(expected: typeID.rawValue, actual: rawTypeID)
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
                    throw ForyError.invalidData("received name-registered type info for id-registered local type")
                }
                if namespace.value != localTypeInfo.namespace.value ||
                    typeName.value != localTypeInfo.typeName.value {
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
           let localTypeDefHeader = localTypeInfo.typeDefHeader {
            let typeMetaStart = buffer.getCursor()
            let indexMarker = try buffer.readVarUInt32()
            if indexMarker == 0 {
                let header = try buffer.readUInt64()
                var bodySize = Int(header & UInt64(typeMetaSizeMask))
                if bodySize == typeMetaSizeMask {
                    bodySize += Int(try buffer.readVarUInt32())
                }
                if header == localTypeDefHeader {
                    compatibleTypeDefTypeInfos.push(localTypeInfo)
                    try buffer.skip(bodySize)
                    return nil
                }
            }
            buffer.setCursor(typeMetaStart)
        }
        return try readCompatibleTypeInfo(
            for: localTypeInfo,
            wireTypeID: wireTypeID
        )
    }

    private func readCompatibleTypeInfo() throws -> TypeInfo {
        let buffer = self.buffer
        let compatibleTypeDefTypeInfos = self.compatibleTypeDefTypeInfos
        let indexMarker = try buffer.readVarUInt32()
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
            try buffer.skip(bodySize)
            compatibleTypeDefTypeInfos.push(cached)
            return cached
        }

        buffer.setCursor(typeMetaStart)
        let decoded = try TypeMeta.decode(buffer)
        let cachedTypeInfo = try typeResolver.cacheTypeInfo(decoded, forHeader: header)
        compatibleTypeDefTypeInfos.push(cachedTypeInfo)
        return cachedTypeInfo
    }

    @inline(__always)
    private func readCompatibleTypeInfo(
        for localTypeInfo: TypeInfo,
        wireTypeID: TypeId
    ) throws -> TypeInfo {
        let buffer = self.buffer
        let compatibleTypeDefTypeInfos = self.compatibleTypeDefTypeInfos
        let remoteTypeInfo: TypeInfo
        if compatibleTypeDefTypeInfos.isEmpty,
           let localTypeDefHeader = localTypeInfo.typeDefHeader {
            let typeMetaStart = buffer.getCursor()
            let indexMarker = try buffer.readVarUInt32()
            if indexMarker != 0 {
                buffer.setCursor(typeMetaStart)
                remoteTypeInfo = try readCompatibleTypeInfo()
            } else {
                let header = try buffer.readUInt64()
                var bodySize = Int(header & UInt64(typeMetaSizeMask))
                if bodySize == typeMetaSizeMask {
                    bodySize += Int(try buffer.readVarUInt32())
                }

                if header == localTypeDefHeader {
                    compatibleTypeDefTypeInfos.push(localTypeInfo)
                    try buffer.skip(bodySize)
                    return localTypeInfo
                }

                buffer.setCursor(typeMetaStart)
                remoteTypeInfo = try readCompatibleTypeInfo()
            }
        } else {
            remoteTypeInfo = try readCompatibleTypeInfo()
        }
        guard let remoteTypeMeta = remoteTypeInfo.compatibleTypeMeta else {
            throw ForyError.invalidData("compatible type metadata is required")
        }
        if let localTypeMeta = localTypeInfo.typeMeta,
           remoteTypeMeta === localTypeMeta {
            return localTypeInfo
        }
        if remoteTypeMeta.registerByName {
            guard localTypeInfo.registerByName else {
                throw ForyError.invalidData("received name-registered compatible metadata for id-registered local type")
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
                throw ForyError.invalidData("received id-registered compatible metadata for name-registered local type")
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
           ) {
            throw ForyError.typeMismatch(expected: wireTypeID.rawValue, actual: remoteTypeID)
        }
        return remoteTypeInfo
    }

    func readAnyValue(typeInfo: TypeInfo) throws -> Any {
        try enterDynamicAnyDepth()
        defer { leaveDynamicAnyDepth() }

        let value: Any
        switch typeInfo.typeID {
        case .bool:
            value = try Bool.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int8:
            value = try Int8.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int16:
            value = try Int16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int32:
            value = try ForyInt32Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varint32:
            value = try Int32.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int64:
            value = try ForyInt64Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varint64:
            value = try Int64.foryRead(self, refMode: .none, readTypeInfo: false)
        case .taggedInt64:
            value = try ForyInt64Tagged.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint8:
            value = try UInt8.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint16:
            value = try UInt16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint32:
            value = try ForyUInt32Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varUInt32:
            value = try UInt32.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint64:
            value = try ForyUInt64Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varUInt64:
            value = try UInt64.foryRead(self, refMode: .none, readTypeInfo: false)
        case .taggedUInt64:
            value = try ForyUInt64Tagged.foryRead(self, refMode: .none, readTypeInfo: false)
        case .float16:
            value = try Float16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .bfloat16:
            value = try BFloat16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .float32:
            value = try Float.foryRead(self, refMode: .none, readTypeInfo: false)
        case .float64:
            value = try Double.foryRead(self, refMode: .none, readTypeInfo: false)
        case .string:
            value = try String.foryRead(self, refMode: .none, readTypeInfo: false)
        case .duration:
            value = try Duration.foryRead(self, refMode: .none, readTypeInfo: false)
        case .timestamp:
            value = try Date.foryRead(self, refMode: .none, readTypeInfo: false)
        case .date:
            value = try ForyDate.foryRead(self, refMode: .none, readTypeInfo: false)
        case .binary, .uint8Array:
            value = try Data.foryRead(self, refMode: .none, readTypeInfo: false)
        case .boolArray:
            value = try [Bool].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int8Array:
            value = try [Int8].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int16Array:
            value = try [Int16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int32Array:
            value = try [Int32].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int64Array:
            value = try [Int64].foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint16Array:
            value = try [UInt16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint32Array:
            value = try [UInt32].foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint64Array:
            value = try [UInt64].foryRead(self, refMode: .none, readTypeInfo: false)
        case .float16Array:
            value = try [Float16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .bfloat16Array:
            value = try [BFloat16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .float32Array:
            value = try [Float].foryRead(self, refMode: .none, readTypeInfo: false)
        case .float64Array:
            value = try [Double].foryRead(self, refMode: .none, readTypeInfo: false)
        case .array, .list:
            value = try readAnyList(refMode: .none) ?? []
        case .set:
            value = try Set<AnyHashable>.foryRead(self, refMode: .none, readTypeInfo: false)
        case .map:
            value = try readDynamicAnyMapValue(context: self)
        case .none:
            value = ForyAnyNullValue()
        default:
            if typeInfo.typeID.isUserTypeKind {
                value = try typeInfo.read(self)
            } else {
                throw ForyError.invalidData("unsupported dynamic type id \(typeInfo.typeID)")
            }
        }
        return value
    }

    @inline(__always)
    func getTypeInfo<T: Serializer>(for type: T.Type) -> TypeInfo? {
        typeInfoStack.value(for: UInt64(UInt(bitPattern: ObjectIdentifier(type))))
    }

    func withTypeInfo<T: Serializer, R>(
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
        if trackRef {
            refReader.reset()
        }
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

public extension ReadContext {
    func readAny(
        refMode: RefMode,
        readTypeInfo: Bool = true
    ) throws -> Any? {
        try DynamicAnyValue.foryRead(self, refMode: refMode, readTypeInfo: readTypeInfo).anyValue()
    }

    func readAnyList(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [Any]? {
        let wrapped: [DynamicAnyValue]? = try [DynamicAnyValue]?.foryRead(
            self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
        return wrapped?.map { $0.anyValueForCollection() }
    }

    func readStringAnyMap(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [String: Any]? {
        let wrapped: [String: DynamicAnyValue]? = try [String: DynamicAnyValue]?.foryRead(
            self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
        guard let wrapped else {
            return nil
        }
        var map: [String: Any] = [:]
        map.reserveCapacity(wrapped.count)
        for pair in wrapped {
            map[pair.key] = pair.value.anyValueForCollection()
        }
        return map
    }

    func readInt32AnyMap(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [Int32: Any]? {
        let wrapped: [Int32: DynamicAnyValue]? = try [Int32: DynamicAnyValue]?.foryRead(
            self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
        guard let wrapped else {
            return nil
        }
        var map: [Int32: Any] = [:]
        map.reserveCapacity(wrapped.count)
        for pair in wrapped {
            map[pair.key] = pair.value.anyValueForCollection()
        }
        return map
    }

    func readAnyHashableAnyMap(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [AnyHashable: Any]? {
        let wrapped: [AnyHashable: DynamicAnyValue]? = try [AnyHashable: DynamicAnyValue]?.foryRead(
            self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
        guard let wrapped else {
            return nil
        }
        var map: [AnyHashable: Any] = [:]
        map.reserveCapacity(wrapped.count)
        for pair in wrapped {
            map[pair.key] = pair.value.anyValueForCollection()
        }
        return map
    }
}
