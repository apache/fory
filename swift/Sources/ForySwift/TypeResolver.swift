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

public struct RegisteredTypeInfo {
    public let userTypeID: UInt32
    public let kind: ForyTypeId
}

public final class TypeResolver {
    private var bySwiftType: [ObjectIdentifier: RegisteredTypeInfo] = [:]
    private var byUserTypeID: [UInt32: (kind: ForyTypeId, reader: (ReadContext) throws -> Any)] = [:]

    public init() {}

    public func register<T: Serializer>(_ type: T.Type, id: UInt32) {
        let key = ObjectIdentifier(type)
        let info = RegisteredTypeInfo(userTypeID: id, kind: T.staticTypeId)
        bySwiftType[key] = info
        byUserTypeID[id] = (
            kind: T.staticTypeId,
            reader: { context in
                try T.foryRead(context, refMode: .none, readTypeInfo: false)
            }
        )
    }

    public func registeredTypeInfo<T: Serializer>(for type: T.Type) -> RegisteredTypeInfo? {
        bySwiftType[ObjectIdentifier(type)]
    }

    public func registeredTypeInfo(for type: any Serializer.Type) -> RegisteredTypeInfo? {
        bySwiftType[ObjectIdentifier(type)]
    }

    public func requireRegisteredTypeInfo<T: Serializer>(for type: T.Type) throws -> RegisteredTypeInfo {
        if let info = bySwiftType[ObjectIdentifier(type)] {
            return info
        }
        throw ForyError.typeNotRegistered("\(type) is not registered")
    }

    public func readByUserTypeID(_ userTypeID: UInt32, context: ReadContext) throws -> Any {
        guard let entry = byUserTypeID[userTypeID] else {
            throw ForyError.typeNotRegistered("user_type_id=\(userTypeID)")
        }
        return try entry.reader(context)
    }
}
