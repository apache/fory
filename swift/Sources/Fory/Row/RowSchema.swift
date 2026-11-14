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

/// The type of a row field, an array element, or a map key or value.
public indirect enum RowDataType: Equatable, Sendable, CustomStringConvertible {
    case bool
    case int8
    case int16
    case int32
    case int64
    /// Only parsed from schemas; its values are not read or written yet.
    case float16
    case float32
    case float64
    case string
    case binary
    case date32
    case timestamp
    case duration
    /// Only parsed from schemas; its values are not read or written yet.
    case decimal(precision: Int, scale: Int)
    case list(RowField)
    case map(key: RowField, value: RowField)
    case `struct`([RowField])

    /// The cross-language type id written in schema bytes.
    public var typeId: TypeId {
        switch self {
        case .bool: return .bool
        case .int8: return .int8
        case .int16: return .int16
        case .int32: return .int32
        case .int64: return .int64
        case .float16: return .float16
        case .float32: return .float32
        case .float64: return .float64
        case .string: return .string
        case .binary: return .binary
        case .date32: return .date
        case .timestamp: return .timestamp
        case .duration: return .duration
        case .decimal: return .decimal
        case .list: return .list
        case .map: return .map
        case .struct: return .structType
        }
    }

    /// The size of one value in bytes, or nil when the value lives after the slots and is
    /// reached through an offset and size.
    public var byteWidth: Int? {
        switch self {
        case .bool, .int8: return 1
        case .int16, .float16: return 2
        case .int32, .float32, .date32: return 4
        case .int64, .float64, .timestamp, .duration: return 8
        case .string, .binary, .decimal, .list, .map, .struct: return nil
        }
    }

    /// A list whose element field is named `item` and can be null.
    public static func listOf(_ element: RowDataType) -> RowDataType {
        .list(RowField(name: "item", dataType: element, nullable: true))
    }

    /// A map whose key field is named `key` and is never null, and whose value field is named
    /// `value` and can be null.
    public static func mapOf(_ key: RowDataType, _ value: RowDataType) -> RowDataType {
        .map(
            key: RowField(name: "key", dataType: key, nullable: false),
            value: RowField(name: "value", dataType: value, nullable: true)
        )
    }

    public var description: String {
        switch self {
        case .bool: return "bool"
        case .int8: return "int8"
        case .int16: return "int16"
        case .int32: return "int32"
        case .int64: return "int64"
        case .float16: return "float16"
        case .float32: return "float32"
        case .float64: return "float64"
        case .string: return "string"
        case .binary: return "binary"
        case .date32: return "date32"
        case .timestamp: return "timestamp"
        case .duration: return "duration"
        case .decimal(let precision, let scale): return "decimal(\(precision), \(scale))"
        case .list(let element): return "list<\(element.dataType)>"
        case .map(let key, let value): return "map<\(key.dataType), \(value.dataType)>"
        case .struct(let fields): return "struct<\(rowFieldListDescription(fields))>"
        }
    }
}

/// A named slot in a schema, or the element of a list, or the key or value of a map.
public struct RowField: Equatable, Sendable {
    public var name: String
    public var dataType: RowDataType
    public var nullable: Bool

    public init(name: String, dataType: RowDataType, nullable: Bool) {
        self.name = name
        self.dataType = dataType
        self.nullable = nullable
    }
}

/// The fields of a row in slot order. A row can only be read with the schema it was written with.
public struct RowSchema: Equatable, Sendable, CustomStringConvertible {
    public let fields: [RowField]
    // Built once from `fields`, so it never disagrees with them.
    private let indexByName: [String: Int]

    public init(_ fields: [RowField]) {
        self.fields = fields
        var indexByName: [String: Int] = [:]
        indexByName.reserveCapacity(fields.count)
        for (index, field) in fields.enumerated() {
            indexByName[field.name] = index
        }
        self.indexByName = indexByName
    }

    public var fieldCount: Int {
        fields.count
    }

    /// The slot index of the field named `name`, or nil if there is none. When a name repeats, the
    /// last field with that name wins.
    public func fieldIndex(of name: String) -> Int? {
        indexByName[name]
    }

    public var description: String {
        "schema<\(rowFieldListDescription(fields))>"
    }
}

// Shared by the struct and schema descriptions: "a: int32, b: string".
private func rowFieldListDescription(_ fields: [RowField]) -> String {
    fields.map { "\($0.name): \($0.dataType)" }.joined(separator: ", ")
}
