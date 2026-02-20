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

private enum DynamicRegistrationMode {
    case idOnly
    case nameOnly
    case mixed
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
    private var registrationModeByKind: [ForyTypeId: DynamicRegistrationMode] = [:]

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
        markRegistrationMode(kind: info.kind, registerByName: false)
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
        markRegistrationMode(kind: info.kind, registerByName: true)
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

    public func readDynamicTypeInfo(context: ReadContext) throws -> DynamicTypeInfo {
        let rawTypeID = try context.reader.readVarUInt32()
        guard let wireTypeID = ForyTypeId(rawValue: rawTypeID) else {
            throw ForyError.invalidData("unknown dynamic type id \(rawTypeID)")
        }

        switch wireTypeID {
        case .compatibleStruct, .namedCompatibleStruct:
            let typeMeta = try context.readCompatibleTypeMeta()
            if typeMeta.registerByName {
                return DynamicTypeInfo(
                    wireTypeID: wireTypeID,
                    userTypeID: nil,
                    namespace: typeMeta.namespace,
                    typeName: typeMeta.typeName,
                    compatibleTypeMeta: typeMeta
                )
            }
            return DynamicTypeInfo(
                wireTypeID: wireTypeID,
                userTypeID: typeMeta.userTypeID,
                namespace: nil,
                typeName: nil,
                compatibleTypeMeta: typeMeta
            )
        case .namedStruct, .namedEnum, .namedExt, .namedUnion:
            let namespace = try Self.readMetaString(
                reader: context.reader,
                decoder: .namespace,
                encodings: namespaceMetaStringEncodings
            )
            let typeName = try Self.readMetaString(
                reader: context.reader,
                decoder: .typeName,
                encodings: typeNameMetaStringEncodings
            )
            return DynamicTypeInfo(
                wireTypeID: wireTypeID,
                userTypeID: nil,
                namespace: namespace,
                typeName: typeName,
                compatibleTypeMeta: nil
            )
        case .structType, .enumType, .ext, .typedUnion:
            switch try dynamicRegistrationMode(for: wireTypeID) {
            case .idOnly:
                return DynamicTypeInfo(
                    wireTypeID: wireTypeID,
                    userTypeID: try context.reader.readVarUInt32(),
                    namespace: nil,
                    typeName: nil,
                    compatibleTypeMeta: nil
                )
            case .nameOnly:
                let namespace = try Self.readMetaString(
                    reader: context.reader,
                    decoder: .namespace,
                    encodings: namespaceMetaStringEncodings
                )
                let typeName = try Self.readMetaString(
                    reader: context.reader,
                    decoder: .typeName,
                    encodings: typeNameMetaStringEncodings
                )
                return DynamicTypeInfo(
                    wireTypeID: wireTypeID,
                    userTypeID: nil,
                    namespace: namespace,
                    typeName: typeName,
                    compatibleTypeMeta: nil
                )
            case .mixed:
                throw ForyError.invalidData("ambiguous dynamic type registration mode for \(wireTypeID)")
            }
        default:
            return DynamicTypeInfo(
                wireTypeID: wireTypeID,
                userTypeID: nil,
                namespace: nil,
                typeName: nil,
                compatibleTypeMeta: nil
            )
        }
    }

