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

public extension Serializer where Self: RawRepresentable, RawValue == UInt32 {
    static func foryDefault() -> Self {
        guard let zero = Self(rawValue: 0) else {
            fatalError("enum \(Self.self) must define case with rawValue 0")
        }
        return zero
    }

    static var staticTypeId: ForyTypeId {
        .enumType
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.writer.writeVarUInt32(rawValue)
    }

    static func foryReadData(_ context: ReadContext) throws -> Self {
        let ordinal = try context.reader.readVarUInt32()
        guard let value = Self(rawValue: ordinal) else {
            throw ForyError.invalidData("unknown enum ordinal \(ordinal)")
        }
        return value
    }
}

public enum ForyUnion2<A: Serializer, B: Serializer>: Serializer {
    case first(A)
    case second(B)

    public static func foryDefault() -> ForyUnion2<A, B> {
        .first(A.foryDefault())
    }

    public static var staticTypeId: ForyTypeId {
        .typedUnion
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        switch self {
        case .first(let value):
            context.writer.writeVarUInt32(0)
            try value.foryWrite(context, refMode: .tracking, writeTypeInfo: true, hasGenerics: false)
        case .second(let value):
            context.writer.writeVarUInt32(1)
            try value.foryWrite(context, refMode: .tracking, writeTypeInfo: true, hasGenerics: false)
        }
    }

    public static func foryReadData(_ context: ReadContext) throws -> ForyUnion2<A, B> {
        let tag = try context.reader.readVarUInt32()
        switch tag {
        case 0:
            return .first(try A.foryRead(context, refMode: .tracking, readTypeInfo: true))
        case 1:
            return .second(try B.foryRead(context, refMode: .tracking, readTypeInfo: true))
        default:
            throw ForyError.invalidData("unknown union tag \(tag)")
        }
    }
}

extension ForyUnion2: Equatable where A: Equatable, B: Equatable {}
