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

public struct ForyNoneValue: Serializer {
    public init() {}

    public static func foryDefault() -> ForyNoneValue {
        ForyNoneValue()
    }

    public static var staticTypeId: ForyTypeId {
        .none
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = context
        _ = hasGenerics
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyNoneValue {
        _ = context
        return ForyNoneValue()
    }
}

public struct AnySerializerValue: Serializer {
    public var value: any Serializer

    public init(_ value: any Serializer) {
        self.value = value
    }

    public init(any value: Any) throws {
        guard let serializer = value as? any Serializer else {
            throw ForyError.invalidData(
                "AnySerializerValue requires Any values conforming to Serializer, got \(type(of: value))"
            )
        }
        self.value = serializer
    }

    public init(anyObject value: AnyObject) throws {
        guard let serializer = value as? any Serializer else {
            throw ForyError.invalidData(
                "AnySerializerValue requires AnyObject values conforming to Serializer, got \(type(of: value))"
            )
        }
        self.value = serializer
    }

    public static func foryDefault() -> AnySerializerValue {
        AnySerializerValue(ForyNoneValue())
    }

    public static var staticTypeId: ForyTypeId {
        .unknown
    }

    public static var isNullableType: Bool {
        true
    }

    public static var isReferenceTrackableType: Bool {
        true
    }

    public var foryIsNone: Bool {
        value.foryIsNone
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        try value.foryWriteData(context, hasGenerics: hasGenerics)
    }

    public static func foryReadData(_ context: ReadContext) throws -> AnySerializerValue {
        guard let typeInfo = context.dynamicTypeInfo(for: Self.self) else {
            throw ForyError.invalidData("AnySerializerValue requires dynamic type info")
        }
        context.clearDynamicTypeInfo(for: Self.self)
        return AnySerializerValue(try context.typeResolver.readDynamicValue(typeInfo: typeInfo, context: context))
    }

    public static func foryWriteTypeInfo(_ context: WriteContext) throws {
        _ = context
        throw ForyError.invalidData("AnySerializerValue type info is dynamic")
    }

    public func foryWriteTypeInfo(_ context: WriteContext) throws {
        try type(of: value).foryWriteTypeInfo(context)
    }

    public static func foryReadTypeInfo(_ context: ReadContext) throws {
        let typeInfo = try context.typeResolver.readDynamicTypeInfo(context: context)
        context.setDynamicTypeInfo(for: Self.self, typeInfo)
    }

    public func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        if refMode != .none {
            if value.foryIsNone {
                context.writer.writeInt8(RefFlag.null.rawValue)
                return
            }
            let valueType = type(of: value)
            if refMode == .tracking, valueType.isReferenceTrackableType, let object = value as AnyObject? {
                if context.refWriter.tryWriteReference(writer: context.writer, object: object) {
                    return
                }
            } else {
                context.writer.writeInt8(RefFlag.notNullValue.rawValue)
            }
        }

        if writeTypeInfo {
            try foryWriteTypeInfo(context)
        }
        try value.foryWriteData(context, hasGenerics: hasGenerics)
    }

    public static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> AnySerializerValue {
        if refMode != .none {
            let rawFlag = try context.reader.readInt8()
            guard let flag = RefFlag(rawValue: rawFlag) else {
                throw ForyError.refError("invalid ref flag \(rawFlag)")
            }

            switch flag {
            case .null:
                return .foryDefault()
            case .ref:
                let refID = try context.reader.readVarUInt32()
                let referenced = try context.refReader.readRefValue(refID)
                guard let value = referenced as? AnySerializerValue else {
                    throw ForyError.refError("ref_id \(refID) has unexpected runtime type")
                }
                return value
            case .refValue:
                let reservedRefID = context.refReader.reserveRefID()
                context.pushPendingReference(reservedRefID)
                if readTypeInfo {
                    try foryReadTypeInfo(context)
                }
                let value = try foryReadData(context)
                context.finishPendingReferenceIfNeeded(value)
                context.popPendingReference()
                return value
            case .notNullValue:
                break
            }
        }

        if readTypeInfo {
            try foryReadTypeInfo(context)
        }
        return try foryReadData(context)
    }
}
