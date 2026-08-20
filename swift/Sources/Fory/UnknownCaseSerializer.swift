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

private let unknownCaseGraphBytes =
    2 * MemoryLayout<Int>.stride
    + 2 * MemoryLayout<UInt32>.stride
    + MemoryLayout<Any?>.stride

public enum UnknownCaseSerializer {
    public static func writePayload(_ value: UnknownCase, _ context: WriteContext) throws {
        // Wire order is ref metadata first, then Any type metadata, then value bytes. Numeric
        // Any payloads are scalar values, so replay writes NotNullValue plus the original type id.
        if try writeTypedPayload(value, context) {
            return
        }
        try DynamicSerializer<Any>.write(
            value.value as Any,
            context,
            refMode: .tracking,
            writeTypeInfo: true
        )
    }

    public static func readPayload(caseId: UInt32, _ context: ReadContext) throws -> UnknownCase {
        let rawFlag = try context.buffer.readInt8()
        guard let flag = RefFlag(rawValue: rawFlag) else {
            throw ForyError.refError("invalid ref flag \(rawFlag)")
        }
        switch flag {
        case .null:
            return try materializeUnknownCase(
                caseId: caseId,
                typeId: TypeId.unknown.rawValue,
                value: nil,
                context
            )
        case .ref:
            let refID = try context.buffer.readVarUInt32()
            let value = try context.refReader.readRefValue(refID)
            return try materializeUnknownCase(
                caseId: caseId,
                typeId: TypeId.unknown.rawValue,
                value: value,
                context
            )
        case .refValue:
            let reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
            let (typeId, value) = try readNonNullPayload(context)
            let unknown = try materializeUnknownCase(
                caseId: caseId,
                typeId: typeId,
                value: value,
                context
            )
            if let reservedRefID {
                context.refReader.storeRef(value ?? NSNull(), at: reservedRefID)
            }
            return unknown
        case .notNullValue:
            let (typeId, value) = try readNonNullPayload(context)
            return try materializeUnknownCase(
                caseId: caseId,
                typeId: typeId,
                value: value,
                context
            )
        }
    }

    @inline(__always)
    private static func materializeUnknownCase(
        caseId: UInt32,
        typeId: UInt32,
        value: Any?,
        _ context: ReadContext
    ) throws -> UnknownCase {
        try context.reserveGraphMemory(unknownCaseGraphBytes)
        return UnknownCase(caseId: caseId, typeId: typeId, value: value)
    }

    private static func writeTypedPayload(_ unknown: UnknownCase, _ context: WriteContext) throws -> Bool {
        guard let typeId = TypeId(rawValue: unknown.typeId), let value = unknown.value else {
            return false
        }
        // Scalar replay bypasses DynamicSerializer, but the reader still materializes one
        // dynamically selected Any value. Enter only after a matching scalar type is known so the
        // non-scalar fallback remains responsible for its own dynamic depth.
        switch typeId {
        case .bool:
            guard let typed = value as? Bool else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeUInt8(typed ? 1 : 0)
            return true
        case .int8:
            guard let typed = value as? Int8 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeInt8(typed)
            return true
        case .uint8:
            guard let typed = value as? UInt8 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeUInt8(typed)
            return true
        case .int16:
            guard let typed = value as? Int16 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeInt16(typed)
            return true
        case .uint16:
            guard let typed = value as? UInt16 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeUInt16(typed)
            return true
        case .int32:
            guard let typed = value as? Int32 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeInt32(typed)
            return true
        case .varint32:
            guard let typed = value as? Int32 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeVarInt32(typed)
            return true
        case .uint32:
            guard let typed = value as? UInt32 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeUInt32(typed)
            return true
        case .varUInt32:
            guard let typed = value as? UInt32 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeVarUInt32(typed)
            return true
        case .int64:
            guard let typed = value as? Int64 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeInt64(typed)
            return true
        case .varint64:
            guard let typed = value as? Int64 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeVarInt64(typed)
            return true
        case .taggedInt64:
            guard let typed = value as? Int64 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeTaggedInt64(typed)
            return true
        case .uint64:
            guard let typed = value as? UInt64 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeUInt64(typed)
            return true
        case .varUInt64:
            guard let typed = value as? UInt64 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeVarUInt64(typed)
            return true
        case .taggedUInt64:
            guard let typed = value as? UInt64 else { return false }
            try context.enterDynamicAnyDepth()
            defer { context.leaveDynamicAnyDepth() }
            writeRefAndType(typeId, context)
            context.buffer.writeTaggedUInt64(typed)
            return true
        default:
            return false
        }
    }

    private static func writeRefAndType(_ typeId: TypeId, _ context: WriteContext) {
        context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
        context.buffer.writeUInt8(UInt8(truncatingIfNeeded: typeId.rawValue))
    }

    private static func readNonNullPayload(_ context: ReadContext) throws -> (UInt32, Any?) {
        // UnknownCase owns the union payload envelope only. The envelope is not
        // a nested dynamic value, so depth checks belong to the decoded payload
        // serializer or the final root-context reset, not this carrier reader.
        let typeInfo = try context.readTypeInfo()
        let value = try context.readAnyValue(typeInfo: typeInfo)
        return (typeInfo.typeID.rawValue, value is ForyAnyNullValue ? nil : value)
    }
}
