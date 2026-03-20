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

func leadingPrimitiveFastPathFields(_ fields: [ParsedField]) -> [ParsedField] {
    var result: [ParsedField] = []
    result.reserveCapacity(fields.count)
    for field in fields {
        if isPrimitiveFastPathField(field) {
            result.append(field)
        } else {
            break
        }
    }
    return result
}

private func leadingFixedPrimitiveFields(_ fields: [ParsedField]) -> [ParsedField] {
    var result: [ParsedField] = []
    result.reserveCapacity(fields.count)
    for field in fields {
        if primitiveFixedByteWidth(for: field) != nil {
            result.append(field)
        } else {
            break
        }
    }
    return result
}

private func primitiveFixedPrefixBytes(_ fields: [ParsedField]) -> Int {
    fields.reduce(0) { partial, field in
        partial + (primitiveFixedByteWidth(for: field) ?? 0)
    }
}

private func primitiveFixedByteWidth(for field: ParsedField) -> Int? {
    switch trimType(field.typeText) {
    case "Bool", "Int8", "UInt8":
        return 1
    case "Int16", "UInt16":
        return 2
    case "Float":
        return 4
    case "Double":
        return 8
    default:
        return nil
    }
}

private func isPrimitiveFastPathField(_ field: ParsedField) -> Bool {
    guard !field.isOptional else {
        return false
    }
    guard field.dynamicAnyCodec == nil, field.customCodecType == nil else {
        return false
    }
    guard field.typeID != 27, !compatibleFieldNeedsTypeInfo(field) else {
        return false
    }
    return primitiveUnsafeWriteMethod(for: field) != nil && primitiveUnsafeReadMethod(for: field) != nil
}

func buildPrimitiveFastWriteBlock(_ fields: [ParsedField]) -> String? {
    guard !fields.isEmpty else {
        return nil
    }
    let fixedFields = leadingFixedPrimitiveFields(fields)
    let remainingFields = Array(fields.dropFirst(fixedFields.count))
    let fixedPrefixBytes = primitiveFixedPrefixBytes(fixedFields)
    let maxNumericBytes = fields.reduce(0) { partial, field in
        partial + (primitiveMaxEncodedByteWidth(for: field) ?? 0)
    }
    guard maxNumericBytes > 0 else {
        return nil
    }
    let locals = fields.map { field in
        "let __\(field.name) = self.\(field.name)"
    }.joined(separator: "\n        ")
    var fixedOffset = 0
    let fixedWrites = fixedFields.compactMap { field -> String? in
        guard let line = primitiveUnsafeWriteFixedLine(for: field, offset: fixedOffset) else {
            return nil
        }
        fixedOffset += primitiveFixedByteWidth(for: field) ?? 0
        return line
    }.joined(separator: "\n            ")
    let remainingWrites = remainingFields.compactMap { field in
        primitiveUnsafeWriteAdvanceLine(for: field, indexExpr: "__writerIndex")
    }.joined(separator: "\n            ")
    var bodySections: [String] = []
    if !fixedWrites.isEmpty {
        bodySections.append(fixedWrites)
    }
    if !remainingWrites.isEmpty {
        bodySections.append(
            """
            var __writerIndex = \(fixedPrefixBytes)
            \(remainingWrites)
            assert(__writerIndex <= \(maxNumericBytes))
            return __writerIndex
            """
        )
    } else {
        bodySections.append("return \(fixedPrefixBytes)")
    }
    let writeBody = bodySections.joined(separator: "\n            ")
    return """
    \(locals)
    UnsafeUtil.writeRegion(buffer: __buffer, maxCount: \(maxNumericBytes)) { __base in
        \(writeBody)
    }
    """
}

private func primitiveMaxEncodedByteWidth(for field: ParsedField) -> Int? {
    switch trimType(field.typeText) {
    case "Bool", "Int8", "UInt8":
        return 1
    case "Int16", "UInt16":
        return 2
    case "Float":
        return 4
    case "Double":
        return 8
    case "Int32":
        return 5
    case "UInt32":
        return 5
    case "Int64":
        return 9
    case "UInt64":
        return 9
    case "Int":
        return 9
    case "UInt":
        return 9
    default:
        return nil
    }
}

private func primitiveUnsafeWriteFixedLine(for field: ParsedField, offset: Int) -> String? {
    guard let method = primitiveUnsafeWriteMethod(for: field) else {
        return nil
    }
    return "_ = UnsafeUtil.\(method)(__\(field.name), to: __base, index: \(offset))"
}

private func primitiveUnsafeWriteAdvanceLine(for field: ParsedField, indexExpr: String) -> String? {
    guard let method = primitiveUnsafeWriteMethod(for: field) else {
        return nil
    }
    return "__writerIndex = UnsafeUtil.\(method)(__\(field.name), to: __base, index: \(indexExpr))"
}

private func primitiveUnsafeWriteMethod(for field: ParsedField) -> String? {
    switch trimType(field.typeText) {
    case "Bool":
        return "writeBool"
    case "Int8":
        return "writeInt8"
    case "Int16":
        return "writeInt16"
    case "Int32":
        return "writeInt32"
    case "Int64":
        return "writeInt64"
    case "Int":
        return "writeInt"
    case "UInt8":
        return "writeUInt8"
    case "UInt16":
        return "writeUInt16"
    case "UInt32":
        return "writeUInt32"
    case "UInt64":
        return "writeUInt64"
    case "UInt":
        return "writeUInt"
    case "Float":
        return "writeFloat32"
    case "Double":
        return "writeFloat64"
    default:
        return nil
    }
}