    public func readDynamicValue(typeInfo: DynamicTypeInfo, context: ReadContext) throws -> Any {
        let value: Any
        switch typeInfo.wireTypeID {
        case .bool:
            value = try Bool.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int8:
            value = try Int8.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int16:
            value = try Int16.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int32:
            value = try ForyInt32Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varint32:
            value = try Int32.foryRead(context, refMode: .none, readTypeInfo: false)
        case .int64:
            value = try ForyInt64Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varint64:
            value = try Int64.foryRead(context, refMode: .none, readTypeInfo: false)
        case .taggedInt64:
            value = try ForyInt64Tagged.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint8:
            value = try UInt8.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint16:
            value = try UInt16.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint32:
            value = try ForyUInt32Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varUInt32:
            value = try UInt32.foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint64:
            value = try ForyUInt64Fixed.foryRead(context, refMode: .none, readTypeInfo: false)
        case .varUInt64:
            value = try UInt64.foryRead(context, refMode: .none, readTypeInfo: false)
        case .taggedUInt64:
            value = try ForyUInt64Tagged.foryRead(context, refMode: .none, readTypeInfo: false)
        case .float32:
            value = try Float.foryRead(context, refMode: .none, readTypeInfo: false)
        case .float64:
            value = try Double.foryRead(context, refMode: .none, readTypeInfo: false)
        case .string:
            value = try String.foryRead(context, refMode: .none, readTypeInfo: false)
        case .timestamp:
            value = try Date.foryRead(context, refMode: .none, readTypeInfo: false)
        case .date:
            value = try ForyDate.foryRead(context, refMode: .none, readTypeInfo: false)
        case .binary, .uint8Array:
            value = try Data.foryRead(context, refMode: .none, readTypeInfo: false)
        case .boolArray:
            value = try [Bool].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int8Array:
            value = try [Int8].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int16Array:
            value = try [Int16].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int32Array:
            value = try [Int32].foryRead(context, refMode: .none, readTypeInfo: false)
        case .int64Array:
            value = try [Int64].foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint16Array:
            value = try [UInt16].foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint32Array:
            value = try [UInt32].foryRead(context, refMode: .none, readTypeInfo: false)
        case .uint64Array:
            value = try [UInt64].foryRead(context, refMode: .none, readTypeInfo: false)
        case .float32Array:
            value = try [Float].foryRead(context, refMode: .none, readTypeInfo: false)
        case .float64Array:
            value = try [Double].foryRead(context, refMode: .none, readTypeInfo: false)
        case .list:
            value = try context.readAnyList(refMode: .none) ?? []
        case .map:
            value = try readDynamicAnyMapValue(context: context)
        case .structType, .enumType, .ext, .typedUnion:
            if let userTypeID = typeInfo.userTypeID {
                value = try readByUserTypeID(userTypeID, context: context)
            } else if let namespace = typeInfo.namespace, let typeName = typeInfo.typeName {
                value = try readByTypeName(
                    namespace: namespace.value,
                    typeName: typeName.value,
                    context: context
                )
            } else {
                throw ForyError.invalidData("missing dynamic registration info for \(typeInfo.wireTypeID)")
            }
        case .namedStruct, .namedEnum, .namedExt, .namedUnion:
            guard let namespace = typeInfo.namespace, let typeName = typeInfo.typeName else {
                throw ForyError.invalidData("missing dynamic type name for \(typeInfo.wireTypeID)")
            }
            value = try readByTypeName(
                namespace: namespace.value,
                typeName: typeName.value,
                context: context
            )
        case .compatibleStruct, .namedCompatibleStruct:
            guard let compatibleTypeMeta = typeInfo.compatibleTypeMeta else {
                throw ForyError.invalidData("missing compatible type meta for \(typeInfo.wireTypeID)")
            }
            if compatibleTypeMeta.registerByName {
                value = try readByTypeName(
                    namespace: compatibleTypeMeta.namespace.value,
                    typeName: compatibleTypeMeta.typeName.value,
                    context: context,
                    compatibleTypeMeta: compatibleTypeMeta
                )
            } else {
                guard let userTypeID = compatibleTypeMeta.userTypeID else {
                    throw ForyError.invalidData("missing user type id in compatible dynamic type meta")
                }
                value = try readByUserTypeID(
                    userTypeID,
                    context: context,
                    compatibleTypeMeta: compatibleTypeMeta
                )
            }
        case .none:
            value = ForyAnyNullValue()
        default:
            throw ForyError.invalidData("unsupported dynamic type id \(typeInfo.wireTypeID)")
        }
        return value
    }

    private func markRegistrationMode(kind: ForyTypeId, registerByName: Bool) {
        let mode: DynamicRegistrationMode = registerByName ? .nameOnly : .idOnly
        guard let existing = registrationModeByKind[kind] else {
            registrationModeByKind[kind] = mode
            return
        }
        if existing != mode {
            registrationModeByKind[kind] = .mixed
        }
    }

    private func dynamicRegistrationMode(for kind: ForyTypeId) throws -> DynamicRegistrationMode {
        guard let mode = registrationModeByKind[kind] else {
            throw ForyError.typeNotRegistered("no dynamic registration mode for kind \(kind)")
        }
        return mode
    }

    private static func readMetaString(
        reader: ByteBuffer,
        decoder: MetaStringDecoder,
        encodings: [MetaStringEncoding]
    ) throws -> MetaString {
        let header = try reader.readUInt8()
        let encodingIndex = Int(header & 0b11)
        guard encodingIndex < encodings.count else {
            throw ForyError.invalidData("invalid meta string encoding index")
        }

        var length = Int(header >> 2)
        if length >= 0b11_1111 {
            length = 0b11_1111 + Int(try reader.readVarUInt32())
        }
        let bytes = try reader.readBytes(count: length)
        return try decoder.decode(bytes: bytes, encoding: encodings[encodingIndex])
    }
}
