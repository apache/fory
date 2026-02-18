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

public protocol ForyDefault {
    static func foryDefault() -> Self
}

public protocol Serializer: ForyDefault {
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
}

public extension Serializer {
    static var isNullableType: Bool { false }

    static var isReferenceTrackableType: Bool { false }

    var foryIsNone: Bool { false }

    func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        if refMode != .none {
            if refMode == .tracking, Self.isReferenceTrackableType, let object = self as AnyObject? {
                if context.refWriter.tryWriteReference(writer: context.writer, object: object) {
                    return
                }
            } else {
                context.writer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try Self.foryWriteTypeInfo(context)
        }

        try foryWriteData(context, hasGenerics: hasGenerics)
    }

    static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Self {
        if refMode != .none {
            let rawFlag = try context.reader.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return Self.foryDefault()
            case .ref:
                let refID = try context.reader.readVarUInt32()
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
        if staticTypeId.isUserTypeKind {
            let info = try context.typeResolver.requireRegisteredTypeInfo(for: Self.self)
            context.writer.writeUInt8(UInt8(truncatingIfNeeded: info.kind.rawValue))
            context.writer.writeVarUInt32(info.userTypeID)
        } else {
            context.writer.writeUInt8(UInt8(truncatingIfNeeded: staticTypeId.rawValue))
        }
    }

    static func foryReadTypeInfo(_ context: ReadContext) throws {
        let rawTypeID = try context.reader.readVarUInt32()
        guard let typeID = ForyTypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown type id \(rawTypeID)")
        }

        if staticTypeId.isUserTypeKind {
            let info = try context.typeResolver.requireRegisteredTypeInfo(for: Self.self)
            if rawTypeID != info.kind.rawValue {
                throw ForyError.typeMismatch(expected: info.kind.rawValue, actual: rawTypeID)
            }
            let remoteUserTypeID = try context.reader.readVarUInt32()
            if remoteUserTypeID != info.userTypeID {
                throw ForyError.typeMismatch(expected: info.userTypeID, actual: remoteUserTypeID)
            }
        } else if typeID != staticTypeId {
            throw ForyError.typeMismatch(expected: staticTypeId.rawValue, actual: rawTypeID)
        }
    }
}
