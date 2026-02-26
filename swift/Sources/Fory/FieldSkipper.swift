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

public enum FieldSkipper {
    public static func skipFieldValue(
        context: ReadContext,
        fieldType: TypeMetaFieldType
    ) throws {
        _ = try readFieldValue(
            context: context,
            fieldType: fieldType,
            readTypeInfo: needsTypeInfoForField(fieldType.typeID)
        )
    }

    private static func needsTypeInfoForField(_ typeID: UInt32) -> Bool {
        guard let resolved = TypeId(rawValue: typeID) else {
            return true
        }
        return TypeId.needsTypeInfoForField(resolved)
    }

    private static func readFieldValue(
        context: ReadContext,
        fieldType: TypeMetaFieldType,
        runtimeTypeInfo: DynamicTypeInfo? = nil,
        readTypeInfo: Bool
    ) throws -> Any? {
        let refMode = RefMode.from(nullable: fieldType.nullable, trackRef: fieldType.trackRef)
        return try readValueWithRefMode(
            context: context,
            fieldType: fieldType,
            runtimeTypeInfo: runtimeTypeInfo,
            refMode: refMode,
            readTypeInfo: readTypeInfo
        )
    }

    private static func readValueWithRefMode(
        context: ReadContext,
        fieldType: TypeMetaFieldType,
        runtimeTypeInfo: DynamicTypeInfo?,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Any? {
        switch refMode {
        case .none:
            return try readFieldPayload(
                context: context,
                fieldType: fieldType,
                runtimeTypeInfo: runtimeTypeInfo,
                readTypeInfo: readTypeInfo
            )
        case .nullOnly:
            let flag = try context.buffer.readInt8()
            if flag == RefFlag.null.rawValue {
                return nil
            }
            guard flag == RefFlag.notNullValue.rawValue else {
                throw ForyError.invalidData("unexpected nullOnly flag \(flag)")
            }
            return try readFieldPayload(
                context: context,
                fieldType: fieldType,
                runtimeTypeInfo: runtimeTypeInfo,
                readTypeInfo: readTypeInfo
            )
        case .tracking:
            let rawFlag = try context.buffer.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.invalidData("unexpected tracking flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return nil
            case .ref:
                let refID = try context.buffer.readVarUInt32()
                return try context.refReader.readRefValue(refID)
            case .refValue:
                let refID = context.refReader.reserveRefID()
                let value = try readFieldPayload(
                    context: context,
                    fieldType: fieldType,
                    runtimeTypeInfo: runtimeTypeInfo,
                    readTypeInfo: readTypeInfo
                )
                context.refReader.storeRef(value, at: refID)
                return value
            case .notNullValue:
                return try readFieldPayload(
                    context: context,
                    fieldType: fieldType,
                    runtimeTypeInfo: runtimeTypeInfo,
                    readTypeInfo: readTypeInfo
                )
            }
        }
    }

    private static func readFieldPayload(
        context: ReadContext,
        fieldType: TypeMetaFieldType,
        runtimeTypeInfo: DynamicTypeInfo?,
        readTypeInfo: Bool
    ) throws -> Any {
        if let runtimeTypeInfo {
            return try context.typeResolver.readDynamicValue(typeInfo: runtimeTypeInfo, context: context)
        }
        if readTypeInfo {
            let typeInfo = try context.typeResolver.readDynamicTypeInfo(context: context)
            return try context.typeResolver.readDynamicValue(typeInfo: typeInfo, context: context)
        }

        guard let resolvedTypeID = TypeId(rawValue: fieldType.typeID) else {
            throw ForyError.invalidData("unknown compatible field type id \(fieldType.typeID)")
        }

        switch resolvedTypeID {
        case .none:
            return ForyAnyNullValue()
        case .bool:
            return try Bool.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int8:
            return try Int8.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int16:
            return try Int16.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int32:
            return try ForyInt32Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varint32:
            return try Int32.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int64:
            return try ForyInt64Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varint64:
            return try Int64.foryRead(context, refMode: .none, readTypeInfo: false)
        case .taggedInt64:
            return try ForyInt64Tagged.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint8:
            return try UInt8.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint16:
            return try UInt16.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint32:
            return try ForyUInt32Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varUInt32:
            return try UInt32.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint64:
            return try ForyUInt64Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varUInt64:
            return try UInt64.foryRead(context, refMode: .none, readTypeInfo: false)
        case .taggedUInt64:
            return try ForyUInt64Tagged.foryRead(context, refMode: .none, readTypeInfo: false)
        case .float16:
            return try Float16.foryRead(context, refMode: .none, readTypeInfo: false)
        case .bfloat16:
            return try BFloat16.foryRead(context, refMode: .none, readTypeInfo: false)
        case .float32:
            return try Float.foryRead(context, refMode: .none, readTypeInfo: false)
        case .float64:
            return try Double.foryRead(context, refMode: .none, readTypeInfo: false)
        case .string:
            return try String.foryRead(context, refMode: .none, readTypeInfo: false)
        case .duration:
            return try Duration.foryRead(context, refMode: .none, readTypeInfo: false)
        case .timestamp:
            return try Date.foryRead(context, refMode: .none, readTypeInfo: false)
        case .date:
            return try ForyDate.foryRead(context, refMode: .none, readTypeInfo: false)
        case .binary, .uint8Array:
            return try Data.foryRead(context, refMode: .none, readTypeInfo: false)
        case .boolArray:
            return try [Bool].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int8Array:
            return try [Int8].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int16Array:
            return try [Int16].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int32Array:
            return try [Int32].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int64Array:
            return try [Int64].foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint16Array:
            return try [UInt16].foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint32Array:
            return try [UInt32].foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint64Array:
            return try [UInt64].foryRead(context, refMode: .none, readTypeInfo: false)
        case .float16Array:
            return try [Float16].foryRead(context, refMode: .none, readTypeInfo: false)
        case .bfloat16Array:
            return try [BFloat16].foryRead(context, refMode: .none, readTypeInfo: false)
        case .float32Array:
            return try [Float].foryRead(context, refMode: .none, readTypeInfo: false)
        case .float64Array:
            return try [Double].foryRead(context, refMode: .none, readTypeInfo: false)
        case .array, .list:
            return try readCollection(context: context, fieldType: fieldType)
        case .set:
            return try readSet(context: context, fieldType: fieldType)
        case .map:
            return try readMap(context: context, fieldType: fieldType)
        case .enumType, .namedEnum:
            return try context.buffer.readVarUInt32()
        default:
            throw ForyError.invalidData("unsupported compatible field type id \(fieldType.typeID)")
        }
    }

    private static func readCollection(
        context: ReadContext,
        fieldType: TypeMetaFieldType
    ) throws -> [Any] {
        let elementFieldType = fieldType.generics.first
            ?? TypeMetaFieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        let length = Int(try context.buffer.readVarUInt32())
        try context.ensureCollectionLength(length, label: "compatible_collection")
        if length == 0 {
            return []
        }

        let header = try context.buffer.readUInt8()
        let trackRef = (header & 0b0000_0001) != 0
        let hasNull = (header & 0b0000_0010) != 0
        let declared = (header & 0b0000_0100) != 0
        let sameType = (header & 0b0000_1000) != 0

        var runtimeTypeInfo: DynamicTypeInfo?
        if sameType, !declared {
            runtimeTypeInfo = try context.typeResolver.readDynamicTypeInfo(context: context)
        }

        for _ in 0..<length {
            if sameType {
                if trackRef {
                    _ = try readValueWithRefMode(
                        context: context,
                        fieldType: elementFieldType,
                        runtimeTypeInfo: runtimeTypeInfo,
                        refMode: .tracking,
                        readTypeInfo: false
                    )
                } else if hasNull {
                    let refFlag = try context.buffer.readInt8()
                    if refFlag == RefFlag.null.rawValue {
                        continue
                    }
                    if refFlag != RefFlag.notNullValue.rawValue {
                        throw ForyError.invalidData("invalid collection nullability flag \(refFlag)")
                    }
                    _ = try readFieldPayload(
                        context: context,
                        fieldType: elementFieldType,
                        runtimeTypeInfo: runtimeTypeInfo,
                        readTypeInfo: false
                    )
                } else {
                    _ = try readFieldPayload(
                        context: context,
                        fieldType: elementFieldType,
                        runtimeTypeInfo: runtimeTypeInfo,
                        readTypeInfo: false
                    )
                }
                continue
            }

            if trackRef {
                _ = try readValueWithRefMode(
                    context: context,
                    fieldType: elementFieldType,
                    runtimeTypeInfo: nil,
                    refMode: .tracking,
                    readTypeInfo: true
                )
            } else if hasNull {
                let refFlag = try context.buffer.readInt8()
                if refFlag == RefFlag.null.rawValue {
                    continue
                }
                if refFlag != RefFlag.notNullValue.rawValue {
                    throw ForyError.invalidData("invalid collection nullability flag \(refFlag)")
                }
                _ = try readFieldPayload(
                    context: context,
                    fieldType: elementFieldType,
                    runtimeTypeInfo: nil,
                    readTypeInfo: true
                )
            } else {
                _ = try readFieldPayload(
                    context: context,
                    fieldType: elementFieldType,
                    runtimeTypeInfo: nil,
                    readTypeInfo: true
                )
            }
        }

        return []
    }

    private static func readSet(
        context: ReadContext,
        fieldType: TypeMetaFieldType
    ) throws -> Set<AnyHashable> {
        _ = try readCollection(context: context, fieldType: fieldType)
        return []
    }

    private static func readMap(
        context: ReadContext,
        fieldType: TypeMetaFieldType
    ) throws -> [AnyHashable: Any] {
        let keyType = fieldType.generics.first
            ?? TypeMetaFieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        let valueType = fieldType.generics.dropFirst().first
            ?? TypeMetaFieldType(typeID: TypeId.unknown.rawValue, nullable: true)

        let totalLength = Int(try context.buffer.readVarUInt32())
        try context.ensureCollectionLength(totalLength, label: "compatible_map")
        if totalLength == 0 {
            return [:]
        }

        var readCount = 0
        while readCount < totalLength {
            let header = try context.buffer.readUInt8()
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
                let valueRuntimeType = valueDeclared ? nil : try context.typeResolver.readDynamicTypeInfo(context: context)
                _ = try readValueWithRefMode(
                    context: context,
                    fieldType: valueType,
                    runtimeTypeInfo: valueRuntimeType,
                    refMode: trackValueRef ? .tracking : .none,
                    readTypeInfo: false
                )
                readCount += 1
                continue
            }

            if valueNull {
                let keyRuntimeType = keyDeclared ? nil : try context.typeResolver.readDynamicTypeInfo(context: context)
                _ = try readValueWithRefMode(
                    context: context,
                    fieldType: keyType,
                    runtimeTypeInfo: keyRuntimeType,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: false
                )
                readCount += 1
                continue
            }

            let chunkSize = Int(try context.buffer.readUInt8())
            if chunkSize <= 0 {
                throw ForyError.invalidData("invalid map chunk size \(chunkSize)")
            }
            if chunkSize > (totalLength - readCount) {
                throw ForyError.invalidData("map chunk size exceeds remaining entries")
            }

            let keyRuntimeType = keyDeclared ? nil : try context.typeResolver.readDynamicTypeInfo(context: context)
            let valueRuntimeType = valueDeclared ? nil : try context.typeResolver.readDynamicTypeInfo(context: context)

            for _ in 0..<chunkSize {
                _ = try readValueWithRefMode(
                    context: context,
                    fieldType: keyType,
                    runtimeTypeInfo: keyRuntimeType,
                    refMode: trackKeyRef ? .tracking : .none,
                    readTypeInfo: false
                )
                _ = try readValueWithRefMode(
                    context: context,
                    fieldType: valueType,
                    runtimeTypeInfo: valueRuntimeType,
                    refMode: trackValueRef ? .tracking : .none,
                    readTypeInfo: false
                )
            }
            readCount += chunkSize
        }

        return [:]
    }
}
