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

func buildReadDataDecl(
  isClass: Bool,
  fields: [ParsedField],
  sortedFields: [ParsedField],
  accessPrefix: String
) -> String {
  if isClass {
    return buildClassReadDataDecl(sortedFields: sortedFields, accessPrefix: accessPrefix)
  }
  if fields.isEmpty {
    return buildEmptyStructReadDataDecl(accessPrefix: accessPrefix)
  }
  return buildStructReadDataDecl(
    fields: fields, sortedFields: sortedFields, accessPrefix: accessPrefix)
}

func buildReadCompatibleDataDecl(
  isClass: Bool,
  fields: [ParsedField],
  sortedFields: [ParsedField],
  accessPrefix: String
) -> String {
  if isClass {
    return buildClassReadCompatibleDataDecl(sortedFields: sortedFields, accessPrefix: accessPrefix)
  }
  if fields.isEmpty {
    return buildEmptyStructReadCompatibleDataDecl(accessPrefix: accessPrefix)
  }
  return buildStructReadCompatibleDataDecl(
    fields: fields, sortedFields: sortedFields, accessPrefix: accessPrefix)
}

private func graphFieldBytesExpr(_ field: ParsedField) -> String {
  if field.primitiveSize > 0 {
    return "\(field.primitiveSize)"
  }
  return "(\(field.typeText).isRefType ? 4 : max(1, MemoryLayout<\(field.typeText)>.stride))"
}

private func classGraphOwnerBytesExpr(_ fields: [ParsedField]) -> String {
  if fields.isEmpty {
    return "1"
  }
  return "max(1, 1 + " + fields.map(graphFieldBytesExpr).joined(separator: " + ") + ")"
}

private func reserveClassGraphOwnerLine(fields: [ParsedField], indent: String) -> String {
  "\(indent)try context.reserveGraphMemory(\(classGraphOwnerBytesExpr(fields)))"
}

private func reserveValueGraphOwnerLine(indent: String) -> String {
  "\(indent)try context.reserveGraphMemory(max(1, MemoryLayout<Self>.stride))"
}

func buildClassReadWrapperDecl(accessPrefix: String) -> String {
  """
  @inline(__always)
  \(accessPrefix)static func foryRead(
      _ context: ReadContext,
      refMode: RefMode,
      readTypeInfo: Bool
  ) throws -> Self {
      let __buffer = context.buffer
      let __reservedRefID: UInt32?
      if refMode != .none {
          let rawFlag = try __buffer.readInt8()
          guard let flag = RefFlag(rawValue: rawFlag) else {
              throw ForyError.refError("invalid ref flag \\(rawFlag)")
          }

          switch flag {
          case .null:
              return Self.foryDefault()
          case .ref:
              let refID = try __buffer.readVarUInt32()
              return try context.refReader.readRef(refID, as: Self.self)
          case .refValue:
              __reservedRefID = context.trackRef ? context.refReader.reserveRefID() : nil
          case .notNullValue:
              __reservedRefID = nil
          }
      } else {
          __reservedRefID = nil
      }

      return try Self.foryReadPayload(
          context,
          readTypeInfo: readTypeInfo,
          readData: {
              try Self.__foryReadDataImpl(context, reservedRefID: __reservedRefID)
          },
          readCompatibleData: { remoteTypeInfo in
              try Self.__foryReadCompatibleDataImpl(
                  context,
                  remoteTypeInfo: remoteTypeInfo,
                  reservedRefID: __reservedRefID
              )
          }
      )
  }
  """
}

private func buildClassReadDataDecl(
  sortedFields: [ParsedField],
  accessPrefix: String
) -> String {
  let primitiveFastFields = leadingPrimitiveFastPathFields(sortedFields)
  let schemaAssignBody = buildClassAssignBody(
    sortedFields: sortedFields, primitiveFastFields: primitiveFastFields, compatibleAligned: false)

  return """
    @inline(__always)
    private static func __foryReadDataImpl(_ context: ReadContext, reservedRefID: UInt32?) throws -> Self {
        let __buffer = context.buffer
        \(schemaHashCheckExpr())
        \(reserveClassGraphOwnerLine(fields: sortedFields, indent: "        "))
        let value = Self.init()
        if let reservedRefID {
            context.refReader.storeRef(value, at: reservedRefID)
        }
        \(schemaAssignBody)
        return value
    }

    @inline(__always)
    \(accessPrefix)static func foryReadData(_ context: ReadContext) throws -> Self {
        try Self.__foryReadDataImpl(context, reservedRefID: nil)
    }
    """
}

