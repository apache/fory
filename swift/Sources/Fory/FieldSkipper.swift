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

public extension ReadContext {
    func skipFieldValue(_ fieldType: TypeMeta.FieldType) throws {
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
            return try readAnyValue(typeInfo: typeInfo)
        }
        if readTypeInfo {
            let typeInfo = try self.readTypeInfo()
            return try readAnyValue(typeInfo: typeInfo)
        }

        guard let resolvedTypeID = TypeId(rawValue: fieldType.typeID) else {
            throw ForyError.invalidData("unknown compatible field type id \(fieldType.typeID)")
        }

        switch resolvedTypeID {
        case .none:
            return ForyAnyNullValue()
        case .bool:
            return try Bool.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int8:
            return try Int8.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int16:
            return try Int16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int32:
            return try ForyInt32Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varint32:
            return try Int32.foryRead(self, refMode: .none, readTypeInfo: false)
        case .int64:
            return try ForyInt64Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varint64:
            return try Int64.foryRead(self, refMode: .none, readTypeInfo: false)
        case .taggedInt64:
            return try ForyInt64Tagged.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint8:
            return try UInt8.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint16:
            return try UInt16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint32:
            return try ForyUInt32Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varUInt32:
            return try UInt32.foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint64:
            return try ForyUInt64Fixed.foryRead(self, refMode: .none, readTypeInfo: false)
        case .varUInt64:
            return try UInt64.foryRead(self, refMode: .none, readTypeInfo: false)
        case .taggedUInt64:
            return try ForyUInt64Tagged.foryRead(self, refMode: .none, readTypeInfo: false)
        case .float16:
            return try Float16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .bfloat16:
            return try BFloat16.foryRead(self, refMode: .none, readTypeInfo: false)
        case .float32:
            return try Float.foryRead(self, refMode: .none, readTypeInfo: false)
        case .float64:
            return try Double.foryRead(self, refMode: .none, readTypeInfo: false)
        case .string:
            return try String.foryRead(self, refMode: .none, readTypeInfo: false)
        case .duration:
            return try Duration.foryRead(self, refMode: .none, readTypeInfo: false)
        case .timestamp:
            return try Date.foryRead(self, refMode: .none, readTypeInfo: false)
        case .date:
            return try ForyDate.foryRead(self, refMode: .none, readTypeInfo: false)
        case .binary, .uint8Array:
            return try Data.foryRead(self, refMode: .none, readTypeInfo: false)
        case .boolArray:
            return try [Bool].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int8Array:
            return try [Int8].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int16Array:
            return try [Int16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int32Array:
            return try [Int32].foryRead(self, refMode: .none, readTypeInfo: false)
        case .int64Array:
            return try [Int64].foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint16Array:
            return try [UInt16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint32Array:
            return try [UInt32].foryRead(self, refMode: .none, readTypeInfo: false)
        case .uint64Array:
            return try [UInt64].foryRead(self, refMode: .none, readTypeInfo: false)
        case .float16Array:
            return try [Float16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .bfloat16Array:
            return try [BFloat16].foryRead(self, refMode: .none, readTypeInfo: false)
        case .float32Array:
            return try [Float].foryRead(self, refMode: .none, readTypeInfo: false)
        case .float64Array:
            return try [Double].foryRead(self, refMode: .none, readTypeInfo: false)
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

    private func readSkippedCollection(
        fieldType: TypeMeta.FieldType
    ) throws -> [Any] {
        let elementFieldType = fieldType.generics.first
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
                } else {
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
        let keyType = fieldType.generics.first
            ?? TypeMeta.FieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        let valueType = fieldType.generics.dropFirst().first
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
                let valueTypeInfo = valueDeclared ? nil : try self.readTypeInfo()
                _ = try readSkippedValue(
                    fieldType: valueType,
                    typeInfo: valueTypeInfo,
                    refMode: trackValueRef ? .tracking : .none,
                    readTypeInfo: false
                )
                readCount += 1
                continue
            }

            if valueNull {
                let keyTypeInfo = keyDeclared ? nil : try self.readTypeInfo()
                _ = try readSkippedValue(
                    fieldType: keyType,
                    typeInfo: keyTypeInfo,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: false
                )
                readCount += 1
                continue
            }

            let chunkSize = Int(try buffer.readUInt8())
            if chunkSize <= 0 {
                throw ForyError.invalidData("invalid map chunk size \(chunkSize)")
            }
            if chunkSize > (totalLength - readCount) {
                throw ForyError.invalidData("map chunk size exceeds remaining entries")
            }

            let keyTypeInfo = keyDeclared ? nil : try self.readTypeInfo()
            let valueTypeInfo = valueDeclared ? nil : try self.readTypeInfo()

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
            readCount += chunkSize
        }

        return [:]
    }

    private func readSkippedUnion() throws -> Any {
        _ = try buffer.readVarUInt32()
        return try readAny(refMode: .tracking, readTypeInfo: true) ?? ForyAnyNullValue()
    }
}
