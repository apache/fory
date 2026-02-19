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

public final class CompatibleTypeDefWriteState {
    private var typeIndexBySwiftType: [ObjectIdentifier: UInt32] = [:]
    private var nextIndex: UInt32 = 0

    public init() {}

    public func lookupIndex(for typeID: ObjectIdentifier) -> UInt32? {
        typeIndexBySwiftType[typeID]
    }

    public func assignIndexIfAbsent(for typeID: ObjectIdentifier) -> (index: UInt32, isNew: Bool) {
        if let existing = typeIndexBySwiftType[typeID] {
            return (existing, false)
        }
        let index = nextIndex
        nextIndex &+= 1
        typeIndexBySwiftType[typeID] = index
        return (index, true)
    }

    public func reset() {
        typeIndexBySwiftType.removeAll(keepingCapacity: true)
        nextIndex = 0
    }
}

public final class CompatibleTypeDefReadState {
    private var typeMetas: [TypeMeta] = []

    public init() {}

    public func typeMeta(at index: Int) -> TypeMeta? {
        guard index >= 0, index < typeMetas.count else {
            return nil
        }
        return typeMetas[index]
    }

    public func storeTypeMeta(_ typeMeta: TypeMeta, at index: Int) throws {
        if index < 0 {
            throw ForyError.invalidData("negative compatible type definition index")
        }
        if index == typeMetas.count {
            typeMetas.append(typeMeta)
            return
        }
        if index < typeMetas.count {
            typeMetas[index] = typeMeta
            return
        }
        throw ForyError.invalidData(
            "compatible type definition index gap: index=\(index), count=\(typeMetas.count)"
        )
    }

    public func reset() {
        typeMetas.removeAll(keepingCapacity: true)
    }
}

private struct MetaStringCacheKey: Hashable {
    let encoding: MetaStringEncoding
    let bytes: [UInt8]
}

private struct CanonicalReferenceSignature: Hashable {
    let typeID: ObjectIdentifier
    let hashLo: UInt64
    let hashHi: UInt64
    let length: Int
}

private struct CanonicalReferenceEntry {
    let bytes: [UInt8]
    let object: AnyObject
}

public final class MetaStringWriteState {
    private var stringIndexByKey: [MetaStringCacheKey: UInt32] = [:]
    private var nextIndex: UInt32 = 0

    public init() {}

    public func index(for value: MetaString) -> UInt32? {
        stringIndexByKey[MetaStringCacheKey(encoding: value.encoding, bytes: value.bytes)]
    }

    public func assignIndexIfAbsent(for value: MetaString) -> (index: UInt32, isNew: Bool) {
        let key = MetaStringCacheKey(encoding: value.encoding, bytes: value.bytes)
        if let existing = stringIndexByKey[key] {
            return (existing, false)
        }
        let index = nextIndex
        nextIndex &+= 1
        stringIndexByKey[key] = index
        return (index, true)
    }

    public func reset() {
        stringIndexByKey.removeAll(keepingCapacity: true)
        nextIndex = 0
    }
}

public final class MetaStringReadState {
    private var values: [MetaString] = []

    public init() {}

    public func value(at index: Int) -> MetaString? {
        guard index >= 0, index < values.count else {
            return nil
        }
        return values[index]
    }

    public func append(_ value: MetaString) {
        values.append(value)
    }

    public func reset() {
        values.removeAll(keepingCapacity: true)
    }
}

public struct DynamicTypeInfo {
    public let wireTypeID: ForyTypeId
    public let userTypeID: UInt32?
    public let namespace: MetaString?
    public let typeName: MetaString?
    public let compatibleTypeMeta: TypeMeta?

    public init(
        wireTypeID: ForyTypeId,
        userTypeID: UInt32?,
        namespace: MetaString?,
        typeName: MetaString?,
        compatibleTypeMeta: TypeMeta?
    ) {
        self.wireTypeID = wireTypeID
        self.userTypeID = userTypeID
        self.namespace = namespace
        self.typeName = typeName
        self.compatibleTypeMeta = compatibleTypeMeta
    }
}

public final class WriteContext {
    public let writer: ByteWriter
    public let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let refWriter: RefWriter
    public let compatibleTypeDefState: CompatibleTypeDefWriteState
    public let metaStringWriteState: MetaStringWriteState

