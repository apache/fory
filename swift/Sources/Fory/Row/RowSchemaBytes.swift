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

// Schema bytes are a version byte and a field count, then each field in order. A field is a header
// byte, its packed name, and its type. The header holds the name encoding in bits 0 and 1, the name
// size minus one in bits 2 to 5 (15 means a varint with the size minus 15 follows), and the
// nullable flag in bit 6. A type is its id byte, followed by precision and scale for a decimal, the
// element field for a list, the key and value fields for a map, or a count and the fields for a
// struct.

private let rowSchemaVersion: UInt8 = 1
private let rowFieldNameSizeThreshold = 15
// Deeper schemas are rejected so reading one cannot recurse without bound.
private let rowSchemaMaxDepth = 64
// Header bits 0 and 1 hold a position in this list, not the encoding's raw value.
private let rowFieldNameEncodings: [MetaStringEncoding] = [.utf8, .allToLowerSpecial, .lowerUpperDigitSpecial]

extension RowSchema {
    /// The schema in the byte form every row format runtime reads. Throws if a field name is empty,
    /// fields nest 64 or more levels deep, or a decimal precision or scale does not fit in a byte.
    public func toBytes() throws -> [UInt8] {
        let buffer = ByteBuffer()
        buffer.writeUInt8(rowSchemaVersion)
        buffer.writeVarUInt32(UInt32(fields.count))
        for field in fields {
            try rowWriteSchemaField(field, depth: 0, to: buffer)
        }
        return buffer.storage
    }

    public init(bytes: [UInt8]) throws {
        let buffer = ByteBuffer(bytes: bytes)
        let version = try buffer.readUInt8()
        guard version == rowSchemaVersion else {
            throw rowSchemaVersionUnsupported(version)
        }
        self.init(try rowReadSchemaFields(buffer, depth: 0))
    }

    /// A fingerprint of the field types, written before a row so the reader can tell it was written
    /// with the same schema. Names and nullability do not change it.
    public var hash: Int64 {
        fields.reduce(17) { rowSchemaHash($0, $1.dataType) }
    }
}

private func rowWriteSchemaField(_ field: RowField, depth: Int, to buffer: ByteBuffer) throws {
    guard depth < rowSchemaMaxDepth else {
        throw rowSchemaTooDeepToWrite()
    }
    guard !field.name.isEmpty else {
        throw rowSchemaEmptyFieldName()
    }
    let name = try MetaStringEncoder.fieldName.encode(field.name, allowedEncodings: rowFieldNameEncodings)
    guard let encodingIndex = rowFieldNameEncodings.firstIndex(of: name.encoding) else {
        preconditionFailure("field name encoder chose \(name.encoding), which was not allowed")
    }
    let nameSize = name.bytes.count
    let longName = nameSize > rowFieldNameSizeThreshold
    var header = UInt8(encodingIndex)
    header |= UInt8(longName ? rowFieldNameSizeThreshold : nameSize - 1) << 2
    if field.nullable {
        header |= 0x40
    }
    buffer.writeUInt8(header)
    if longName {
        buffer.writeVarUInt32(UInt32(nameSize - rowFieldNameSizeThreshold))
    }
    buffer.writeBytes(name.bytes)
    try rowWriteSchemaType(field.dataType, depth: depth, to: buffer)
}

private func rowWriteSchemaType(_ dataType: RowDataType, depth: Int, to buffer: ByteBuffer) throws {
    buffer.writeUInt8(UInt8(dataType.typeId.rawValue))
    switch dataType {
    case .decimal(let precision, let scale):
        guard let precisionByte = UInt8(exactly: precision), let scaleByte = UInt8(exactly: scale) else {
            throw rowSchemaDecimalOutOfRange(precision: precision, scale: scale)
        }
        buffer.writeUInt8(precisionByte)
        buffer.writeUInt8(scaleByte)
    case .list(let element):
        try rowWriteSchemaField(element, depth: depth + 1, to: buffer)
    case .map(let key, let value):
        try rowWriteSchemaField(key, depth: depth + 1, to: buffer)
        try rowWriteSchemaField(value, depth: depth + 1, to: buffer)
    case .struct(let fields):
        buffer.writeVarUInt32(UInt32(fields.count))
        for field in fields {
            try rowWriteSchemaField(field, depth: depth + 1, to: buffer)
        }
    default:
        break
    }
}

private func rowReadSchemaFields(_ buffer: ByteBuffer, depth: Int) throws -> [RowField] {
    let count = try buffer.readVarUInt32()
    // Every field takes at least one byte, so a larger count is wrong. Checking it first also keeps a
    // forged count from reserving a huge array.
    guard UInt64(count) <= UInt64(buffer.remaining) else {
        throw rowSchemaCountTooLarge(count, remaining: buffer.remaining)
    }
    var fields: [RowField] = []
    fields.reserveCapacity(Int(count))
    for _ in 0..<count {
        fields.append(try rowReadSchemaField(buffer, depth: depth))
    }
    return fields
}

