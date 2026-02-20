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

extension Optional: Serializer where Wrapped: Serializer {
    public static func foryDefault() -> Optional<Wrapped> {
        nil
    }

    public static var staticTypeId: ForyTypeId {
        Wrapped.staticTypeId
    }

    public static var isNullableType: Bool {
        true
    }

    public static var isReferenceTrackableType: Bool {
        Wrapped.isReferenceTrackableType
    }

    public var foryIsNone: Bool {
        self == nil
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        guard case .some(let wrapped) = self else {
            throw ForyError.invalidData("Option.none cannot write raw payload")
        }
        try wrapped.foryWriteData(context, hasGenerics: hasGenerics)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Optional<Wrapped> {
        .some(try Wrapped.foryReadData(context))
    }

    public static func foryWriteTypeInfo(_ context: WriteContext) throws {
        try Wrapped.foryWriteTypeInfo(context)
    }

    public static func foryReadTypeInfo(_ context: ReadContext) throws {
        try Wrapped.foryReadTypeInfo(context)
    }

    public func foryWrite(
        _ context: WriteContext,
        refMode: RefMode,
        writeTypeInfo: Bool,
        hasGenerics: Bool
    ) throws {
        switch refMode {
        case .none:
            guard case .some(let wrapped) = self else {
                throw ForyError.invalidData("Option.none with RefMode.none")
            }
            try wrapped.foryWrite(context, refMode: .none, writeTypeInfo: writeTypeInfo, hasGenerics: hasGenerics)
        case .nullOnly:
            guard case .some(let wrapped) = self else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            context.buffer.writeInt8(RefFlag.notNullValue.rawValue)
            try wrapped.foryWrite(context, refMode: .none, writeTypeInfo: writeTypeInfo, hasGenerics: hasGenerics)
        case .tracking:
            guard case .some(let wrapped) = self else {
                context.buffer.writeInt8(RefFlag.null.rawValue)
                return
            }
            try wrapped.foryWrite(context, refMode: .tracking, writeTypeInfo: writeTypeInfo, hasGenerics: hasGenerics)
        }
    }

    public static func foryRead(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Optional<Wrapped> {
        switch refMode {
        case .none:
            return .some(try Wrapped.foryRead(context, refMode: .none, readTypeInfo: readTypeInfo))
        case .nullOnly:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            return .some(try Wrapped.foryRead(context, refMode: .none, readTypeInfo: readTypeInfo))
        case .tracking:
            let refFlag = try context.buffer.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            context.buffer.moveBack(1)
            return .some(try Wrapped.foryRead(context, refMode: .tracking, readTypeInfo: readTypeInfo))
        }
    }
}
