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

public struct BFloat16: Serializer, Equatable, Hashable, Sendable {
    public var rawValue: UInt16

    public init(rawValue: UInt16 = 0) {
        self.rawValue = rawValue
    }

    public static func foryDefault() -> BFloat16 { .init() }
    public static var staticTypeId: TypeId { .bfloat16 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeUInt16(rawValue)
    }

    public static func foryReadData(_ context: ReadContext) throws -> BFloat16 {
        .init(rawValue: try context.buffer.readUInt16())
    }
}

extension Float16: Serializer {
    public static var staticTypeId: TypeId { .float16 }

    public static func foryDefault() -> Float16 { 0 }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.buffer.writeUInt16(bitPattern)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Float16 {
        Float16(bitPattern: try context.buffer.readUInt16())
    }
}