private func buildEmptyStructReadDataDecl(accessPrefix: String) -> String {
  """
  @inline(__always)
  \(accessPrefix)static func foryReadData(_ context: ReadContext) throws -> Self {
      let __buffer = context.buffer
      \(schemaHashCheckExpr())
      \(reserveValueGraphOwnerLine(indent: "      "))
      return Self()
  }
  """
}

private func buildStructReadDataDecl(
  fields: [ParsedField],
  sortedFields: [ParsedField],
  accessPrefix: String
) -> String {
  let primitiveFastFields = leadingPrimitiveFastPathFields(sortedFields)
  let schemaReadBody = buildStructReadBody(
    sortedFields: sortedFields,
    primitiveFastFields: primitiveFastFields,
    compatibleAligned: false
  )
  let ctorArgs = buildCtorArgs(fields)

  return """
    @inline(__always)
    \(accessPrefix)static func foryReadData(_ context: ReadContext) throws -> Self {
        let __buffer = context.buffer
        \(schemaHashCheckExpr())
        \(reserveValueGraphOwnerLine(indent: "        "))
        \(schemaReadBody)
        return Self(
            \(ctorArgs)
        )
    }
    """
}

private func buildClassReadCompatibleDataDecl(
  sortedFields: [ParsedField],
  accessPrefix: String
) -> String {
  let primitiveFastFields = leadingPrimitiveFastPathFields(sortedFields)
  let schemaAssignBody = buildClassAssignBody(
    sortedFields: sortedFields, primitiveFastFields: primitiveFastFields, compatibleAligned: false)
  let compatibleAlignedAssignBody = buildClassAssignBody(
    sortedFields: sortedFields,
    primitiveFastFields: primitiveFastFields,
    compatibleAligned: true
  )
  let compatibleCases = buildCompatibleReadCases(
    sortedFields: sortedFields, indent: "                "
  ) { sortedIndex, field, valueExpr in
    "case \(sortedIndex): value.\(field.name) = \(valueExpr)"
  }
  let bufferBinding =
    (schemaAssignBody.contains("__buffer") || compatibleAlignedAssignBody.contains("__buffer")
      || compatibleCases.contains("__buffer")) ? "let __buffer = context.buffer\n        " : ""
  let localFieldsBinding =
    compatibleCases.contains("__foryLocalFields")
    ? "let __foryLocalFields = remoteTypeInfo.typeMeta?.fields ?? Self.foryFieldsInfo(trackRef: context.trackRef)\n        "
    : ""

  return """
    @inline(never)
    private static func __foryReadCompatibleDataImpl(
        _ context: ReadContext,
        remoteTypeInfo: TypeInfo,
        reservedRefID: UInt32?
    ) throws -> Self {
        \(bufferBinding)guard let typeMeta = remoteTypeInfo.compatibleTypeMeta else {
            throw ForyError.invalidData("compatible type metadata is required")
        }
        \(reserveClassGraphOwnerLine(fields: sortedFields, indent: "        "))
        let value = Self.init()
        if let reservedRefID {
            context.refReader.storeRef(value, at: reservedRefID)
        }
        if let localTypeMeta = remoteTypeInfo.typeMeta,
           let localHeaderHash = remoteTypeInfo.typeDefHeaderHash,
           typeMeta.headerHash == localHeaderHash,
           typeMeta.fields == localTypeMeta.fields {
            if !remoteTypeInfo.typeDefHasUserTypeFields {
                \(schemaAssignBody)
                return value
            }
            \(compatibleAlignedAssignBody)
            return value
        }
        \(localFieldsBinding)for remoteField in typeMeta.fields {
            switch Int(remoteField.fieldID ?? -1) {
        \(compatibleCases)
            case -1:
                try context.skipFieldValue(remoteField.fieldType)
            default:
                throw ForyError.invalidData("invalid compatible matched id \\(remoteField.fieldID ?? -2)")
            }
        }
        return value
    }

    @inline(never)
    \(accessPrefix)static func foryReadCompatibleData(_ context: ReadContext, remoteTypeInfo: TypeInfo) throws -> Self {
        try Self.__foryReadCompatibleDataImpl(context, remoteTypeInfo: remoteTypeInfo, reservedRefID: nil)
    }
    """
}