    public init(
        writer: ByteWriter,
        typeResolver: TypeResolver,
        trackRef: Bool,
        compatible: Bool = false,
        compatibleTypeDefState: CompatibleTypeDefWriteState = CompatibleTypeDefWriteState(),
        metaStringWriteState: MetaStringWriteState = MetaStringWriteState()
    ) {
        self.writer = writer
        self.typeResolver = typeResolver
        self.trackRef = trackRef
        self.compatible = compatible
        self.refWriter = RefWriter()
        self.compatibleTypeDefState = compatibleTypeDefState
        self.metaStringWriteState = metaStringWriteState
    }

    public func writeCompatibleTypeMeta<T: Serializer>(
        for type: T.Type,
        typeMeta: TypeMeta
    ) throws {
        let typeID = ObjectIdentifier(type)
        let assignment = compatibleTypeDefState.assignIndexIfAbsent(for: typeID)
        if assignment.isNew {
            writer.writeVarUInt32(assignment.index << 1)
            writer.writeBytes(try typeMeta.encode())
        } else {
            writer.writeVarUInt32((assignment.index << 1) | 1)
        }
    }

    public func resetObjectState() {
        refWriter.reset()
    }

    public func reset() {
        resetObjectState()
        compatibleTypeDefState.reset()
        metaStringWriteState.reset()
    }
}

private struct PendingRefSlot {
    var refID: UInt32
    var bound: Bool
}

public final class ReadContext {
    public let reader: ByteReader
    public let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let refReader: RefReader
    public let compatibleTypeDefState: CompatibleTypeDefReadState
    public let metaStringReadState: MetaStringReadState

    private var pendingRefStack: [PendingRefSlot] = []
    private var pendingCompatibleTypeMeta: [ObjectIdentifier: [TypeMeta]] = [:]
    private var pendingDynamicTypeInfo: [ObjectIdentifier: DynamicTypeInfo] = [:]
    private var canonicalReferenceCache: [CanonicalReferenceSignature: [CanonicalReferenceEntry]] = [:]

    public init(
        reader: ByteReader,
        typeResolver: TypeResolver,
        trackRef: Bool,
        compatible: Bool = false,
        compatibleTypeDefState: CompatibleTypeDefReadState = CompatibleTypeDefReadState(),
        metaStringReadState: MetaStringReadState = MetaStringReadState()
    ) {
        self.reader = reader
        self.typeResolver = typeResolver
        self.trackRef = trackRef
        self.compatible = compatible
        self.refReader = RefReader()
        self.compatibleTypeDefState = compatibleTypeDefState
        self.metaStringReadState = metaStringReadState
    }

    public func pushPendingReference(_ refID: UInt32) {
        pendingRefStack.append(PendingRefSlot(refID: refID, bound: false))
    }

    public func bindPendingReference(_ value: Any) {
        guard var last = pendingRefStack.popLast() else {
            return
        }
        last.bound = true
        refReader.storeRef(value, at: last.refID)
        pendingRefStack.append(last)
    }

    public func finishPendingReferenceIfNeeded(_ value: Any) {
        guard var last = pendingRefStack.popLast() else {
            return
        }
        if !last.bound {
            refReader.storeRef(value, at: last.refID)
            last.bound = true
        }
    }

    public func popPendingReference() {
        _ = pendingRefStack.popLast()
    }

    public func readCompatibleTypeMeta() throws -> TypeMeta {
        let indexMarker = try reader.readVarUInt32()
        let isRef = (indexMarker & 1) == 1
        let index = Int(indexMarker >> 1)
        if isRef {
            guard let typeMeta = compatibleTypeDefState.typeMeta(at: index) else {
                throw ForyError.invalidData("unknown compatible type definition ref index \(index)")
            }
            return typeMeta
        }
        let typeMeta = try TypeMeta.decode(reader)
        try compatibleTypeDefState.storeTypeMeta(typeMeta, at: index)
        return typeMeta
    }

    public func pushCompatibleTypeMeta<T: Serializer>(for type: T.Type, _ typeMeta: TypeMeta) {
        let typeID = ObjectIdentifier(type)
        pendingCompatibleTypeMeta[typeID] = [typeMeta]
    }

