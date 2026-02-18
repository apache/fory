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

extension Optional: ForyDefault where Wrapped: Serializer {
    public static func defaultValue() -> Optional<Wrapped> {
        nil
    }
}

extension Optional: Serializer where Wrapped: Serializer {
    public static var staticTypeId: ForyTypeId {
        Wrapped.staticTypeId
    }

    public static var isNullableType: Bool {
        true
    }

    public static var isReferenceTrackableType: Bool {
        Wrapped.isReferenceTrackableType
    }

    public var isNilValue: Bool {
        self == nil
    }

    public func writeData(_ context: WriteContext, hasGenerics: Bool) throws {
        guard case .some(let wrapped) = self else {
            throw ForyError.invalidData("Option.none cannot write raw payload")
        }
        try wrapped.writeData(context, hasGenerics: hasGenerics)
    }

    public static func readData(_ context: ReadContext) throws -> Optional<Wrapped> {
        .some(try Wrapped.readData(context))
    }

    public static func writeTypeInfo(_ context: WriteContext) throws {
        try Wrapped.writeTypeInfo(context)
    }

    public static func readTypeInfo(_ context: ReadContext) throws {
        try Wrapped.readTypeInfo(context)
    }

    public func write(
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
            try wrapped.write(context, refMode: .none, writeTypeInfo: writeTypeInfo, hasGenerics: hasGenerics)
        case .nullOnly:
            guard case .some(let wrapped) = self else {
                context.writer.writeInt8(RefFlag.null.rawValue)
                return
            }
            context.writer.writeInt8(RefFlag.notNullValue.rawValue)
            try wrapped.write(context, refMode: .none, writeTypeInfo: writeTypeInfo, hasGenerics: hasGenerics)
        case .tracking:
            guard case .some(let wrapped) = self else {
                context.writer.writeInt8(RefFlag.null.rawValue)
                return
            }
            try wrapped.write(context, refMode: .tracking, writeTypeInfo: writeTypeInfo, hasGenerics: hasGenerics)
        }
    }

    public static func read(
        _ context: ReadContext,
        refMode: RefMode,
        readTypeInfo: Bool
    ) throws -> Optional<Wrapped> {
        switch refMode {
        case .none:
            return .some(try Wrapped.read(context, refMode: .none, readTypeInfo: readTypeInfo))
        case .nullOnly:
            let refFlag = try context.reader.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            return .some(try Wrapped.read(context, refMode: .none, readTypeInfo: readTypeInfo))
        case .tracking:
            let refFlag = try context.reader.readInt8()
            if refFlag == RefFlag.null.rawValue {
                return nil
            }
            context.reader.moveBack(1)
            return .some(try Wrapped.read(context, refMode: .tracking, readTypeInfo: readTypeInfo))
        }
    }
}