private func buildEmptyStructReadCompatibleDataDecl(accessPrefix: String) -> String {
  """
  @inline(never)
  \(accessPrefix)static func foryReadCompatibleData(_ context: ReadContext, remoteTypeInfo: TypeInfo) throws -> Self {
      guard let typeMeta = remoteTypeInfo.compatibleTypeMeta else {
          throw ForyError.invalidData("compatible type metadata is required")
      }
      \(reserveValueGraphOwnerLine(indent: "      "))
      if let localTypeMeta = remoteTypeInfo.typeMeta,
         let localHeaderHash = remoteTypeInfo.typeDefHeaderHash,
         typeMeta.headerHash == localHeaderHash,
         typeMeta.fields == localTypeMeta.fields {
          return Self()
      }
      for remoteField in typeMeta.fields {
          try context.skipFieldValue(remoteField.fieldType)
      }
      return Self()
  }
  """
}

private func buildStructReadCompatibleDataDecl(
  fields: [ParsedField],
  sortedFields: [ParsedField],
  accessPrefix: String
) -> String {
  let primitiveFastFields = leadingPrimitiveFastPathFields(sortedFields)
  let schemaReadBody = buildStructReadBody(
    sortedFields: sortedFields,
    primitiveFastFields: primitiveFastFields,
    compatibleAligned: false
  )
  let compatibleAlignedReadBody = buildStructReadBody(
    sortedFields: sortedFields,
    primitiveFastFields: primitiveFastFields,
    compatibleAligned: true
  )
  let ctorArgs = buildCtorArgs(fields)
  let compatibleDefaults = buildStructCompatibleDefaults(fields)
  let compatibleCases = buildCompatibleReadCases(
    sortedFields: sortedFields, indent: "                    "
  ) { sortedIndex, field, valueExpr in
    "case \(sortedIndex): __\(field.name) = \(valueExpr)"
  }
  let changedFallbackDecl = buildStructChangedFallbackDecl(
    defaults: compatibleDefaults,
    cases: compatibleCases,
    ctorArgs: ctorArgs
  )
  let bufferBinding =
    (schemaReadBody.contains("__buffer") || compatibleAlignedReadBody.contains("__buffer"))
    ? "let __buffer = context.buffer\n        " : ""

  return """
    \(changedFallbackDecl)

    @inline(never)
    \(accessPrefix)static func foryReadCompatibleData(_ context: ReadContext, remoteTypeInfo: TypeInfo) throws -> Self {
        \(bufferBinding)guard let typeMeta = remoteTypeInfo.compatibleTypeMeta else {
            throw ForyError.invalidData("compatible type metadata is required")
        }
        \(reserveValueGraphOwnerLine(indent: "        "))
        if let localTypeMeta = remoteTypeInfo.typeMeta,
           let localHeaderHash = remoteTypeInfo.typeDefHeaderHash,
           typeMeta.headerHash == localHeaderHash,
           typeMeta.fields == localTypeMeta.fields {
            if !remoteTypeInfo.typeDefHasUserTypeFields {
                \(schemaReadBody)
                return Self(
                    \(ctorArgs)
                )
            }
            \(compatibleAlignedReadBody)
            return Self(
                \(ctorArgs)
            )
        }
        return try Self.__foryReadChangedData(
            context,
            typeMeta: typeMeta
        )
    }
    """
}