    public func consumeCompatibleTypeMeta<T: Serializer>(for type: T.Type) throws -> TypeMeta {
        let typeID = ObjectIdentifier(type)
        guard let stack = pendingCompatibleTypeMeta[typeID], let last = stack.last else {
            throw ForyError.invalidData("missing compatible type metadata for \(type)")
        }
        return last
    }

    public func setDynamicTypeInfo<T: Serializer>(for type: T.Type, _ typeInfo: DynamicTypeInfo) {
        pendingDynamicTypeInfo[ObjectIdentifier(type)] = typeInfo
    }

    public func dynamicTypeInfo<T: Serializer>(for type: T.Type) -> DynamicTypeInfo? {
        pendingDynamicTypeInfo[ObjectIdentifier(type)]
    }

    public func clearDynamicTypeInfo<T: Serializer>(for type: T.Type) {
        pendingDynamicTypeInfo.removeValue(forKey: ObjectIdentifier(type))
    }

    public func canonicalizeNonTrackingReference<T>(
        _ value: T,
        start: Int,
        end: Int
    ) -> T {
        guard trackRef else {
            return value
        }
        guard end > start else {
            return value
        }
        guard let object = value as AnyObject? else {
            return value
        }

        let bytes = Array(reader.storage[start..<end])
        let (hashLo, hashHi) = MurmurHash3.x64_128(bytes, seed: 47)
        let signature = CanonicalReferenceSignature(
            typeID: ObjectIdentifier(type(of: object)),
            hashLo: hashLo,
            hashHi: hashHi,
            length: bytes.count
        )

        if var bucket = canonicalReferenceCache[signature] {
            for entry in bucket where entry.bytes == bytes {
                if let shared = entry.object as? T {
                    return shared
                }
            }
            bucket.append(CanonicalReferenceEntry(bytes: bytes, object: object))
            canonicalReferenceCache[signature] = bucket
            return value
        }

        canonicalReferenceCache[signature] = [CanonicalReferenceEntry(bytes: bytes, object: object)]
        return value
    }

    public func resetObjectState() {
        refReader.reset()
        pendingRefStack.removeAll(keepingCapacity: true)
        pendingCompatibleTypeMeta.removeAll(keepingCapacity: true)
        pendingDynamicTypeInfo.removeAll(keepingCapacity: true)
        canonicalReferenceCache.removeAll(keepingCapacity: true)
    }

    public func reset() {
        resetObjectState()
        compatibleTypeDefState.reset()
        metaStringReadState.reset()
    }
}

public extension WriteContext {
    func writeAny(
        _ value: Any?,
        refMode: RefMode,
        writeTypeInfo: Bool = true,
        hasGenerics: Bool = false
    ) throws {
        try ForySwift.writeAny(
            value,
            context: self,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo,
            hasGenerics: hasGenerics
        )
    }

    func writeAnyList(
        _ value: [Any]?,
        refMode: RefMode,
        writeTypeInfo: Bool = false,
        hasGenerics: Bool = true
    ) throws {
        try ForySwift.writeAnyList(
            value,
            context: self,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo,
            hasGenerics: hasGenerics
        )
    }

    func writeStringAnyMap(
        _ value: [String: Any]?,
        refMode: RefMode,
        writeTypeInfo: Bool = false,
        hasGenerics: Bool = true
    ) throws {
        try ForySwift.writeStringAnyMap(
            value,
            context: self,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo,
            hasGenerics: hasGenerics
        )
    }

    func writeInt32AnyMap(
        _ value: [Int32: Any]?,
        refMode: RefMode,
        writeTypeInfo: Bool = false,
        hasGenerics: Bool = true
    ) throws {
        try ForySwift.writeInt32AnyMap(
            value,
            context: self,
            refMode: refMode,
            writeTypeInfo: writeTypeInfo,
            hasGenerics: hasGenerics
        )
    }
}

public extension ReadContext {
    func readAny(
        refMode: RefMode,
        readTypeInfo: Bool = true
    ) throws -> Any? {
        try ForySwift.readAny(
            context: self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    func readAnyList(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [Any]? {
        try ForySwift.readAnyList(
            context: self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    func readStringAnyMap(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [String: Any]? {
        try ForySwift.readStringAnyMap(
            context: self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    func readInt32AnyMap(
        refMode: RefMode,
        readTypeInfo: Bool = false
    ) throws -> [Int32: Any]? {
        try ForySwift.readInt32AnyMap(
            context: self,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }
}
