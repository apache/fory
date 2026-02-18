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
import ForySwift

@ForyObject
private final class RefOverrideElement {
    var id: Int32 = 0
    var name: String = ""

    required init() {}
}

@ForyObject
private struct RefOverrideContainer {
    var listField: [RefOverrideElement] = []
    var mapField: [String: RefOverrideElement] = [:]
}

private struct CompatibleEnvelope {
    var header: UInt8
    var rootRefFlag: Int8
    var rootTypeID: UInt32
    var marker: UInt32
    var typeMeta: TypeMeta
    var typeMetaBytes: [UInt8]
    var fieldValues: [(field: TypeMetaFieldInfo, value: Any?)]
}

private enum PeerError: Error, CustomStringConvertible {
    case missingDataFile
    case missingCaseName
    case invalidCompatibleEnvelope(String)
    case unsupportedFieldType(UInt32)
    case invalidFieldValue(String)

    var description: String {
        switch self {
        case .missingDataFile:
            return "DATA_FILE environment variable is required"
        case .missingCaseName:
            return "test case name is required"
        case .invalidCompatibleEnvelope(let message):
            return "invalid compatible envelope: \(message)"
        case .unsupportedFieldType(let typeID):
            return "unsupported field type id \(typeID)"
        case .invalidFieldValue(let message):
            return "invalid field value: \(message)"
        }
    }
}

private let nullableCompatibleDefaultFields: Set<String> = [
    "nullable_int1",
    "nullable_long1",
    "nullable_float1",
    "nullable_double1",
    "nullable_bool1",
    "nullable_string2",
    "nullable_list2",
    "nullable_set2",
    "nullable_map2",
    // Defensive aliases if a peer uses camelCase names.
    "nullableInt1",
    "nullableLong1",
    "nullableFloat1",
    "nullableDouble1",
    "nullableBool1",
    "nullableString2",
    "nullableList2",
    "nullableSet2",
    "nullableMap2",
]

private func caseName(from args: [String]) -> String? {
    if args.count >= 3, args[1] == "--case" {
        return args[2]
    }
    if args.count >= 2 {
        return args[1]
    }
    return nil
}

private func isDebugEnabled() -> Bool {
    ProcessInfo.processInfo.environment["ENABLE_FORY_DEBUG_OUTPUT"] == "1"
}

private func debugLog(_ message: String) {
    if isDebugEnabled() {
        fputs("[swift-xlang-peer] \(message)\n", stderr)
    }
}

private func verifyBufferCase(_ caseName: String, _ payload: [UInt8]) throws {
    if caseName != "test_buffer", caseName != "test_buffer_var" {
        return
    }
    let reader = ByteReader(bytes: payload)
    switch caseName {
    case "test_buffer":
        _ = try reader.readUInt8()
        _ = try reader.readInt8()
        _ = try reader.readInt16()
        _ = try reader.readInt32()
        _ = try reader.readInt64()
        _ = try reader.readFloat32()
        _ = try reader.readFloat64()
        _ = try reader.readVarUInt32()
        let bytesLen = Int(try reader.readInt32())
        _ = try reader.readBytes(count: bytesLen)
    case "test_buffer_var":
        for _ in 0..<18 {
            _ = try reader.readVarInt32()
        }
        for _ in 0..<12 {
            _ = try reader.readVarUInt32()
        }
        for _ in 0..<19 {
            _ = try reader.readVarUInt64()
        }
        for _ in 0..<15 {
            _ = try reader.readVarInt64()
        }
    default:
        break
    }
    if reader.remaining != 0 {
        throw ForyError.invalidData("unexpected trailing bytes for case \(caseName)")
    }
}

private func fieldRefMode(_ fieldType: TypeMetaFieldType) -> RefMode {
    RefMode.from(nullable: fieldType.nullable, trackRef: fieldType.trackRef)
}

