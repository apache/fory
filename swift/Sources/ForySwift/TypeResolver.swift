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
    public let userTypeID: UInt32?
    public let kind: ForyTypeId
    public let registerByName: Bool
    public let namespace: MetaString?
    public let typeName: MetaString

    public init(
        userTypeID: UInt32?,
        kind: ForyTypeId,
        registerByName: Bool,
        namespace: MetaString?,
        typeName: MetaString
    ) {
        self.userTypeID = userTypeID
        self.kind = kind
        self.registerByName = registerByName
        self.namespace = namespace
        self.typeName = typeName
    }
}

private struct TypeNameKey: Hashable {
    let namespace: String
    let typeName: String
}

private struct TypeReader {
    let kind: ForyTypeId
    let reader: (ReadContext) throws -> Any
    let compatibleReader: (ReadContext, TypeMeta) throws -> Any
}

public final class TypeResolver {
    private var bySwiftType: [ObjectIdentifier: RegisteredTypeInfo] = [:]
    private var byUserTypeID: [UInt32: TypeReader] = [:]
    private var byTypeName: [TypeNameKey: TypeReader] = [:]

    public init() {}

    public func register<T: Serializer>(_ type: T.Type, id: UInt32) {
        let key = ObjectIdentifier(type)
        let info = RegisteredTypeInfo(
            userTypeID: id,
            kind: T.staticTypeId,
            registerByName: false,
            namespace: nil,
            typeName: MetaString.empty(specialChar1: "$", specialChar2: "_")
        )
        bySwiftType[key] = info
        byUserTypeID[id] = TypeReader(
            kind: T.staticTypeId,
            reader: { context in
                try T.foryRead(context, refMode: .none, readTypeInfo: false)
            },
            compatibleReader: { context, typeMeta in
                context.pushCompatibleTypeMeta(for: T.self, typeMeta)
                return try T.foryRead(context, refMode: .none, readTypeInfo: false)
            }
        )
    }

    public func register<T: Serializer>(_ type: T.Type, namespace: String, typeName: String) throws {
        let namespaceMeta = try MetaStringEncoder.namespace.encode(
            namespace,
            allowedEncodings: namespaceMetaStringEncodings
        )
        let typeNameMeta = try MetaStringEncoder.typeName.encode(
            typeName,
            allowedEncodings: typeNameMetaStringEncodings
        )
        let key = ObjectIdentifier(type)
        let info = RegisteredTypeInfo(
            userTypeID: nil,
            kind: T.staticTypeId,
            registerByName: true,
            namespace: namespaceMeta,
            typeName: typeNameMeta
        )
        bySwiftType[key] = info
        byTypeName[TypeNameKey(namespace: namespace, typeName: typeName)] = TypeReader(
            kind: T.staticTypeId,
            reader: { context in
                try T.foryRead(context, refMode: .none, readTypeInfo: false)
            },
            compatibleReader: { context, typeMeta in
                context.pushCompatibleTypeMeta(for: T.self, typeMeta)
                return try T.foryRead(context, refMode: .none, readTypeInfo: false)
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
        try readByUserTypeID(userTypeID, context: context, compatibleTypeMeta: nil)
    }

    public func readByUserTypeID(
        _ userTypeID: UInt32,
        context: ReadContext,
        compatibleTypeMeta: TypeMeta?
    ) throws -> Any {
        guard let entry = byUserTypeID[userTypeID] else {
            throw ForyError.typeNotRegistered("user_type_id=\(userTypeID)")
        }
        if let compatibleTypeMeta {
            return try entry.compatibleReader(context, compatibleTypeMeta)
        }
        return try entry.reader(context)
    }

    public func readByTypeName(namespace: String, typeName: String, context: ReadContext) throws -> Any {
        try readByTypeName(namespace: namespace, typeName: typeName, context: context, compatibleTypeMeta: nil)
    }

    public func readByTypeName(
        namespace: String,
        typeName: String,
        context: ReadContext,
        compatibleTypeMeta: TypeMeta?
    ) throws -> Any {
        guard let entry = byTypeName[TypeNameKey(namespace: namespace, typeName: typeName)] else {
            throw ForyError.typeNotRegistered("namespace=\(namespace), type=\(typeName)")
        }
        if let compatibleTypeMeta {
            return try entry.compatibleReader(context, compatibleTypeMeta)
        }
        return try entry.reader(context)
    }
}