private func buildStructChangedFallbackDecl(
  defaults: String,
  cases: String,
  ctorArgs: String
) -> String {
  let bufferBinding = cases.contains("__buffer") ? "let __buffer = context.buffer\n        " : ""
  let localFieldsBinding =
    cases.contains("__foryLocalFields")
    ? "let __foryLocalFields = Self.foryFieldsInfo(trackRef: context.trackRef)\n          " : ""
  return """
      @inline(never)
      private static func __foryReadChangedData(
          _ context: ReadContext,
          typeMeta: TypeMeta
      ) throws -> Self {
          \(bufferBinding)
          \(defaults)
          \(localFieldsBinding)for remoteField in typeMeta.fields {
              switch Int(remoteField.fieldID ?? -1) {
              \(cases)
              case -1:
                  try context.skipFieldValue(remoteField.fieldType)
              default:
                  throw ForyError.invalidData("invalid compatible matched id \\(remoteField.fieldID ?? -2)")
              }
          }
          return Self(
              \(ctorArgs)
          )
      }
    """
}

private func buildClassAssignBody(
  sortedFields: [ParsedField],
  primitiveFastFields: [ParsedField],
  compatibleAligned: Bool
) -> String {
  let remainingAssignLines = sortedFields.dropFirst(primitiveFastFields.count).map {
    field -> String in
    let valueExpr: String
    if compatibleAligned {
      valueExpr = compatibleSchemaReadFieldExpr(field)
    } else {
      valueExpr = readFieldExpr(
        field,
        refModeExpr: fieldRefModeExpression(field),
        readTypeInfoExpr: "false"
      )
    }
    return "value.\(field.name) = \(valueExpr)"
  }

  var sections: [String] = []
  if let primitiveReadBlock = buildPrimitiveFastClassReadBlock(primitiveFastFields) {
    sections.append(primitiveReadBlock)
  }
  if !remainingAssignLines.isEmpty {
    sections.append(remainingAssignLines.joined(separator: "\n        "))
  }
  if sections.isEmpty {
    sections.append("_ = context")
  }
  return sections.joined(separator: "\n        ")
}

private func buildStructReadBody(
  sortedFields: [ParsedField],
  primitiveFastFields: [ParsedField],
  compatibleAligned: Bool
) -> String {
  let remainingReadLines = sortedFields.dropFirst(primitiveFastFields.count).map {
    field -> String in
    let valueExpr =
      compatibleAligned ? compatibleSchemaReadFieldExpr(field) : schemaReadFieldExpr(field)
    return "let __\(field.name) = \(valueExpr)"
  }

  var sections: [String] = []
  if let primitiveDeclarations = buildPrimitiveFastStructReadDeclarations(primitiveFastFields) {
    sections.append(primitiveDeclarations)
  }
  if let primitiveReadBlock = buildPrimitiveFastStructReadBlock(primitiveFastFields) {
    sections.append(primitiveReadBlock)
  }
  if !remainingReadLines.isEmpty {
    sections.append(remainingReadLines.joined(separator: "\n        "))
  }
  return sections.joined(separator: "\n        ")
}

private func buildCtorArgs(_ fields: [ParsedField]) -> String {
  fields
    .sorted(by: { $0.originalIndex < $1.originalIndex })
    .map { "\($0.name): __\($0.name)" }
    .joined(separator: ",\n            ")
}

private func buildStructCompatibleDefaults(_ fields: [ParsedField]) -> String {
  fields
    .sorted(by: { $0.originalIndex < $1.originalIndex })
    .map(compatibleDefaultDecl)
    .joined(separator: "\n                ")
}

private func schemaHashCheckExpr(indent: String = "        ") -> String {
  """
  \(indent)if context.checkClassVersion {
  \(indent)    let __schemaHash = UInt32(bitPattern: try __buffer.readInt32())
  \(indent)    let __expectedHash = Self.__forySchemaHash(context.trackRef)
  \(indent)    if __schemaHash != __expectedHash {
  \(indent)        throw ForyError.invalidData("class version hash mismatch: expected \\(__expectedHash), got \\(__schemaHash)")
  \(indent)    }
  \(indent)}
  """
}