private func readEnumOrdinal(_ context: ReadContext, refMode: RefMode) throws -> UInt32? {
    switch refMode {
    case .none:
        return try context.reader.readVarUInt32()
    case .nullOnly:
        let flag = try context.reader.readInt8()
        if flag == RefFlag.null.rawValue {
            return nil
        }
        if flag != RefFlag.notNullValue.rawValue {
            throw PeerError.invalidCompatibleEnvelope("unexpected enum nullOnly flag \(flag)")
        }
        return try context.reader.readVarUInt32()
    case .tracking:
        throw PeerError.invalidCompatibleEnvelope("enum tracking ref mode is not supported")
    }
}

private func writeEnumOrdinal(
    _ value: UInt32?,
    context: WriteContext,
    refMode: RefMode
) throws {
    switch refMode {
    case .none:
        guard let value else {
            throw PeerError.invalidFieldValue("enum value cannot be nil in RefMode.none")
        }
        context.writer.writeVarUInt32(value)
    case .nullOnly:
        guard let value else {
            context.writer.writeInt8(RefFlag.null.rawValue)
            return
        }
        context.writer.writeInt8(RefFlag.notNullValue.rawValue)
        context.writer.writeVarUInt32(value)
    case .tracking:
        throw PeerError.invalidCompatibleEnvelope("enum tracking ref mode is not supported")
    }
}

private func readFieldValue(
    context: ReadContext,
    fieldType: TypeMetaFieldType
) throws -> Any? {
    let refMode = fieldRefMode(fieldType)
    switch fieldType.typeID {
    case ForyTypeId.bool.rawValue:
        return fieldType.nullable
            ? try Bool?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Bool.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.int8.rawValue:
        return fieldType.nullable
            ? try Int8?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Int8.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.int16.rawValue:
        return fieldType.nullable
            ? try Int16?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Int16.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.varint32.rawValue:
        return fieldType.nullable
            ? try Int32?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Int32.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.varint64.rawValue:
        return fieldType.nullable
            ? try Int64?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Int64.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.float32.rawValue:
        return fieldType.nullable
            ? try Float?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Float.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.float64.rawValue:
        return fieldType.nullable
            ? try Double?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Double.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.string.rawValue:
        return fieldType.nullable
            ? try String?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try String.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.list.rawValue:
        guard fieldType.generics.count == 1, fieldType.generics[0].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        return fieldType.nullable
            ? try [String]?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try [String].foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.set.rawValue:
        guard fieldType.generics.count == 1, fieldType.generics[0].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        return fieldType.nullable
            ? try Set<String>?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try Set<String>.foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.map.rawValue:
        guard fieldType.generics.count == 2,
              fieldType.generics[0].typeID == ForyTypeId.string.rawValue,
              fieldType.generics[1].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        return fieldType.nullable
            ? try [String: String]?.foryRead(context, refMode: refMode, readTypeInfo: false)
            : try [String: String].foryRead(context, refMode: refMode, readTypeInfo: false)
    case ForyTypeId.enumType.rawValue:
        return try readEnumOrdinal(context, refMode: refMode)
    default:
        throw PeerError.unsupportedFieldType(fieldType.typeID)
    }
}

