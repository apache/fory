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

@attached(
    member,
    names: named(staticTypeId),
    named(foryEvolving),
    named(isRefType),
    named(__foryNormalizeSchemaFingerprintTypeID),
    named(__forySchemaHash),
    named(__forySchemaHashTrackRefDisabled),
    named(__forySchemaHashTrackRefEnabled),
    named(foryFieldsInfo),
    named(__foryFieldsInfoTrackRefDisabled),
    named(__foryFieldsInfoTrackRefEnabled),
    named(foryDefault),
    named(foryWrite),
    named(foryRead),
    named(foryWriteData),
    named(foryReadData),
    named(foryReadCompatibleData),
    named(__foryReadDataImpl),
    named(__foryReadCompatibleDataImpl)
)
@attached(extension, conformances: Serializer, StructSerializer)
public macro ForyStruct(evolving: Bool = true) = #externalMacro(module: "ForyMacro", type: "ForyStructMacro")

@attached(
    member,
    names: named(staticTypeId),
    named(foryEvolving),
    named(isRefType),
    named(__foryNormalizeSchemaFingerprintTypeID),
    named(__forySchemaHash),
    named(__forySchemaHashTrackRefDisabled),
    named(__forySchemaHashTrackRefEnabled),
    named(foryFieldsInfo),
    named(__foryFieldsInfoTrackRefDisabled),
    named(__foryFieldsInfoTrackRefEnabled),
    named(foryDefault),
    named(foryWrite),
    named(foryRead),
    named(foryWriteData),
    named(foryReadData),
    named(foryReadCompatibleData),
    named(__foryReadDataImpl),
    named(__foryReadCompatibleDataImpl)
)
@attached(extension, conformances: Serializer, StructSerializer)
public macro ForyEnum() = #externalMacro(module: "ForyMacro", type: "ForyEnumMacro")

@attached(
    member,
    names: named(staticTypeId),
    named(foryEvolving),
    named(isRefType),
    named(__foryNormalizeSchemaFingerprintTypeID),
    named(__forySchemaHash),
    named(__forySchemaHashTrackRefDisabled),
    named(__forySchemaHashTrackRefEnabled),
    named(foryFieldsInfo),
    named(__foryFieldsInfoTrackRefDisabled),
    named(__foryFieldsInfoTrackRefEnabled),
    named(foryDefault),
    named(foryWrite),
    named(foryRead),
    named(foryWriteData),
    named(foryReadData),
    named(foryReadCompatibleData),
    named(__foryReadDataImpl),
    named(__foryReadCompatibleDataImpl)
)
@attached(extension, conformances: Serializer, StructSerializer)
public macro ForyUnion() = #externalMacro(module: "ForyMacro", type: "ForyUnionMacro")

public enum ForyFieldEncoding: String {
    case varint
    case fixed
    case tagged
}

public struct ForyFieldType: Sendable {
    private init() {}

    public static func encoding(_ encoding: ForyFieldEncoding) -> ForyFieldType {
        _ = encoding
        return .init()
    }

    public static var bool: ForyFieldType { .init() }
    public static var int8: ForyFieldType { .init() }
    public static var int16: ForyFieldType { .init() }
    public static var uint8: ForyFieldType { .init() }
    public static var uint16: ForyFieldType { .init() }
    public static var float16: ForyFieldType { .init() }
    public static var bfloat16: ForyFieldType { .init() }
    public static var float32: ForyFieldType { .init() }
    public static var float64: ForyFieldType { .init() }
    public static var string: ForyFieldType { .init() }
    public static var date: ForyFieldType { .init() }
    public static var timestamp: ForyFieldType { .init() }
    public static var duration: ForyFieldType { .init() }
    public static var decimal: ForyFieldType { .init() }
    public static var binary: ForyFieldType { .init() }

    public static func int32(nullable: Bool = false, encoding: ForyFieldEncoding = .varint) -> ForyFieldType {
        _ = nullable
        _ = encoding
        return .init()
    }

    public static func int64(nullable: Bool = false, encoding: ForyFieldEncoding = .varint) -> ForyFieldType {
        _ = nullable
        _ = encoding
        return .init()
    }

    public static func int(nullable: Bool = false, encoding: ForyFieldEncoding = .varint) -> ForyFieldType {
        _ = nullable
        _ = encoding
        return .init()
    }

    public static func uint32(nullable: Bool = false, encoding: ForyFieldEncoding = .varint) -> ForyFieldType {
        _ = nullable
        _ = encoding
        return .init()
    }

    public static func uint64(nullable: Bool = false, encoding: ForyFieldEncoding = .varint) -> ForyFieldType {
        _ = nullable
        _ = encoding
        return .init()
    }

    public static func uint(nullable: Bool = false, encoding: ForyFieldEncoding = .varint) -> ForyFieldType {
        _ = nullable
        _ = encoding
        return .init()
    }

    public static func list(_ element: ForyFieldType) -> ForyFieldType {
        list(element: element)
    }

    public static func list(element: ForyFieldType) -> ForyFieldType {
        _ = element
        return .init()
    }

    public static func array(element: ForyFieldType) -> ForyFieldType {
        _ = element
        return .init()
    }

    public static func set(element: ForyFieldType) -> ForyFieldType {
        _ = element
        return .init()
    }

    public static func map(key: ForyFieldType) -> ForyFieldType {
        _ = key
        return .init()
    }

    public static func map(value: ForyFieldType) -> ForyFieldType {
        _ = value
        return .init()
    }

    public static func map(key: ForyFieldType, value: ForyFieldType) -> ForyFieldType {
        _ = key
        _ = value
        return .init()
    }
}

@attached(peer)
public macro ForyField(
    id: Int? = nil,
    encoding: ForyFieldEncoding? = nil,
    type: ForyFieldType? = nil
) = #externalMacro(module: "ForyMacro", type: "ForyFieldMacro")

@attached(peer)
public macro ListField(
    element: ForyFieldType
) = #externalMacro(module: "ForyMacro", type: "ListFieldMacro")

@attached(peer)
public macro ArrayField(
    element: ForyFieldType
) = #externalMacro(module: "ForyMacro", type: "ArrayFieldMacro")

@attached(peer)
public macro SetField(
    element: ForyFieldType
) = #externalMacro(module: "ForyMacro", type: "SetFieldMacro")

@attached(peer)
public macro MapField(
    key: ForyFieldType? = nil,
    value: ForyFieldType? = nil
) = #externalMacro(module: "ForyMacro", type: "MapFieldMacro")

@attached(peer)
public macro ForyCase(
    id: Int? = nil,
    payload: ForyFieldType? = nil
) = #externalMacro(module: "ForyMacro", type: "ForyCaseMacro")