private func buildCompatibleReadCases(
  sortedFields: [ParsedField],
  indent: String,
  assignCase: (Int, ParsedField, String) -> String
) -> String {
  sortedFields.enumerated().map { sortedIndex, field -> String in
    let directValueExpr = compatibleSchemaReadFieldExpr(field)
    let compatibleValueExpr = readFieldExpr(
      field,
      refModeExpr:
        "RefMode.from(nullable: remoteField.fieldType.nullable, trackRef: remoteField.fieldType.trackRef)",
      readTypeInfoExpr:
        "TypeId.needsTypeInfoForField(TypeId(rawValue: remoteField.fieldType.typeID) ?? .unknown)"
    )
    let compatibleCaseExpr = compatibleScalarReadExpr(
      field,
      sortedIndex: sortedIndex,
      compatibleValueExpr: compatibleValueExpr
    )
    return [
      assignCase(sortedIndex * 2, field, directValueExpr),
      assignCase(sortedIndex * 2 + 1, field, compatibleCaseExpr),
    ].joined(separator: "\n\(indent)")
  }.joined(separator: "\n\(indent)")
}

private func compatibleScalarReadExpr(
  _ field: ParsedField,
  sortedIndex: Int,
  compatibleValueExpr: String
) -> String {
  guard
    field.dynamicAnyCodec == nil,
    let helperTarget = compatibleScalarReaderTarget(field)
  else {
    return compatibleValueExpr
  }
  let helperName =
    field.isOptional
    ? "foryReadCompatibleOptional\(helperTarget)Field"
    : "foryReadCompatible\(helperTarget)Field"
  return """
    try \(helperName)(
        context,
        remoteField: remoteField,
        localField: __foryLocalFields[\(sortedIndex)]
    )
    """
}

private func compatibleScalarReaderTarget(_ field: ParsedField) -> String? {
  guard compatibleScalarTypeID(field.typeID) else {
    return nil
  }
  switch compatibleScalarPayloadType(field.typeText) {
  case "Bool":
    return "Bool"
  case "Int8":
    return "Int8"
  case "Int16":
    return "Int16"
  case "Int32":
    return "Int32"
  case "Int64":
    return "Int64"
  case "Int":
    return "Int"
  case "UInt8":
    return "UInt8"
  case "UInt16":
    return "UInt16"
  case "UInt32":
    return "UInt32"
  case "UInt64":
    return "UInt64"
  case "UInt":
    return "UInt"
  case "Float16":
    return "Float16"
  case "BFloat16":
    return "BFloat16"
  case "Float":
    return "Float"
  case "Double":
    return "Double"
  case "String":
    return "String"
  case "Decimal":
    return "Decimal"
  default:
    return nil
  }
}

private func compatibleScalarPayloadType(_ typeText: String) -> String {
  var type = trimType(typeText)
  if type.hasSuffix("?") {
    type.removeLast()
  } else if type.hasPrefix("Optional<"), type.hasSuffix(">") {
    type = String(type.dropFirst("Optional<".count).dropLast())
  }
  for prefix in ["Swift.", "Foundation.", "Fory."] where type.hasPrefix(prefix) {
    return String(type.dropFirst(prefix.count))
  }
  return type
}

private func compatibleScalarTypeID(_ typeID: UInt32) -> Bool {
  switch typeID {
  case 1...15, 17...21, 40:
    return true
  default:
    return false
  }
}

private func swiftStringLiteral(_ value: String) -> String {
  let escaped =
    value
    .replacingOccurrences(of: "\\", with: "\\\\")
    .replacingOccurrences(of: "\"", with: "\\\"")
  return "\"\(escaped)\""
}