private func rowReadSchemaField(_ buffer: ByteBuffer, depth: Int) throws -> RowField {
    guard depth < rowSchemaMaxDepth else {
        throw rowSchemaTooDeep()
    }
    let header = try buffer.readUInt8()
    let encodingIndex = Int(header & 0x03)
    guard encodingIndex < rowFieldNameEncodings.count else {
        throw rowSchemaUnknownNameEncoding(encodingIndex)
    }
    var nameSize = UInt64((header >> 2) & 0x0F) + 1
    if nameSize == UInt64(rowFieldNameSizeThreshold) + 1 {
        nameSize = UInt64(try buffer.readVarUInt32()) + UInt64(rowFieldNameSizeThreshold)
    }
    // readBytes checks the size against the bytes left before copying, and clamping keeps a huge
    // size from trapping where Int is 32 bits.
    let nameBytes = try buffer.readBytes(count: Int(clamping: nameSize))
    let name = try MetaStringDecoder.fieldName.decode(bytes: nameBytes, encoding: rowFieldNameEncodings[encodingIndex])
    let dataType = try rowReadSchemaType(buffer, depth: depth)
    return RowField(name: name.value, dataType: dataType, nullable: header & 0x40 != 0)
}

private func rowReadSchemaType(_ buffer: ByteBuffer, depth: Int) throws -> RowDataType {
    let typeId = try buffer.readUInt8()
    switch TypeId(rawValue: UInt32(typeId)) {
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
    case .date: return .date32
    case .timestamp: return .timestamp
    case .duration: return .duration
    case .decimal:
        let precision = try buffer.readUInt8()
        let scale = try buffer.readUInt8()
        return .decimal(precision: Int(precision), scale: Int(scale))
    case .list:
        return .list(try rowReadSchemaField(buffer, depth: depth + 1))
    case .map:
        let key = try rowReadSchemaField(buffer, depth: depth + 1)
        let value = try rowReadSchemaField(buffer, depth: depth + 1)
        // Only the key and value types are kept; their names and nullability are always the standard ones.
        return .mapOf(key.dataType, value.dataType)
    case .structType:
        return .struct(try rowReadSchemaFields(buffer, depth: depth + 1))
    default:
        throw rowSchemaUnknownTypeId(typeId)
    }
}

// Folds `hash * 31 + typeId` over a type and then its children in order. When either step would
// overflow, the hash is shifted right by 2, keeping its sign, and the step is tried again.
private func rowSchemaHash(_ hash: Int64, _ dataType: RowDataType) -> Int64 {
    let typeId = Int64(dataType.typeId.rawValue)
    var hash = hash
    while true {
        let (product, multiplyOverflow) = hash.multipliedReportingOverflow(by: 31)
        let (sum, addOverflow) = product.addingReportingOverflow(typeId)
        if !multiplyOverflow && !addOverflow {
            hash = sum
            break
        }
        hash >>= 2
    }
    switch dataType {
    case .list(let element):
        return rowSchemaHash(hash, element.dataType)
    case .map(let key, let value):
        return rowSchemaHash(rowSchemaHash(hash, key.dataType), value.dataType)
    case .struct(let fields):
        return fields.reduce(hash) { rowSchemaHash($0, $1.dataType) }
    default:
        return hash
    }
}

@inline(never)
private func rowSchemaTooDeepToWrite() -> ForyError {
    .encodingError("row schema nests \(rowSchemaMaxDepth) or more levels deep")
}

@inline(never)
private func rowSchemaEmptyFieldName() -> ForyError {
    .encodingError("row schema field has an empty name")
}

@inline(never)
private func rowSchemaDecimalOutOfRange(precision: Int, scale: Int) -> ForyError {
    .encodingError("decimal(\(precision), \(scale)) precision and scale must each fit in one byte")
}

@inline(never)
private func rowSchemaVersionUnsupported(_ version: UInt8) -> ForyError {
    .invalidData("unsupported row schema version \(version), expected \(rowSchemaVersion)")
}

@inline(never)
private func rowSchemaCountTooLarge(_ count: UInt32, remaining: Int) -> ForyError {
    .invalidData("row schema declares \(count) fields but only \(remaining) bytes remain")
}

@inline(never)
private func rowSchemaTooDeep() -> ForyError {
    .invalidData("row schema nests \(rowSchemaMaxDepth) or more levels deep")
}

@inline(never)
private func rowSchemaUnknownNameEncoding(_ index: Int) -> ForyError {
    .invalidData("unknown row schema field name encoding \(index)")
}

@inline(never)
private func rowSchemaUnknownTypeId(_ typeId: UInt8) -> ForyError {
    .invalidData("unknown type id \(typeId) in row schema")
}
