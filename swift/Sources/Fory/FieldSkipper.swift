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

extension ReadContext {
    public func skipFieldValue(_ fieldType: TypeMeta.FieldType) throws {
        _ = try readSkippedFieldValue(
            fieldType: fieldType,
            readTypeInfo: needsTypeInfoForSkippedField(fieldType.typeID)
        )
    }

    private func needsTypeInfoForSkippedField(_ typeID: UInt32) -> Bool {
        guard let resolved = TypeId(rawValue: typeID) else {
            return true
        }
        return TypeId.needsTypeInfoForField(resolved)
    }

    @inline(__always)
    private func skippedFieldReadAlwaysAdvances(
        _ fieldType: TypeMeta.FieldType,
        typeInfo: TypeInfo?
    ) -> Bool {
        if let typeInfo {
            return typeInfo.readDataAlwaysAdvances
        }
        return TypeId(rawValue: fieldType.typeID)?.readDataAlwaysAdvances == true
    }

    private func readSkippedFieldValue(
        fieldType: TypeMeta.FieldType,
        typeInfo: TypeInfo? = nil,
        readTypeInfo: Bool
    ) throws -> Any? {
        let refMode = RefMode.from(nullable: fieldType.nullable, trackRef: fieldType.trackRef)
        return try readSkippedValue(
            fieldType: fieldType,
            typeInfo: typeInfo,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    private func readSkippedValue(
        fieldType: TypeMeta.FieldType,
        typeInfo: TypeInfo?,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Any? {
        if refMode == .tracking,
            fieldType.typeID != TypeId.unknown.rawValue,
            let typeInfo,
            typeInfo.isRefType
        {
            // A static same-type reference body has one tracking envelope, owned by its registered
            // reader so it can publish the reference before reading children. Dynamic fields keep
            // their outer envelope here because their concrete TypeInfo writes a second envelope.
            return try typeInfo.readDeclared(self)
        }
        switch refMode {
        case .none:
            return try readSkippedFieldPayload(
                fieldType: fieldType,
                typeInfo: typeInfo,
                readTypeInfo: readTypeInfo
            )
        case .nullOnly:
            let flag = try buffer.readInt8()
            if flag == RefFlag.null.rawValue {
                return nil
            }
            guard flag == RefFlag.notNullValue.rawValue else {
                throw ForyError.invalidData("unexpected nullOnly flag \(flag)")
            }
            return try readSkippedFieldPayload(
                fieldType: fieldType,
                typeInfo: typeInfo,
                readTypeInfo: readTypeInfo
            )
        case .tracking:
            let rawFlag = try buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.invalidData("unexpected tracking flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return nil
            case .ref:
                let refID = try buffer.readVarUInt32()
                return try refReader.readRefValue(refID)
            case .refValue:
                let refID = refReader.reserveRefID()
                let value = try readSkippedFieldPayload(
                    fieldType: fieldType,
                    typeInfo: typeInfo,
                    readTypeInfo: readTypeInfo
                )
                refReader.storeRef(value, at: refID)
                return value
            case .notNullValue:
                return try readSkippedFieldPayload(
                    fieldType: fieldType,
                    typeInfo: typeInfo,
                    readTypeInfo: readTypeInfo
                )
            }
        }
    }

    private func readSkippedFieldPayload(
        fieldType: TypeMeta.FieldType,
        typeInfo: TypeInfo?,
        readTypeInfo: Bool
    ) throws -> Any {
        if let typeInfo {
            return try readSkippedTypeInfoValue(fieldType: fieldType, typeInfo: typeInfo)
        }
        if readTypeInfo {
            let typeInfo = try self.readTypeInfo()
            return try readSkippedTypeInfoValue(fieldType: fieldType, typeInfo: typeInfo)
        }

        guard let resolvedTypeID = TypeId(rawValue: fieldType.typeID) else {
            throw ForyError.invalidData("unknown compatible field type id \(fieldType.typeID)")
        }

        switch resolvedTypeID {
        case .none:
            return ForyAnyNullValue()
        case .bool:
            return try Bool.read(self, refMode: .none, readTypeInfo: false)
        case .int8:
            return try Int8.read(self, refMode: .none, readTypeInfo: false)
        case .int16:
            return try Int16.read(self, refMode: .none, readTypeInfo: false)
        case .int32:
            return try buffer.readInt32()
        case .varint32:
            return try Int32.read(self, refMode: .none, readTypeInfo: false)
        case .int64:
            return try buffer.readInt64()
        case .varint64:
            return try Int64.read(self, refMode: .none, readTypeInfo: false)
        case .taggedInt64:
            return try buffer.readTaggedInt64()
        case .uint8:
            return try UInt8.read(self, refMode: .none, readTypeInfo: false)
        case .uint16:
            return try UInt16.read(self, refMode: .none, readTypeInfo: false)
        case .uint32:
            return try buffer.readUInt32()
        case .varUInt32:
            return try UInt32.read(self, refMode: .none, readTypeInfo: false)
        case .uint64:
            return try buffer.readUInt64()
        case .varUInt64:
            return try UInt64.read(self, refMode: .none, readTypeInfo: false)
        case .taggedUInt64:
            return try buffer.readTaggedUInt64()
        case .float16:
            return try Float16.read(self, refMode: .none, readTypeInfo: false)
        case .bfloat16:
            return try BFloat16.read(self, refMode: .none, readTypeInfo: false)
        case .float32:
            return try Float.read(self, refMode: .none, readTypeInfo: false)
        case .float64:
            return try Double.read(self, refMode: .none, readTypeInfo: false)
        case .string:
            return try String.read(self, refMode: .none, readTypeInfo: false)
        case .duration:
            return try Duration.read(self, refMode: .none, readTypeInfo: false)
        case .timestamp:
            return try Date.read(self, refMode: .none, readTypeInfo: false)
        case .date:
            return try LocalDate.read(self, refMode: .none, readTypeInfo: false)
        case .decimal:
            return try Decimal.read(self, refMode: .none, readTypeInfo: false)
        case .binary, .uint8Array:
            return try Data.read(self, refMode: .none, readTypeInfo: false)
        // Packed-array IDs carry dense bodies; the ordinary Array serializer always carries LIST.
        case .boolArray:
            let value: [Bool] = try readPrimitiveArray(self)
            return value
        case .int8Array:
            let value: [Int8] = try readPrimitiveArray(self)
            return value
        case .int16Array:
            let value: [Int16] = try readPrimitiveArray(self)
            return value
        case .int32Array:
            let value: [Int32] = try readPrimitiveArray(self)
            return value
        case .int64Array:
            let value: [Int64] = try readPrimitiveArray(self)
            return value
        case .uint16Array:
            let value: [UInt16] = try readPrimitiveArray(self)
            return value
        case .uint32Array:
            let value: [UInt32] = try readPrimitiveArray(self)
            return value
        case .uint64Array:
            let value: [UInt64] = try readPrimitiveArray(self)
            return value
        case .float16Array:
            let value: [Float16] = try readPrimitiveArray(self)
            return value
        case .bfloat16Array:
            let value: [BFloat16] = try readPrimitiveArray(self)
            return value
        case .float32Array:
            let value: [Float] = try readPrimitiveArray(self)
            return value
        case .float64Array:
            let value: [Double] = try readPrimitiveArray(self)
            return value
        case .array, .list:
            return try readSkippedCollection(fieldType: fieldType)
        case .set:
            return try readSkippedSet(fieldType: fieldType)
        case .map:
            return try readSkippedMap(fieldType: fieldType)
        case .union, .typedUnion, .namedUnion:
            return try readSkippedUnion()
        case .enumType, .namedEnum:
            return try buffer.readVarUInt32()
        default:
            throw ForyError.invalidData("unsupported compatible field type id \(fieldType.typeID)")
        }
    }

    @inline(__always)
    private func readSkippedTypeInfoValue(
        fieldType: TypeMeta.FieldType,
        typeInfo: TypeInfo
    ) throws -> Any {
        if fieldType.typeID != TypeId.unknown.rawValue, typeInfo.isRefType {
            // Static collection/map framing already consumed or omitted the operation envelope.
            // Reading the retained reference TypeInfo as a complete dynamic value would consume
            // the first body byte as a second reference flag.
            return try typeInfo.readBody(self)
        }
        if fieldType.typeID != TypeId.unknown.rawValue {
            return try typeInfo.readDeclared(self)
        }
        return try readAnyValue(typeInfo: typeInfo)
    }

    private func readSkippedCollection(
        fieldType: TypeMeta.FieldType
    ) throws -> [Any] {
        let elementFieldType =
            fieldType.generics.first
            ?? TypeMeta.FieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        let length = Int(try buffer.readVarUInt32())
        try ensureCollectionLength(length, label: "compatible_collection")
        if length == 0 {
            return []
        }

        let header = try buffer.readUInt8()
        let trackRef = (header & 0b0000_0001) != 0
        let hasNull = (header & 0b0000_0010) != 0
        let declared = (header & 0b0000_0100) != 0
        let sameType = (header & 0b0000_1000) != 0

        var typeInfo: TypeInfo?
        if sameType, !declared {
            typeInfo = try self.readTypeInfo()
        }

        if sameType, !trackRef, !hasNull {
            if skippedFieldReadAlwaysAdvances(elementFieldType, typeInfo: typeInfo) {
                for _ in 0..<length {
                    _ = try readSkippedFieldPayload(
                        fieldType: elementFieldType,
                        typeInfo: typeInfo,
                        readTypeInfo: false
                    )
                }
                return []
            }

            var windowStart = buffer.cursor
            var windowItems = 0
            for _ in 0..<length {
                _ = try readSkippedFieldPayload(
                    fieldType: elementFieldType,
                    typeInfo: typeInfo,
                    readTypeInfo: false
                )
                windowItems += 1
                if windowItems == unbackedContainerCheckInterval {
                    try settleUnbackedContainerItems(
                        self, completed: windowItems, startCursor: windowStart)
                    windowStart = buffer.cursor
                    windowItems = 0
                }
            }
            if windowItems != 0 {
                try settleUnbackedContainerItems(
                    self, completed: windowItems, startCursor: windowStart)
            }
            return []
        }

        for _ in 0..<length {
            if sameType {
                if trackRef {
                    _ = try readSkippedValue(
                        fieldType: elementFieldType,
                        typeInfo: typeInfo,
                        refMode: .tracking,
                        readTypeInfo: false
                    )
                } else if hasNull {
                    let refFlag = try buffer.readInt8()
                    if refFlag == RefFlag.null.rawValue {
                        continue
                    }
                    if refFlag != RefFlag.notNullValue.rawValue {
                        throw ForyError.invalidData("invalid collection nullability flag \(refFlag)")
                    }
                    _ = try readSkippedFieldPayload(
                        fieldType: elementFieldType,
                        typeInfo: typeInfo,
                        readTypeInfo: false
                    )
                }
                continue
            }

            if trackRef {
                _ = try readSkippedValue(
                    fieldType: elementFieldType,
                    typeInfo: nil,
                    refMode: .tracking,
                    readTypeInfo: true
                )
            } else if hasNull {
                let refFlag = try buffer.readInt8()
                if refFlag == RefFlag.null.rawValue {
                    continue
                }
                if refFlag != RefFlag.notNullValue.rawValue {
                    throw ForyError.invalidData("invalid collection nullability flag \(refFlag)")
                }
                _ = try readSkippedFieldPayload(
                    fieldType: elementFieldType,
                    typeInfo: nil,
                    readTypeInfo: true
                )
            } else {
                _ = try readSkippedFieldPayload(
                    fieldType: elementFieldType,
                    typeInfo: nil,
                    readTypeInfo: true
                )
            }
        }

        return []
    }

    private func readSkippedSet(
        fieldType: TypeMeta.FieldType
    ) throws -> Set<AnyHashable> {
        _ = try readSkippedCollection(fieldType: fieldType)
        return []
    }

    private func readSkippedMap(
        fieldType: TypeMeta.FieldType
    ) throws -> [AnyHashable: Any] {
        let keyType =
            fieldType.generics.first
            ?? TypeMeta.FieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        let valueType =
            fieldType.generics.dropFirst().first
            ?? TypeMeta.FieldType(typeID: TypeId.unknown.rawValue, nullable: true)

        let totalLength = Int(try buffer.readVarUInt32())
        try ensureCollectionLength(totalLength, label: "compatible_map")
        if totalLength == 0 {
            return [:]
        }

        var readCount = 0
        while readCount < totalLength {
            let header = try buffer.readUInt8()
            let trackKeyRef = (header & 0b0000_0001) != 0
            let keyNull = (header & 0b0000_0010) != 0
            let keyDeclared = (header & 0b0000_0100) != 0

            let trackValueRef = (header & 0b0000_1000) != 0
            let valueNull = (header & 0b0001_0000) != 0
            let valueDeclared = (header & 0b0010_0000) != 0

            if keyNull && valueNull {
                readCount += 1
                continue
            }

            if keyNull {
                _ = try readSkippedValue(
                    fieldType: valueType,
                    typeInfo: nil,
                    refMode: trackValueRef ? .tracking : .none,
                    readTypeInfo: !valueDeclared
                )
                readCount += 1
                continue
            }

            if valueNull {
                _ = try readSkippedValue(
                    fieldType: keyType,
                    typeInfo: nil,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: !keyDeclared
                )
                readCount += 1
                continue
            }

            let chunkSize = Int(try buffer.readUInt8())
            if chunkSize <= 0 || chunkSize > (totalLength - readCount) {
                throw invalidMapChunkSize(dynamic: false)
            }

            let keyTypeInfo = keyDeclared ? nil : try self.readTypeInfo()
            let valueTypeInfo = valueDeclared ? nil : try self.readTypeInfo()
            let alwaysAdvances =
                trackKeyRef || trackValueRef
                || skippedFieldReadAlwaysAdvances(keyType, typeInfo: keyTypeInfo)
                || skippedFieldReadAlwaysAdvances(valueType, typeInfo: valueTypeInfo)
            if alwaysAdvances {
                for _ in 0..<chunkSize {
                    _ = try readSkippedValue(
                        fieldType: keyType,
                        typeInfo: keyTypeInfo,
                        refMode: trackKeyRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                    _ = try readSkippedValue(
                        fieldType: valueType,
                        typeInfo: valueTypeInfo,
                        refMode: trackValueRef ? .tracking : .none,
                        readTypeInfo: false
                    )
                }
            } else {
                let chunkStart = buffer.cursor
                for _ in 0..<chunkSize {
                    _ = try readSkippedValue(
                        fieldType: keyType,
                        typeInfo: keyTypeInfo,
                        refMode: .none,
                        readTypeInfo: false
                    )
                    _ = try readSkippedValue(
                        fieldType: valueType,
                        typeInfo: valueTypeInfo,
                        refMode: .none,
                        readTypeInfo: false
                    )
                }
                try settleUnbackedContainerItems(
                    self, completed: chunkSize, startCursor: chunkStart)
            }
            readCount += chunkSize
        }

        return [:]
    }

    private func readSkippedUnion() throws -> Any {
        // An unknown compatible union has no statically bound case payload here.
        // Count this dynamic skip owner, then let the selected payload count its own
        // TypeInfo materialization. Static union readers do not enter this path.
        try enterDynamicAnyDepth()
        _ = try buffer.readVarUInt32()
        let value = try DynamicSerializer<Any>.read(
            self,
            refMode: .tracking,
            readTypeInfo: true
        )
        leaveDynamicAnyDepth()
        return value
    }
}