private func readFieldExpr(
  _ field: ParsedField,
  refModeExpr: String,
  readTypeInfoExpr: String
) -> String {
  if let dynamicAnyCodec = field.dynamicAnyCodec {
    return dynamicAnyReadExpr(
      field: field,
      dynamicAnyCodec: dynamicAnyCodec,
      refModeExpr: refModeExpr
    )
  }
  if let codecType = field.customCodecType {
    let fieldCodec = field.isOptional ? "OptionalFieldCodec<\(codecType)>" : codecType
    if readTypeInfoExpr.contains("remoteField.fieldType") {
      return """
        try \(fieldCodec).readCompatibleField(
            context,
            remoteFieldType: remoteField.fieldType,
            refMode: \(refModeExpr)
        )
        """
    }
    return "try \(fieldCodec).read(context, refMode: \(refModeExpr), readTypeInfo: false)"
  }
  return
    "try \(field.typeText).foryRead(context, refMode: \(refModeExpr), readTypeInfo: \(readTypeInfoExpr))"
}

private func schemaReadFieldExpr(_ field: ParsedField) -> String {
  if fieldNeedsGeneralSchemaRead(field) {
    return readFieldExpr(
      field,
      refModeExpr: fieldRefModeExpression(field),
      readTypeInfoExpr: "false"
    )
  }
  if let primitiveExpr = primitiveSchemaReadExpr(field) {
    return primitiveExpr
  }
  return "try \(field.typeText).foryReadData(context)"
}

private func compatibleSchemaReadFieldExpr(_ field: ParsedField) -> String {
  if fieldNeedsGeneralCompatibleRead(field) {
    return readFieldExpr(
      field,
      refModeExpr: fieldRefModeExpression(field),
      readTypeInfoExpr: "TypeId.needsTypeInfoForField(\(field.typeText).staticTypeId)"
    )
  }
  if let primitiveExpr = primitiveSchemaReadExpr(field) {
    return primitiveExpr
  }
  return "try \(field.typeText).foryReadData(context)"
}

private func primitiveSchemaReadExpr(_ field: ParsedField) -> String? {
  let type = trimType(field.typeText)
  switch type {
  case "Bool":
    return "try __buffer.readUInt8() != 0"
  case "Int8":
    return "try __buffer.readInt8()"
  case "Int16":
    return "try __buffer.readInt16()"
  case "Int32":
    return "try __buffer.readVarInt32()"
  case "Int64":
    return "try __buffer.readVarInt64()"
  case "Int":
    return "Int(try __buffer.readVarInt64())"
  case "UInt8":
    return "try __buffer.readUInt8()"
  case "UInt16":
    return "try __buffer.readUInt16()"
  case "UInt32":
    return "try __buffer.readVarUInt32()"
  case "UInt64":
    return "try __buffer.readVarUInt64()"
  case "UInt":
    return "UInt(try __buffer.readVarUInt64())"
  case "Float":
    return "try __buffer.readFloat32()"
  case "Double":
    return "try __buffer.readFloat64()"
  default:
    return nil
  }
}

private func dynamicAnyReadExpr(
  field: ParsedField,
  dynamicAnyCodec: DynamicAnyCodecKind,
  refModeExpr: String
) -> String {
  let metatypeExpr = "(\(field.typeText)).self"
  let method = dynamicAnyReadMethodName(dynamicAnyCodec)
  let readTypeInfoExpr =
    dynamicAnyReadsTypeInfo(dynamicAnyCodec)
    ? ", readTypeInfo: true"
    : ""
  return
    "try castAnyDynamicValue(\(method)(context: context, refMode: \(refModeExpr)\(readTypeInfoExpr)), to: \(metatypeExpr))"
}

private func compatibleDefaultDecl(_ field: ParsedField) -> String {
  let explicitType =
    (field.dynamicAnyCodec != nil || field.customCodecType != nil) ? ": \(field.typeText)" : ""
  return "var __\(field.name)\(explicitType) = \(fieldDefaultExpr(field))"
}

private func fieldNeedsGeneralSchemaRead(_ field: ParsedField) -> Bool {
  field.dynamicAnyCodec != nil || field.customCodecType != nil || field.isOptional
    || field.typeID == 27
}

private func fieldNeedsGeneralCompatibleRead(_ field: ParsedField) -> Bool {
  fieldNeedsGeneralSchemaRead(field) || compatibleFieldNeedsTypeInfo(field)
}