private func primitiveUnsafeReadMethod(for field: ParsedField) -> String? {
    switch trimType(field.typeText) {
    case "Bool":
        return "readBool"
    case "Int8":
        return "readInt8"
    case "Int16":
        return "readInt16"
    case "Int32":
        return "readInt32"
    case "Int64":
        return "readInt64"
    case "Int":
        return "readInt"
    case "UInt8":
        return "readUInt8"
    case "UInt16":
        return "readUInt16"
    case "UInt32":
        return "readUInt32"
    case "UInt64":
        return "readUInt64"
    case "UInt":
        return "readUInt"
    case "Float":
        return "readFloat32"
    case "Double":
        return "readFloat64"
    default:
        return nil
    }
}

private func primitiveUnsafeFixedReadMethod(for field: ParsedField) -> String? {
    switch trimType(field.typeText) {
    case "Bool":
        return "readBoolUnchecked"
    case "Int8":
        return "readInt8Unchecked"
    case "UInt8":
        return "readUInt8Unchecked"
    case "Int16":
        return "readInt16Unchecked"
    case "UInt16":
        return "readUInt16Unchecked"
    case "Float":
        return "readFloat32Unchecked"
    case "Double":
        return "readFloat64Unchecked"
    default:
        return nil
    }
}

private func primitiveUnsafeFixedReadExpr(for field: ParsedField, baseExpr: String, offset: Int) -> String? {
    guard let method = primitiveUnsafeFixedReadMethod(for: field) else {
        return nil
    }
    return "UnsafeUtil.\(method)(from: \(baseExpr), index: \(offset))"
}

private func primitiveUnsafePointerReadAdvanceExpr(for field: ParsedField) -> String? {
    guard let method = primitiveUnsafeReadMethod(for: field) else {
        return nil
    }
    return "try UnsafeUtil.\(method)(from: __base, length: __length, index: &__readerIndex)"
}

private struct PrimitiveFastReadLayout {
    let statements: [String]
    let consumedExpr: String
    let fixedPrefixBytes: Int
}

private func buildPrimitiveFastReadStatements(
    _ fields: [ParsedField],
    assignLine: (ParsedField, String) -> String,
    remainingReadExpr: (ParsedField) -> String?
) -> PrimitiveFastReadLayout? {
    guard !fields.isEmpty else {
        return nil
    }
    let fixedFields = leadingFixedPrimitiveFields(fields)
    let remainingFields = Array(fields.dropFirst(fixedFields.count))
    let fixedPrefixBytes = primitiveFixedPrefixBytes(fixedFields)
    var fixedOffset = 0
    let fixedReads = fixedFields.compactMap { field -> String? in
        guard let readExpr = primitiveUnsafeFixedReadExpr(for: field, baseExpr: "__base", offset: fixedOffset) else {
            return nil
        }
        fixedOffset += primitiveFixedByteWidth(for: field) ?? 0
        return assignLine(field, readExpr)
    }.joined(separator: "\n            ")
    let remainingReads = remainingFields.compactMap { field -> String? in
        guard let readExpr = remainingReadExpr(field) else {
            return nil
        }
        return assignLine(field, readExpr)
    }.joined(separator: "\n            ")
    var readSections: [String] = []
    if !fixedReads.isEmpty {
        readSections.append(fixedReads)
    }
    if !remainingReads.isEmpty {
        readSections.append("var __readerIndex = \(fixedPrefixBytes)")
        readSections.append(remainingReads)
    }
    let consumedExpr = remainingReads.isEmpty ? "\(fixedPrefixBytes)" : "__readerIndex"
    return PrimitiveFastReadLayout(
        statements: readSections,
        consumedExpr: consumedExpr,
        fixedPrefixBytes: fixedPrefixBytes
    )
}

private func buildPrimitiveFastReadBlock(
    _ fields: [ParsedField],
    assignLine: (ParsedField, String) -> String
) -> String? {
    guard let readLayout = buildPrimitiveFastReadStatements(
        fields,
        assignLine: assignLine,
        remainingReadExpr: primitiveUnsafePointerReadAdvanceExpr
    ) else {
        return nil
    }
    var readSections: [String] = []
    if readLayout.fixedPrefixBytes > 0 {
        readSections.append("try UnsafeUtil.checkReadable(__bytes, index: 0, need: \(readLayout.fixedPrefixBytes))")
    }
    readSections.append(
        """
        guard let __base = __bytes.baseAddress else {
            throw ForyError.outOfBounds(cursor: 0, need: \(max(readLayout.fixedPrefixBytes, 1)), length: __bytes.count)
        }
        """
    )
    readSections.append("let __length = __bytes.count")
    readSections.append(contentsOf: readLayout.statements)
    readSections.append("return \(readLayout.consumedExpr)")
    let readBody = readSections.joined(separator: "\n            ")
    return """
    try UnsafeUtil.readRegion(buffer: __buffer) { __bytes in
        \(readBody)
    }
    """
}

func buildPrimitiveFastClassReadBlock(_ fields: [ParsedField]) -> String? {
    buildPrimitiveFastReadBlock(fields) { field, readExpr in
        "value.\(field.name) = \(readExpr)"
    }
}

func buildPrimitiveFastStructReadDeclarations(_ fields: [ParsedField]) -> String? {
    guard !fields.isEmpty else {
        return nil
    }
    return fields.map { field in
        "var __\(field.name): \(field.typeText) = \(field.typeText).foryDefault()"
    }.joined(separator: "\n        ")
}

func buildPrimitiveFastStructReadBlock(_ fields: [ParsedField]) -> String? {
    buildPrimitiveFastReadBlock(fields) { field, readExpr in
        "__\(field.name) = \(readExpr)"
    }
}
