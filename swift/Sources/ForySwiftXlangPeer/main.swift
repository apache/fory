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
private final class RefOverrideContainer {
    var listField: [RefOverrideElement] = []
    var mapField: [String: RefOverrideElement] = [:]

    required init() {}
}

private enum PeerTestEnum: UInt32 {
    case valueA = 0
    case valueB = 1
    case valueC = 2
}

extension PeerTestEnum: Serializer {
    static func foryDefault() -> PeerTestEnum {
        .valueA
    }

    static var staticTypeId: ForyTypeId {
        .enumType
    }

    func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        context.writer.writeVarUInt32(rawValue)
    }

    static func foryReadData(_ context: ReadContext) throws -> PeerTestEnum {
        let ordinal = try context.reader.readVarUInt32()
        guard let value = PeerTestEnum(rawValue: ordinal) else {
            throw ForyError.invalidData("unknown enum ordinal \(ordinal)")
        }
        return value
    }
}

@ForyObject
private struct TwoEnumFieldStructCompatible {
    var f1: PeerTestEnum = .valueA
    var f2: PeerTestEnum = .valueA
}

@ForyObject
private struct NullableComprehensiveCompatibleSwift {
    var byteField: Int8 = 0
    var shortField: Int16 = 0
    var intField: Int32 = 0
    var longField: Int64 = 0
    var floatField: Float = 0
    var doubleField: Double = 0
    var boolField: Bool = false

    var boxedInt: Int32 = 0
    var boxedLong: Int64 = 0
    var boxedFloat: Float = 0
    var boxedDouble: Double = 0
    var boxedBool: Bool = false

    var stringField: String = ""
    var listField: [String] = []
    var setField: Set<String> = []
    var mapField: [String: String] = [:]

    var nullableInt1: Int32 = 0
    var nullableLong1: Int64 = 0
    var nullableFloat1: Float = 0
    var nullableDouble1: Double = 0
    var nullableBool1: Bool = false

    var nullableString2: String = ""
    var nullableList2: [String] = []
    var nullableSet2: Set<String> = []
    var nullableMap2: [String: String] = [:]
}

private enum PeerError: Error, CustomStringConvertible {
    case missingDataFile
    case missingCaseName
    case invalidFieldValue(String)

    var description: String {
        switch self {
        case .missingDataFile:
            return "DATA_FILE environment variable is required"
        case .missingCaseName:
            return "test case name is required"
        case .invalidFieldValue(let message):
            return "invalid field value: \(message)"
        }
    }
}

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

private func handleCollectionElementRefOverride(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: true, compatible: false))
    fory.register(RefOverrideElement.self, id: 701)
    fory.register(RefOverrideContainer.self, id: 702)

    let decoded: RefOverrideContainer = try fory.deserialize(Data(bytes))
    guard let shared = decoded.listField.first else {
        throw PeerError.invalidFieldValue("listField should not be empty")
    }

    let rewritten = RefOverrideContainer()
    rewritten.listField = [shared, shared]
    rewritten.mapField = [
        "k1": shared,
        "k2": shared,
    ]
    return [UInt8](try fory.serialize(rewritten))
}

private func handleEnumSchemaEvolutionCompatibleReverse(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(PeerTestEnum.self, id: 210)
    fory.register(TwoEnumFieldStructCompatible.self, id: 211)

    let decoded: TwoEnumFieldStructCompatible = try fory.deserialize(Data(bytes))
    return [UInt8](try fory.serialize(decoded))
}

private func handleNullableFieldCompatibleNull(_ bytes: [UInt8]) throws -> [UInt8] {
    let fory = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    fory.register(NullableComprehensiveCompatibleSwift.self, id: 402)

    let decoded: NullableComprehensiveCompatibleSwift = try fory.deserialize(Data(bytes))
    return [UInt8](try fory.serialize(decoded))
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