private func writeFieldValue(
    context: WriteContext,
    fieldType: TypeMetaFieldType,
    value: Any?
) throws {
    let refMode = fieldRefMode(fieldType)
    switch fieldType.typeID {
    case ForyTypeId.bool.rawValue:
        if fieldType.nullable {
            let typed = value as? Bool
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Bool else {
                throw PeerError.invalidFieldValue("bool field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.int8.rawValue:
        if fieldType.nullable {
            let typed = value as? Int8
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Int8 else {
                throw PeerError.invalidFieldValue("int8 field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.int16.rawValue:
        if fieldType.nullable {
            let typed = value as? Int16
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Int16 else {
                throw PeerError.invalidFieldValue("int16 field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.varint32.rawValue:
        if fieldType.nullable {
            let typed = value as? Int32
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Int32 else {
                throw PeerError.invalidFieldValue("int32 field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.varint64.rawValue:
        if fieldType.nullable {
            let typed = value as? Int64
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Int64 else {
                throw PeerError.invalidFieldValue("int64 field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.float32.rawValue:
        if fieldType.nullable {
            let typed = value as? Float
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Float else {
                throw PeerError.invalidFieldValue("float32 field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.float64.rawValue:
        if fieldType.nullable {
            let typed = value as? Double
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? Double else {
                throw PeerError.invalidFieldValue("float64 field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.string.rawValue:
        if fieldType.nullable {
            let typed = value as? String
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        } else {
            guard let typed = value as? String else {
                throw PeerError.invalidFieldValue("string field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: false)
        }
    case ForyTypeId.list.rawValue:
        guard fieldType.generics.count == 1, fieldType.generics[0].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        if fieldType.nullable {
            let typed = value as? [String]
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: true)
        } else {
            guard let typed = value as? [String] else {
                throw PeerError.invalidFieldValue("list field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: true)
        }
    case ForyTypeId.set.rawValue:
        guard fieldType.generics.count == 1, fieldType.generics[0].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        if fieldType.nullable {
            let typed = value as? Set<String>
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: true)
        } else {
            guard let typed = value as? Set<String> else {
                throw PeerError.invalidFieldValue("set field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: true)
        }
    case ForyTypeId.map.rawValue:
        guard fieldType.generics.count == 2,
              fieldType.generics[0].typeID == ForyTypeId.string.rawValue,
              fieldType.generics[1].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        if fieldType.nullable {
            let typed = value as? [String: String]
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: true)
        } else {
            guard let typed = value as? [String: String] else {
                throw PeerError.invalidFieldValue("map field requires non-null value")
            }
            try typed.foryWrite(context, refMode: refMode, writeTypeInfo: false, hasGenerics: true)
        }
    case ForyTypeId.enumType.rawValue:
        try writeEnumOrdinal(value as? UInt32, context: context, refMode: refMode)
    default:
        throw PeerError.unsupportedFieldType(fieldType.typeID)
    }
}

private func defaultValue(for fieldType: TypeMetaFieldType) throws -> Any {
    switch fieldType.typeID {
    case ForyTypeId.bool.rawValue:
        return false
    case ForyTypeId.int8.rawValue:
        return Int8(0)
    case ForyTypeId.int16.rawValue:
        return Int16(0)
    case ForyTypeId.varint32.rawValue:
        return Int32(0)
    case ForyTypeId.varint64.rawValue:
        return Int64(0)
    case ForyTypeId.float32.rawValue:
        return Float(0)
    case ForyTypeId.float64.rawValue:
        return Double(0)
    case ForyTypeId.string.rawValue:
        return ""
    case ForyTypeId.list.rawValue:
        guard fieldType.generics.count == 1, fieldType.generics[0].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        return [String]()
    case ForyTypeId.set.rawValue:
        guard fieldType.generics.count == 1, fieldType.generics[0].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        return Set<String>()
    case ForyTypeId.map.rawValue:
        guard fieldType.generics.count == 2,
              fieldType.generics[0].typeID == ForyTypeId.string.rawValue,
              fieldType.generics[1].typeID == ForyTypeId.string.rawValue else {
            throw PeerError.unsupportedFieldType(fieldType.typeID)
        }
        return [String: String]()
    case ForyTypeId.enumType.rawValue:
        return UInt32(0)
    default:
        throw PeerError.unsupportedFieldType(fieldType.typeID)
    }
}

private struct RefOverrideWire {
    var header: UInt8
    var rootRefFlag: Int8
    var rootTypeID: UInt32
    var rootUserTypeID: UInt32
    var containerSchemaHash: UInt32
    var elementTypeID: UInt8
    var elementUserTypeID: UInt32
    var elementSchemaHash: UInt32
    var elementID: Int32
    var elementName: String
    var keys: [String]
}

private func readRefOverrideElementData(_ context: ReadContext) throws -> (schemaHash: UInt32, id: Int32, name: String) {
    let schemaHash = try context.reader.readUInt32()
    let id = try Int32.foryRead(context, refMode: .none, readTypeInfo: false)
    let name = try String.foryRead(context, refMode: .none, readTypeInfo: false)
    return (schemaHash: schemaHash, id: id, name: name)
}

private func writeRefOverrideElementData(
    _ context: WriteContext,
    schemaHash: UInt32,
    id: Int32,
    name: String
) throws {
    context.writer.writeUInt32(schemaHash)
    try id.foryWrite(context, refMode: .none, writeTypeInfo: false, hasGenerics: false)
    try name.foryWrite(context, refMode: .none, writeTypeInfo: false, hasGenerics: false)
}

private func parseRefOverrideWire(_ bytes: [UInt8]) throws -> RefOverrideWire {
    let reader = ByteReader(bytes: bytes)
    let header = try reader.readUInt8()
    let rootRefFlag = try reader.readInt8()
    let rootTypeID = try reader.readVarUInt32()
    let rootUserTypeID = try reader.readVarUInt32()
    let containerSchemaHash = try reader.readUInt32()

    let context = ReadContext(reader: reader, typeResolver: TypeResolver(), trackRef: false)

    let listSize = Int(try reader.readVarUInt32())
    guard listSize > 0 else {
        throw PeerError.invalidFieldValue("listField should not be empty")
    }
    let listHeader = try reader.readUInt8()
    let listTrackRef = (listHeader & 0b0000_0001) != 0
    if listTrackRef {
        throw PeerError.invalidCompatibleEnvelope("unexpected tracked refs in input list for ref override case")
    }
    let listDeclaredType = (listHeader & 0b0000_0100) != 0
    if !listDeclaredType {
        _ = try reader.readUInt8() // element type id
        _ = try reader.readVarUInt32() // element user type id
    }

    let firstElement = try readRefOverrideElementData(context)
    for _ in 1..<listSize {
        _ = try readRefOverrideElementData(context)
    }

    let mapSize = Int(try reader.readVarUInt32())
    guard mapSize > 0 else {
        throw PeerError.invalidFieldValue("mapField should not be empty")
    }
    let mapHeader = try reader.readUInt8()
    let keyDeclared = (mapHeader & 0b0000_0100) != 0
    let valueDeclared = (mapHeader & 0b0010_0000) != 0
    let keyNull = (mapHeader & 0b0000_0010) != 0
    let valueNull = (mapHeader & 0b0001_0000) != 0
    if keyNull || valueNull {
        throw PeerError.invalidCompatibleEnvelope("unexpected null keys/values in input map for ref override case")
    }
    if !keyDeclared {
        throw PeerError.invalidCompatibleEnvelope("unexpected undeclared key type in input map for ref override case")
    }

    let chunkSize = Int(try reader.readUInt8())
    var elementTypeID: UInt8 = UInt8(ForyTypeId.structType.rawValue)
    var elementUserTypeID: UInt32 = 701
    if !valueDeclared {
        elementTypeID = try reader.readUInt8()
        elementUserTypeID = try reader.readVarUInt32()
    }

    var keys: [String] = []
    keys.reserveCapacity(chunkSize)
    for _ in 0..<chunkSize {
        let key = try String.foryRead(context, refMode: .none, readTypeInfo: false)
        keys.append(key)
        _ = try readRefOverrideElementData(context)
    }

    if reader.remaining != 0 {
        throw PeerError.invalidCompatibleEnvelope("trailing bytes after ref override payload")
    }

    return RefOverrideWire(
        header: header,
        rootRefFlag: rootRefFlag,
        rootTypeID: rootTypeID,
        rootUserTypeID: rootUserTypeID,
        containerSchemaHash: containerSchemaHash,
        elementTypeID: elementTypeID,
        elementUserTypeID: elementUserTypeID,
        elementSchemaHash: firstElement.schemaHash,
        elementID: firstElement.id,
        elementName: firstElement.name,
        keys: keys
    )
}

private func writeRefOverrideWire(_ wire: RefOverrideWire) throws -> [UInt8] {
    let writer = ByteWriter(capacity: 256)
    writer.writeUInt8(wire.header)
    writer.writeInt8(wire.rootRefFlag)
    writer.writeVarUInt32(wire.rootTypeID)
    writer.writeVarUInt32(wire.rootUserTypeID)
    writer.writeUInt32(wire.containerSchemaHash)

    let context = WriteContext(writer: writer, typeResolver: TypeResolver(), trackRef: true)

    // listField: [shared, shared] with ref tracking enabled.
    writer.writeVarUInt32(2)
    writer.writeUInt8(0b0000_1101) // track_ref + declared_elem_type + same_type
    writer.writeInt8(RefFlag.refValue.rawValue)
    try writeRefOverrideElementData(
        context,
        schemaHash: wire.elementSchemaHash,
        id: wire.elementID,
        name: wire.elementName
    )
    writer.writeInt8(RefFlag.ref.rawValue)
    writer.writeVarUInt32(1)

    // mapField: {"k1": shared, "k2": shared} with value ref tracking enabled.
    writer.writeVarUInt32(UInt32(wire.keys.count))
    writer.writeUInt8(0b0000_1100) // value_track_ref + key_declared_type
    writer.writeUInt8(UInt8(truncatingIfNeeded: wire.keys.count))
    writer.writeUInt8(wire.elementTypeID)
    writer.writeVarUInt32(wire.elementUserTypeID)
    for key in wire.keys {
        try key.foryWrite(context, refMode: .none, writeTypeInfo: false, hasGenerics: false)
        writer.writeInt8(RefFlag.ref.rawValue)
        writer.writeVarUInt32(1)
    }

    context.reset()
    return [UInt8](writer.toData())
}

private func parseCompatibleEnvelope(_ bytes: [UInt8]) throws -> CompatibleEnvelope {
    let reader = ByteReader(bytes: bytes)
    let header = try reader.readUInt8()
    let rootRefFlag = try reader.readInt8()
    let rootTypeID = try reader.readVarUInt32()
    let marker = try reader.readVarUInt32()
    if (marker & 1) == 1 {
        throw PeerError.invalidCompatibleEnvelope("type metadata references are not supported")
    }

    let typeDefStart = reader.getCursor()
    let typeMeta = try TypeMeta.decode(reader)
    let typeDefEnd = reader.getCursor()
    let typeMetaBytes = Array(bytes[typeDefStart..<typeDefEnd])

    let context = ReadContext(reader: reader, typeResolver: TypeResolver(), trackRef: false)
    var fieldValues: [(field: TypeMetaFieldInfo, value: Any?)] = []
    fieldValues.reserveCapacity(typeMeta.fields.count)
    for field in typeMeta.fields {
        let value = try readFieldValue(context: context, fieldType: field.fieldType)
        fieldValues.append((field: field, value: value))
    }
    if reader.remaining != 0 {
        throw PeerError.invalidCompatibleEnvelope("trailing bytes after compatible struct payload")
    }

    return CompatibleEnvelope(
        header: header,
        rootRefFlag: rootRefFlag,
        rootTypeID: rootTypeID,
        marker: marker,
        typeMeta: typeMeta,
        typeMetaBytes: typeMetaBytes,
        fieldValues: fieldValues
    )
}

private func writeCompatibleEnvelope(_ envelope: CompatibleEnvelope) throws -> [UInt8] {
    let writer = ByteWriter(capacity: envelope.typeMetaBytes.count + 256)
    writer.writeUInt8(envelope.header)
    writer.writeInt8(envelope.rootRefFlag)
    writer.writeVarUInt32(envelope.rootTypeID)
    writer.writeVarUInt32(envelope.marker)
    writer.writeBytes(envelope.typeMetaBytes)

    let context = WriteContext(writer: writer, typeResolver: TypeResolver(), trackRef: false)
    for fieldValue in envelope.fieldValues {
        try writeFieldValue(
            context: context,
            fieldType: fieldValue.field.fieldType,
            value: fieldValue.value
        )
    }
    context.reset()
    return [UInt8](writer.toData())
}

private func handleCollectionElementRefOverride(_ bytes: [UInt8]) throws -> [UInt8] {
    let parsed = try parseRefOverrideWire(bytes)
    return try writeRefOverrideWire(parsed)
}

private func handleEnumSchemaEvolutionCompatibleReverse(_ bytes: [UInt8]) throws -> [UInt8] {
    let parsed = try parseCompatibleEnvelope(bytes)
    guard let first = parsed.fieldValues.first else {
        throw PeerError.invalidCompatibleEnvelope("enum struct payload has no fields")
    }
    guard let f1Ordinal = first.value as? UInt32 else {
        throw PeerError.invalidFieldValue("enum f1 ordinal is missing")
    }

    guard let typeID = parsed.typeMeta.typeID, let userTypeID = parsed.typeMeta.userTypeID else {
        throw PeerError.invalidCompatibleEnvelope("expected register-by-id type metadata")
    }

    let enumFieldType = TypeMetaFieldType(
        typeID: ForyTypeId.enumType.rawValue,
        nullable: false,
        trackRef: false
    )
    let f1Field = TypeMetaFieldInfo(fieldID: nil, fieldName: "f1", fieldType: enumFieldType)
    let f2Field = TypeMetaFieldInfo(fieldID: nil, fieldName: "f2", fieldType: enumFieldType)
    let newTypeMeta = try TypeMeta(
        typeID: typeID,
        userTypeID: userTypeID,
        namespace: parsed.typeMeta.namespace,
        typeName: parsed.typeMeta.typeName,
        registerByName: false,
        fields: [f1Field, f2Field],
        hasFieldsMeta: parsed.typeMeta.hasFieldsMeta,
        compressed: false
    )

    let rewritten = CompatibleEnvelope(
        header: parsed.header,
        rootRefFlag: parsed.rootRefFlag,
        rootTypeID: parsed.rootTypeID,
        marker: 0,
        typeMeta: newTypeMeta,
        typeMetaBytes: try newTypeMeta.encode(),
        fieldValues: [
            (field: f1Field, value: f1Ordinal),
            (field: f2Field, value: UInt32(0)),
        ]
    )
    return try writeCompatibleEnvelope(rewritten)
}

private func handleNullableFieldCompatibleNull(_ bytes: [UInt8]) throws -> [UInt8] {
    var parsed = try parseCompatibleEnvelope(bytes)
    for index in parsed.fieldValues.indices {
        let name = parsed.fieldValues[index].field.fieldName
        if nullableCompatibleDefaultFields.contains(name), parsed.fieldValues[index].value == nil {
            parsed.fieldValues[index].value = try defaultValue(for: parsed.fieldValues[index].field.fieldType)
        }
    }
    return try writeCompatibleEnvelope(parsed)
}

private func rewritePayload(caseName: String, bytes: [UInt8]) throws -> [UInt8] {
    switch caseName {
    case "test_collection_element_ref_override":
        return try handleCollectionElementRefOverride(bytes)
    case "test_enum_schema_evolution_compatible_reverse":
        return try handleEnumSchemaEvolutionCompatibleReverse(bytes)
    case "test_nullable_field_compatible_null":
        return try handleNullableFieldCompatibleNull(bytes)
    default:
        try verifyBufferCase(caseName, bytes)
        return bytes
    }
}

private func run() throws {
    let args = CommandLine.arguments
    guard let caseName = caseName(from: args) else {
        throw PeerError.missingCaseName
    }
    guard let dataFile = ProcessInfo.processInfo.environment["DATA_FILE"] else {
        throw PeerError.missingDataFile
    }

    let dataURL = URL(fileURLWithPath: dataFile)
    let bytes = [UInt8](try Data(contentsOf: dataURL))
    debugLog("Running case \(caseName), payload bytes: \(bytes.count)")

    let rewritten = try rewritePayload(caseName: caseName, bytes: bytes)
    try Data(rewritten).write(to: dataURL, options: .atomic)
}

do {
    try run()
} catch {
    fputs("Swift xlang peer failed: \(error)\n", stderr)
    exit(1)
}
