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

private struct MetaStringCacheKey: Hashable {
    let encoding: MetaStringEncoding
    let bytes: [UInt8]
}

@inline(never)
private func invalidDynamicDepth(_ maxDepth: Int) throws -> Never {
    throw ForyError.invalidData("configured maxDepth \(maxDepth) is negative")
}

@inline(never)
private func dynamicDepthExceeded(_ depth: Int, maxDepth: Int) throws -> Never {
    throw ForyError.invalidData(
        "dynamic Any nesting depth \(depth) exceeds configured maxDepth \(maxDepth)")
}

final class MetaStringWriteState {
    private var stringIndexByKey: [MetaStringCacheKey: UInt32] = [:]
    private var nextIndex: UInt32 = 0

    init() {}

    func index(for value: MetaString) -> UInt32? {
        stringIndexByKey[MetaStringCacheKey(encoding: value.encoding, bytes: value.bytes)]
    }

    func assignIndexIfAbsent(for value: MetaString) -> (index: UInt32, isNew: Bool) {
        let key = MetaStringCacheKey(encoding: value.encoding, bytes: value.bytes)
        if let existing = stringIndexByKey[key] {
            return (existing, false)
        }
        let index = nextIndex
        nextIndex &+= 1
        stringIndexByKey[key] = index
        return (index, true)
    }

    func reset() {
        if !stringIndexByKey.isEmpty {
            stringIndexByKey.removeAll(keepingCapacity: true)
        }
        if nextIndex != 0 {
            nextIndex = 0
        }
    }
}

public final class WriteContext {
    public let buffer: ByteBuffer
    let typeResolver: TypeResolver
    public let trackRef: Bool
    public let compatible: Bool
    public let checkClassVersion: Bool
    public let maxDepth: Int
    public let refWriter: RefWriter
    let metaStringWriteState: MetaStringWriteState
    private let typeIndexBySerializer = UInt64Map<UInt32>(initialCapacity: 8)
    private var typeDefStateUsed = false
    private var metaStringWriteStateUsed = false
    private var dynamicRefStateUsed = false
    private var dynamicAnyDepth = 0
    private var lastTypeInfo = TypeInfo.uncached
    private var lastTargetTypeInfo = TypeInfo.uncached

    convenience init(
        buffer: ByteBuffer,
        typeResolver: TypeResolver,
        trackRef: Bool,
        compatible: Bool = false,
        checkClassVersion: Bool = true,
        maxDepth: Int = 5
    ) {
        self.init(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: trackRef,
            compatible: compatible,
            checkClassVersion: checkClassVersion,
            maxDepth: maxDepth,
            metaStringWriteState: MetaStringWriteState()
        )
    }

    init(
        buffer: ByteBuffer,
        typeResolver: TypeResolver,
        trackRef: Bool,
        compatible: Bool,
        checkClassVersion: Bool,
        maxDepth: Int,
        metaStringWriteState: MetaStringWriteState
    ) {
        self.buffer = buffer
        self.typeResolver = typeResolver
        self.trackRef = trackRef
        self.compatible = compatible
        self.checkClassVersion = checkClassVersion
        self.maxDepth = maxDepth
        self.refWriter = RefWriter()
        self.metaStringWriteState = metaStringWriteState
    }

    @inline(__always)
    func enterDynamicAnyDepth() throws {
        if maxDepth < 0 {
            try invalidDynamicDepth(maxDepth)
        }
        let nextDepth = dynamicAnyDepth + 1
        if nextDepth > maxDepth {
            try dynamicDepthExceeded(nextDepth, maxDepth: maxDepth)
        }
        dynamicAnyDepth = nextDepth
    }

    @usableFromInline
    @inline(__always)
    internal func typeInfo<T: Serializer>(for type: T.Type) throws -> TypeInfo {
        let typeID = ObjectIdentifier(type)
        if lastTypeInfo.serializerTypeID == typeID {
            return lastTypeInfo
        }
        let info = try typeResolver.requireTypeInfo(for: type)
        lastTypeInfo = info
        return info
    }

    @inline(__always)
    func typeInfo(forTarget type: Any.Type) throws -> TypeInfo {
        let typeID = ObjectIdentifier(type)
        if lastTargetTypeInfo.targetTypeID == typeID {
            return lastTargetTypeInfo
        }
        let info = try typeResolver.requireTypeInfo(forTarget: type)
        lastTargetTypeInfo = info
        return info
    }

    @usableFromInline
    @inline(__always)
    internal func writeStaticTypeInfo(_ typeID: TypeId) {
        buffer.writeUInt8(UInt8(truncatingIfNeeded: typeID.rawValue))
    }

    @inline(__always)
    func leaveDynamicAnyDepth() {
        if dynamicAnyDepth > 0 {
            dynamicAnyDepth -= 1
        }
    }

    @inline(__always)
    func markDynamicRefStateUsed() {
        dynamicRefStateUsed = true
    }

    func writeTypeMeta(_ typeInfo: TypeInfo) throws {
        let buffer = self.buffer
        let typeIndexBySerializer = self.typeIndexBySerializer
        let typeKey = UInt64(UInt(bitPattern: typeInfo.serializerTypeID))
        if !typeDefStateUsed {
            // Reset keeps this flag paired with an empty index map, so the first TypeMeta in a
            // root always owns index zero and does not need a general lookup.
            typeDefStateUsed = true
            typeIndexBySerializer.set(0, for: typeKey)
            buffer.writeUInt8(0)
            if let typeDefBytes = typeInfo.typeDefBytes {
                buffer.writeBytes(typeDefBytes)
            }
            return
        }

        let assignment = typeIndexBySerializer.putIfAbsent(
            UInt32(typeIndexBySerializer.count),
            for: typeKey
        )
        if assignment.inserted {
            let marker = assignment.value << 1
            if marker < 0x80 {
                buffer.writeUInt8(UInt8(truncatingIfNeeded: marker))
            } else {
                buffer.writeVarUInt32(marker)
            }
            if let typeDefBytes = typeInfo.typeDefBytes {
                buffer.writeBytes(typeDefBytes)
            }
        } else {
            let marker = (assignment.value << 1) | 1
            if marker < 0x80 {
                buffer.writeUInt8(UInt8(truncatingIfNeeded: marker))
            } else {
                buffer.writeVarUInt32(marker)
            }
        }
    }

    @inline(__always)
    func markMetaStringWriteStateUsed() {
        metaStringWriteStateUsed = true
    }

    func reset() {
        if dynamicAnyDepth != 0 {
            dynamicAnyDepth = 0
        }
        if trackRef || dynamicRefStateUsed {
            refWriter.reset()
            dynamicRefStateUsed = false
        }
        if typeDefStateUsed {
            if !typeIndexBySerializer.isEmpty {
                typeIndexBySerializer.clear()
            }
            typeDefStateUsed = false
        }
        if metaStringWriteStateUsed {
            metaStringWriteState.reset()
            metaStringWriteStateUsed = false
        }
    }
}
