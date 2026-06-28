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

private let maxCompatibleDecimalDigits = 256
private let maxCompatibleNumericTextLength = 320

private enum CompatibleScalarValue {
  case bool(Bool)
  case string(String)
  case signed(Int64)
  case unsigned(UInt64)
  case float16(Float16)
  case bfloat16(BFloat16)
  case float32(Float)
  case float64(Double)
  case decimal(Decimal)
}

private struct DecimalLiteral {
  let negative: Bool
  let digits: String
  let scale: Int
  let negativeZero: Bool

  var canonicalDecimalText: String? {
    compatibleFormatDecimalText(negative: negative && !isZero, digits: digits, scale: scale)
  }

  var isZero: Bool {
    !digits.contains { $0 != "0" }
  }
}

private struct BinaryFloatLayout {
  let sign: Bool
  let exponentBits: UInt64
  let significandBits: UInt64
  let exponentBitCount: Int
  let significandBitCount: Int
  let exponentBias: Int
}

@inline(never)
public func foryReadCompatibleBoolField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Bool {
  try foryReadCompatibleOptionalBoolField(
    context, remoteField: remoteField, localField: localField)
    ?? false
}

@inline(never)
public func foryReadCompatibleOptionalBoolField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Bool? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToBool(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleInt8Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int8 {
  try foryReadCompatibleOptionalInt8Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalInt8Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int8? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToInt8Target(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleInt16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int16 {
  try foryReadCompatibleOptionalInt16Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalInt16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int16? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToInt16Target(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleInt32Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int32 {
  try foryReadCompatibleOptionalInt32Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalInt32Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int32? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToInt32Target(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleInt64Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int64 {
  try foryReadCompatibleOptionalInt64Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalInt64Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int64? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToInt64(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleIntField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int {
  try foryReadCompatibleOptionalIntField(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalIntField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Int? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToIntTarget(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleUInt8Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt8 {
  try foryReadCompatibleOptionalUInt8Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalUInt8Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt8? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToUInt8Target(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleUInt16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt16 {
  try foryReadCompatibleOptionalUInt16Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalUInt16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt16? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToUInt16Target(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleUInt32Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt32 {
  try foryReadCompatibleOptionalUInt32Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalUInt32Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt32? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToUInt32Target(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleUInt64Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt64 {
  try foryReadCompatibleOptionalUInt64Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalUInt64Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt64? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToUInt64(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleUIntField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt {
  try foryReadCompatibleOptionalUIntField(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalUIntField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> UInt? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToUIntTarget(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleFloat16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Float16 {
  try foryReadCompatibleOptionalFloat16Field(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalFloat16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Float16? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToFloat16(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleBFloat16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> BFloat16 {
  try foryReadCompatibleOptionalBFloat16Field(
    context, remoteField: remoteField, localField: localField)
    ?? BFloat16()
}

@inline(never)
public func foryReadCompatibleOptionalBFloat16Field(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> BFloat16? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToBFloat16(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleFloatField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Float {
  try foryReadCompatibleOptionalFloatField(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalFloatField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Float? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToFloat(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleDoubleField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Double {
  try foryReadCompatibleOptionalDoubleField(
    context, remoteField: remoteField, localField: localField)
    ?? 0
}

@inline(never)
public func foryReadCompatibleOptionalDoubleField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Double? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToDouble(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleStringField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> String {
  try foryReadCompatibleOptionalStringField(
    context, remoteField: remoteField, localField: localField)
    ?? ""
}

@inline(never)
public func foryReadCompatibleOptionalStringField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> String? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToString(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleDecimalField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Decimal {
  try foryReadCompatibleOptionalDecimalField(
    context, remoteField: remoteField, localField: localField)
    ?? .zero
}

@inline(never)
public func foryReadCompatibleOptionalDecimalField(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  localField: TypeMeta.FieldInfo
) throws -> Decimal? {
  var remoteTypeID: TypeId = .unknown
  let resolvedLocalTypeID = try compatibleScalarLocalTypeID(localField)
  let fieldName = localField.fieldName
  guard
    let remoteValue = try readCompatibleScalarValue(
      context,
      remoteField: remoteField,
      fieldName: fieldName,
      remoteTypeID: &remoteTypeID)
  else {
    return nil
  }
  guard let converted = try compatibleScalarToDecimal(remoteValue) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: resolvedLocalTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

private func readCompatibleScalarValue(
  _ context: ReadContext,
  remoteField: TypeMeta.FieldInfo,
  fieldName: String,
  remoteTypeID: inout TypeId
) throws -> CompatibleScalarValue? {
  let remoteFieldType = remoteField.fieldType
  guard let resolvedRemoteTypeID = TypeId(rawValue: remoteFieldType.typeID) else {
    throw ForyError.invalidData(
      "unknown compatible scalar remote type \(remoteFieldType.typeID) for field \(fieldName)")
  }
  remoteTypeID = resolvedRemoteTypeID
  return try readCompatibleRemoteScalar(
    context, remoteTypeID: resolvedRemoteTypeID, fieldType: remoteFieldType)
}

private func compatibleScalarLocalTypeID(_ localField: TypeMeta.FieldInfo) throws -> TypeId {
  guard let localTypeID = TypeId(rawValue: localField.fieldType.typeID) else {
    throw ForyError.invalidData(
      "unknown compatible scalar local type for field \(localField.fieldName)")
  }
  return localTypeID
}

private func readCompatibleRemoteScalar(
  _ context: ReadContext,
  remoteTypeID: TypeId,
  fieldType: TypeMeta.FieldType
) throws -> CompatibleScalarValue? {
  let refMode = RefMode.from(nullable: fieldType.nullable, trackRef: fieldType.trackRef)
  switch refMode {
  case .none:
    return try readCompatibleRemoteScalarPayload(context, remoteTypeID: remoteTypeID)
  case .nullOnly:
    let rawFlag = try context.buffer.readInt8()
    switch rawFlag {
    case RefFlag.null.rawValue:
      return nil
    case RefFlag.notNullValue.rawValue:
      return try readCompatibleRemoteScalarPayload(context, remoteTypeID: remoteTypeID)
    default:
      throw ForyError.refError("invalid ref flag \(rawFlag)")
    }
  case .tracking:
    throw ForyError.invalidData("trackingRef scalar conversion is not supported")
  }
}

private func readCompatibleRemoteScalarPayload(
  _ context: ReadContext,
  remoteTypeID: TypeId
) throws -> CompatibleScalarValue {
  switch remoteTypeID {
  case .bool:
    return .bool(try readCompatibleBoolPayload(context))
  case .string:
    return .string(try StringCodec.read(context, refMode: .none, readTypeInfo: false))
  case .int8:
    return .signed(Int64(try Int8Codec.read(context, refMode: .none, readTypeInfo: false)))
  case .int16:
    return .signed(Int64(try Int16Codec.read(context, refMode: .none, readTypeInfo: false)))
  case .int32:
    return .signed(Int64(try Int32FixedCodec.read(context, refMode: .none, readTypeInfo: false)))
  case .varint32:
    return .signed(Int64(try Int32VarintCodec.read(context, refMode: .none, readTypeInfo: false)))
  case .int64:
    return .signed(try Int64FixedCodec.read(context, refMode: .none, readTypeInfo: false))
  case .varint64:
    return .signed(try Int64VarintCodec.read(context, refMode: .none, readTypeInfo: false))
  case .taggedInt64:
    return .signed(try Int64TaggedCodec.read(context, refMode: .none, readTypeInfo: false))
  case .uint8:
    return .unsigned(UInt64(try UInt8Codec.read(context, refMode: .none, readTypeInfo: false)))
  case .uint16:
    return .unsigned(UInt64(try UInt16Codec.read(context, refMode: .none, readTypeInfo: false)))
  case .uint32:
    return .unsigned(
      UInt64(try UInt32FixedCodec.read(context, refMode: .none, readTypeInfo: false)))
  case .varUInt32:
    return .unsigned(
      UInt64(try UInt32VarintCodec.read(context, refMode: .none, readTypeInfo: false)))
  case .uint64:
    return .unsigned(try UInt64FixedCodec.read(context, refMode: .none, readTypeInfo: false))
  case .varUInt64:
    return .unsigned(try UInt64VarintCodec.read(context, refMode: .none, readTypeInfo: false))
  case .taggedUInt64:
    return .unsigned(try UInt64TaggedCodec.read(context, refMode: .none, readTypeInfo: false))
  case .float16:
    return .float16(try Float16Codec.read(context, refMode: .none, readTypeInfo: false))
  case .bfloat16:
    return .bfloat16(try BFloat16Codec.read(context, refMode: .none, readTypeInfo: false))
  case .float32:
    return .float32(try FloatCodec.read(context, refMode: .none, readTypeInfo: false))
  case .float64:
    return .float64(try DoubleCodec.read(context, refMode: .none, readTypeInfo: false))
  case .decimal:
    return .decimal(try DecimalCodec.read(context, refMode: .none, readTypeInfo: false))
  default:
    throw ForyError.invalidData("unsupported compatible scalar type \(remoteTypeID.rawValue)")
  }
}

private func readCompatibleBoolPayload(_ context: ReadContext) throws -> Bool {
  let raw = try context.buffer.readUInt8()
  switch raw {
  case 0:
    return false
  case 1:
    return true
  default:
    throw ForyError.invalidData("bool payload is not 0 or 1")
  }
}

private func compatibleScalarToInt8Target(_ value: CompatibleScalarValue) throws -> Int8? {
  guard
    let converted = try compatibleScalarToInt64(value),
    converted >= Int64(Int8.min),
    converted <= Int64(Int8.max)
  else {
    return nil
  }
  return Int8(converted)
}

private func compatibleScalarToInt16Target(_ value: CompatibleScalarValue) throws -> Int16? {
  guard
    let converted = try compatibleScalarToInt64(value),
    converted >= Int64(Int16.min),
    converted <= Int64(Int16.max)
  else {
    return nil
  }
  return Int16(converted)
}

private func compatibleScalarToInt32Target(_ value: CompatibleScalarValue) throws -> Int32? {
  guard
    let converted = try compatibleScalarToInt64(value),
    converted >= Int64(Int32.min),
    converted <= Int64(Int32.max)
  else {
    return nil
  }
  return Int32(converted)
}

private func compatibleScalarToIntTarget(_ value: CompatibleScalarValue) throws -> Int? {
  guard let converted = try compatibleScalarToInt64(value) else {
    return nil
  }
  return Int(exactly: converted)
}

private func compatibleScalarToUInt8Target(_ value: CompatibleScalarValue) throws -> UInt8? {
  guard
    let converted = try compatibleScalarToUInt64(value),
    converted <= UInt64(UInt8.max)
  else {
    return nil
  }
  return UInt8(converted)
}

private func compatibleScalarToUInt16Target(_ value: CompatibleScalarValue) throws -> UInt16? {
  guard
    let converted = try compatibleScalarToUInt64(value),
    converted <= UInt64(UInt16.max)
  else {
    return nil
  }
  return UInt16(converted)
}

private func compatibleScalarToUInt32Target(_ value: CompatibleScalarValue) throws -> UInt32? {
  guard
    let converted = try compatibleScalarToUInt64(value),
    converted <= UInt64(UInt32.max)
  else {
    return nil
  }
  return UInt32(converted)
}

private func compatibleScalarToUIntTarget(_ value: CompatibleScalarValue) throws -> UInt? {
  guard let converted = try compatibleScalarToUInt64(value) else {
    return nil
  }
  return UInt(exactly: converted)
}

private func compatibleScalarToBool(_ value: CompatibleScalarValue) throws -> Bool? {
  switch value {
  case .bool(let value):
    return value
  case .string(let value):
    switch value {
    case "0", "false":
      return false
    case "1", "true":
      return true
    default:
      return nil
    }
  default:
    guard let decimal = try compatibleScalarToDecimalLiteral(value) else {
      return nil
    }
    if decimal.isZero {
      return false
    }
    return decimal.digits == "1" && decimal.scale == 0 ? true : nil
  }
}

private func compatibleScalarToString(_ value: CompatibleScalarValue) throws -> String? {
  switch value {
  case .bool(let value):
    return value ? "true" : "false"
  case .string(let value):
    return value
  case .signed(let value):
    return String(value)
  case .unsigned(let value):
    return String(value)
  case .float16(let value):
    return compatibleFiniteFloatText(value)
  case .bfloat16(let value):
    return compatibleFiniteBFloat16Text(value)
  case .float32(let value):
    return compatibleFiniteFloatText(value)
  case .float64(let value):
    return compatibleFiniteFloatText(value)
  case .decimal(let value):
    return compatibleDecimalText(value)
  }
}

private func compatibleScalarToInt64(_ value: CompatibleScalarValue) throws -> Int64? {
  switch value {
  case .bool(let value):
    return value ? 1 : 0
  case .signed(let value):
    return value
  case .unsigned(let value):
    guard value <= UInt64(Int64.max) else {
      return nil
    }
    return Int64(value)
  default:
    guard let literal = try compatibleScalarToDecimalLiteral(value), literal.scale == 0 else {
      return nil
    }
    return compatibleParseSignedDigits(literal)
  }
}

private func compatibleScalarToUInt64(_ value: CompatibleScalarValue) throws -> UInt64? {
  switch value {
  case .bool(let value):
    return value ? 1 : 0
  case .signed(let value):
    guard value >= 0 else {
      return nil
    }
    return UInt64(value)
  case .unsigned(let value):
    return value
  default:
    guard let literal = try compatibleScalarToDecimalLiteral(value), literal.scale == 0 else {
      return nil
    }
    if literal.negative && !literal.isZero {
      return nil
    }
    return UInt64(literal.digits)
  }
}

private func compatibleScalarToFloat16(_ value: CompatibleScalarValue) throws -> Float16? {
  guard let float = try compatibleScalarToFloat(value) else {
    return nil
  }
  let narrowed = Float16(float)
  if narrowed.isNaN {
    return nil
  }
  let roundTrip = Float(narrowed)
  if roundTrip == float && compatibleZeroSign(roundTrip) == compatibleZeroSign(float) {
    return narrowed
  }
  return nil
}

private func compatibleScalarToBFloat16(_ value: CompatibleScalarValue) throws -> BFloat16? {
  guard let float = try compatibleScalarToFloat(value) else {
    return nil
  }
  let narrowed = compatibleBFloat16(from: float)
  let roundTrip = compatibleFloat(from: narrowed)
  if roundTrip == float && compatibleZeroSign(roundTrip) == compatibleZeroSign(float) {
    return narrowed
  }
  return nil
}

private func compatibleScalarToFloat(_ value: CompatibleScalarValue) throws -> Float? {
  switch value {
  case .bool(let value):
    return value ? 1 : 0
  case .signed(let value):
    let converted = Float(value)
    guard converted.isFinite, Int64(exactly: converted) == value else {
      return nil
    }
    return converted
  case .unsigned(let value):
    let converted = Float(value)
    guard converted.isFinite, UInt64(exactly: converted) == value else {
      return nil
    }
    return converted
  case .float16(let value):
    let converted = Float(value)
    return converted.isNaN ? nil : converted
  case .bfloat16(let value):
    let converted = compatibleFloat(from: value)
    return converted.isNaN ? nil : converted
  case .float32(let value):
    return value.isNaN ? nil : value
  case .float64(let value):
    let converted = Float(value)
    if value.isNaN {
      return nil
    }
    if converted == Float.infinity && value == Double.infinity {
      return converted
    }
    if converted == -Float.infinity && value == -Double.infinity {
      return converted
    }
    guard
      Double(converted) == value
        && compatibleZeroSign(Double(converted)) == compatibleZeroSign(value)
    else {
      return nil
    }
    return converted
  case .string, .decimal:
    guard let literal = try compatibleScalarToDecimalLiteral(value) else {
      return nil
    }
    return compatibleDecimalLiteralToFloat(literal)
  }
}

private func compatibleScalarToDouble(_ value: CompatibleScalarValue) throws -> Double? {
  switch value {
  case .bool(let value):
    return value ? 1 : 0
  case .signed(let value):
    let converted = Double(value)
    guard converted.isFinite, Int64(exactly: converted) == value else {
      return nil
    }
    return converted
  case .unsigned(let value):
    let converted = Double(value)
    guard converted.isFinite, UInt64(exactly: converted) == value else {
      return nil
    }
    return converted
  case .float16(let value):
    let converted = Float(value)
    return converted.isNaN ? nil : Double(converted)
  case .bfloat16(let value):
    let converted = compatibleFloat(from: value)
    return converted.isNaN ? nil : Double(converted)
  case .float32(let value):
    return value.isNaN ? nil : Double(value)
  case .float64(let value):
    return value.isNaN ? nil : value
  case .string, .decimal:
    guard let literal = try compatibleScalarToDecimalLiteral(value) else {
      return nil
    }
    return compatibleDecimalLiteralToDouble(literal)
  }
}

private func compatibleScalarToDecimal(_ value: CompatibleScalarValue) throws -> Decimal? {
  switch value {
  case .bool(let value):
    return Decimal(value ? 1 : 0)
  case .signed(let value):
    return Decimal(value)
  case .unsigned(let value):
    return Decimal(value)
  case .decimal(let value):
    return value
  default:
    guard let literal = try compatibleScalarToDecimalLiteral(value) else {
      return nil
    }
    return compatibleDecimal(from: literal)
  }
}

private func compatibleScalarToDecimalLiteral(_ value: CompatibleScalarValue) throws
  -> DecimalLiteral? {
  switch value {
  case .bool(let value):
    return DecimalLiteral(negative: false, digits: value ? "1" : "0", scale: 0, negativeZero: false)
  case .signed(let value):
    return DecimalLiteral(
      negative: value < 0,
      digits: String(value.magnitude),
      scale: 0,
      negativeZero: false
    )
  case .unsigned(let value):
    return DecimalLiteral(negative: false, digits: String(value), scale: 0, negativeZero: false)
  case .float16(let value):
    return compatibleParseCanonicalDecimal(compatibleFiniteFloatText(value))
  case .bfloat16(let value):
    return compatibleParseCanonicalDecimal(compatibleFiniteBFloat16Text(value))
  case .float32(let value):
    return compatibleParseCanonicalDecimal(compatibleFiniteFloatText(value))
  case .float64(let value):
    return compatibleParseCanonicalDecimal(compatibleFiniteFloatText(value))
  case .decimal(let value):
    return compatibleParseCanonicalDecimal(compatibleDecimalText(value))
  case .string(let value):
    return compatibleParseNumericLiteral(value)
  }
}

private func compatibleParseNumericLiteral(_ text: String) -> DecimalLiteral? {
  guard !text.isEmpty, text.utf8.count <= maxCompatibleNumericTextLength else {
    return nil
  }
  let utf8 = text.utf8
  var index = utf8.startIndex
  let end = utf8.endIndex
  func currentByte() -> UInt8? {
    index == end ? nil : utf8[index]
  }
  func advance() {
    utf8.formIndex(after: &index)
  }
  func isDigit(_ byte: UInt8) -> Bool {
    byte >= 48 && byte <= 57
  }

  guard currentByte() != 43 else {
    return nil
  }

  var negative = false
  if currentByte() == 45 {
    negative = true
    advance()
    guard index != end else {
      return nil
    }
  }

  var seenNonZero = false
  var significantDigits = 0
  func countSignificantDigit(_ digit: UInt8) -> Bool {
    if digit != 48 || seenNonZero {
      seenNonZero = true
      significantDigits += 1
    }
    return significantDigits <= maxCompatibleDecimalDigits
  }

  var integerDigitCount = 0
  var firstIntegerDigit: UInt8 = 0
  while let byte = currentByte(), isDigit(byte) {
    if integerDigitCount == 0 {
      firstIntegerDigit = byte
    }
    integerDigitCount += 1
    guard countSignificantDigit(byte) else {
      return nil
    }
    advance()
  }
  guard integerDigitCount > 0,
    integerDigitCount == 1 || firstIntegerDigit != 48
  else {
    return nil
  }

  var fractionalDigitCount = 0
  if currentByte() == 46 {
    advance()
    while let byte = currentByte(), isDigit(byte) {
      fractionalDigitCount += 1
      guard countSignificantDigit(byte) else {
        return nil
      }
      advance()
    }
    guard fractionalDigitCount > 0 else {
      return nil
    }
  }

  var exponent = 0
  if let byte = currentByte(), byte == 101 || byte == 69 {
    advance()
    var exponentNegative = false
    if currentByte() == 45 {
      exponentNegative = true
      advance()
    }
    var exponentDigitCount = 0
    var firstExponentDigit: UInt8 = 0
    while let byte = currentByte(), isDigit(byte) {
      if exponentDigitCount == 0 {
        firstExponentDigit = byte
      }
      exponentDigitCount += 1
      exponent = exponent * 10 + Int(byte - 48)
      guard exponent <= maxCompatibleDecimalDigits else {
        return nil
      }
      advance()
    }
    guard exponentDigitCount > 0,
      exponentDigitCount == 1 || firstExponentDigit != 48
    else {
      return nil
    }
    if exponentNegative {
      exponent = -exponent
    }
  }
  guard index == end else {
    return nil
  }

  let adjustedScale = fractionalDigitCount.subtractingReportingOverflow(exponent)
  guard !adjustedScale.overflow else {
    return nil
  }
  var scale = adjustedScale.partialValue
  guard significantDigits <= maxCompatibleDecimalDigits,
    compatibleDecimalShape(significantDigits: significantDigits, scale: scale)
  else {
    return nil
  }
  if significantDigits == 0 {
    return DecimalLiteral(negative: negative, digits: "0", scale: 0, negativeZero: negative)
  }

  var digitBytes: [UInt8] = []
  digitBytes.reserveCapacity(integerDigitCount + fractionalDigitCount)
  var buildIndex = utf8.startIndex
  if utf8[buildIndex] == 45 {
    utf8.formIndex(after: &buildIndex)
  }
  while buildIndex != end, isDigit(utf8[buildIndex]) {
    digitBytes.append(utf8[buildIndex])
    utf8.formIndex(after: &buildIndex)
  }
  if buildIndex != end, utf8[buildIndex] == 46 {
    utf8.formIndex(after: &buildIndex)
    while buildIndex != end, isDigit(utf8[buildIndex]) {
      digitBytes.append(utf8[buildIndex])
      utf8.formIndex(after: &buildIndex)
    }
  }
  var firstNonZero = 0
  while firstNonZero < digitBytes.count, digitBytes[firstNonZero] == 48 {
    firstNonZero += 1
  }
  if firstNonZero > 0 {
    digitBytes.removeFirst(firstNonZero)
  }

  if scale < 0 {
    let extra = 0.subtractingReportingOverflow(scale)
    guard !extra.overflow,
      extra.partialValue <= maxCompatibleDecimalDigits,
      digitBytes.count + extra.partialValue <= maxCompatibleDecimalDigits
    else {
      return nil
    }
    digitBytes.append(contentsOf: repeatElement(48, count: extra.partialValue))
    scale = 0
  }
  while scale > 0, digitBytes.last == 48 {
    digitBytes.removeLast()
    scale -= 1
  }
  guard scale <= maxCompatibleDecimalDigits,
    digitBytes.count <= maxCompatibleDecimalDigits
  else {
    return nil
  }
  guard let normalized = String(bytes: digitBytes, encoding: .utf8) else {
    return nil
  }
  let negativeZero = negative && normalized == "0"
  return DecimalLiteral(
    negative: negative, digits: normalized, scale: scale, negativeZero: negativeZero)
}

private func compatibleDecimalShape(significantDigits: Int, scale: Int) -> Bool {
  if scale > maxCompatibleDecimalDigits {
    return false
  }
  return scale >= 0 || significantDigits + (-scale) <= maxCompatibleDecimalDigits
}

private func compatibleParseCanonicalDecimal(_ text: String?) -> DecimalLiteral? {
  guard let text else {
    return nil
  }
  return compatibleParseNumericLiteral(text)
}

private func compatibleNormalizeDigits(_ digits: String) -> String {
  let trimmed = digits.drop { $0 == "0" }
  return trimmed.isEmpty ? "0" : String(trimmed)
}

private func compatibleParseSignedDigits(_ literal: DecimalLiteral) -> Int64? {
  guard let magnitude = UInt64(literal.digits) else {
    return nil
  }
  if literal.negative && !literal.isZero {
    guard magnitude <= UInt64(Int64.max) + 1 else {
      return nil
    }
    if magnitude == UInt64(Int64.max) + 1 {
      return Int64.min
    }
    return -Int64(magnitude)
  }
  guard magnitude <= UInt64(Int64.max) else {
    return nil
  }
  return Int64(magnitude)
}

private func compatibleDecimalLiteralToFloat(_ literal: DecimalLiteral) -> Float? {
  if literal.isZero {
    return literal.negativeZero ? -Float(0) : Float(0)
  }
  guard let text = literal.canonicalDecimalText else {
    return nil
  }
  guard let value = Float(text), value.isFinite else {
    return nil
  }
  return compatibleFiniteFloatText(value)
    == compatibleFloatCanonicalText(literal, forceFraction: true) ? value : nil
}

private func compatibleDecimalLiteralToDouble(_ literal: DecimalLiteral) -> Double? {
  if literal.isZero {
    return literal.negativeZero ? -Double(0) : Double(0)
  }
  guard let text = literal.canonicalDecimalText else {
    return nil
  }
  guard let value = Double(text), value.isFinite else {
    return nil
  }
  return compatibleFiniteFloatText(value)
    == compatibleFloatCanonicalText(literal, forceFraction: true) ? value : nil
}

private func compatibleDecimal(from literal: DecimalLiteral) -> Decimal? {
  guard let text = literal.canonicalDecimalText else {
    return nil
  }
  guard let decimal = Decimal(string: text, locale: Locale(identifier: "en_US_POSIX")) else {
    return nil
  }
  guard let canonical = compatibleDecimalText(decimal), canonical == text else {
    return nil
  }
  return decimal
}

private func compatibleDecimalText(_ value: Decimal) -> String? {
  let unscaled = value.foryUnscaledString
  let negative = unscaled.first == "-"
  let digits = negative ? String(unscaled.dropFirst()) : unscaled
  return compatibleFormatDecimalText(
    negative: negative, digits: digits, scale: Int(value.foryScale))
}

private func compatibleFormatDecimalText(negative: Bool, digits: String, scale: Int) -> String? {
  var normalizedDigits = compatibleNormalizeDigits(digits)
  if normalizedDigits == "0" {
    return "0"
  }
  var adjustedScale = scale
  if adjustedScale < 0 {
    let extra = 0.subtractingReportingOverflow(adjustedScale)
    guard !extra.overflow,
      extra.partialValue <= maxCompatibleDecimalDigits,
      normalizedDigits.count + extra.partialValue <= maxCompatibleDecimalDigits
    else {
      return nil
    }
    normalizedDigits += String(repeating: "0", count: extra.partialValue)
    adjustedScale = 0
  }
  guard adjustedScale <= maxCompatibleDecimalDigits,
    normalizedDigits.count <= maxCompatibleDecimalDigits
  else {
    return nil
  }
  let sign = negative ? "-" : ""
  if adjustedScale == 0 {
    return sign + normalizedDigits
  }
  if adjustedScale >= normalizedDigits.count {
    let zeros = String(repeating: "0", count: adjustedScale - normalizedDigits.count)
    var fraction = zeros + normalizedDigits
    while fraction.last == "0" {
      fraction.removeLast()
    }
    return "\(sign)0.\(fraction)"
  }
  let split = normalizedDigits.index(normalizedDigits.endIndex, offsetBy: -adjustedScale)
  let integer = String(normalizedDigits[..<split])
  var fraction = String(normalizedDigits[split...])
  while fraction.last == "0" {
    fraction.removeLast()
  }
  return fraction.isEmpty ? sign + integer : "\(sign)\(integer).\(fraction)"
}

private func compatibleFloatCanonicalText(_ literal: DecimalLiteral, forceFraction: Bool) -> String? {
  if literal.isZero {
    return literal.negativeZero ? "-0.0" : "0.0"
  }
  guard var text = literal.canonicalDecimalText else {
    return nil
  }
  if forceFraction && !text.contains(".") {
    text += ".0"
  }
  return text
}

private func compatibleFiniteFloatText(_ value: Float16) -> String? {
  compatibleFiniteBinaryFloatText(
    BinaryFloatLayout(
      sign: (value.bitPattern & 0x8000) != 0,
      exponentBits: UInt64((value.bitPattern >> 10) & 0x1f),
      significandBits: UInt64(value.bitPattern & 0x03ff),
      exponentBitCount: 5,
      significandBitCount: 10,
      exponentBias: 15
    )
  )
}

private func compatibleFiniteBFloat16Text(_ value: BFloat16) -> String? {
  compatibleFiniteBinaryFloatText(
    BinaryFloatLayout(
      sign: (value.rawValue & 0x8000) != 0,
      exponentBits: UInt64((value.rawValue >> 7) & 0xff),
      significandBits: UInt64(value.rawValue & 0x007f),
      exponentBitCount: 8,
      significandBitCount: 7,
      exponentBias: 127
    )
  )
}

private func compatibleFiniteFloatText(_ value: Float) -> String? {
  compatibleFiniteBinaryFloatText(
    BinaryFloatLayout(
      sign: (value.bitPattern & 0x8000_0000) != 0,
      exponentBits: UInt64((value.bitPattern >> 23) & 0xff),
      significandBits: UInt64(value.bitPattern & 0x007f_ffff),
      exponentBitCount: 8,
      significandBitCount: 23,
      exponentBias: 127
    )
  )
}

private func compatibleFiniteFloatText(_ value: Double) -> String? {
  compatibleFiniteBinaryFloatText(
    BinaryFloatLayout(
      sign: (value.bitPattern & 0x8000_0000_0000_0000) != 0,
      exponentBits: (value.bitPattern >> 52) & 0x7ff,
      significandBits: value.bitPattern & 0x000f_ffff_ffff_ffff,
      exponentBitCount: 11,
      significandBitCount: 52,
      exponentBias: 1023
    )
  )
}

private func compatibleFiniteBinaryFloatText(_ layout: BinaryFloatLayout) -> String? {
  let maxExponent = (UInt64(1) << layout.exponentBitCount) - 1
  guard layout.exponentBits != maxExponent else {
    return nil
  }
  guard layout.exponentBits != 0 || layout.significandBits != 0 else {
    return layout.sign ? "-0.0" : "0.0"
  }
  let significand: UInt64
  let exponent2: Int
  if layout.exponentBits == 0 {
    significand = layout.significandBits
    exponent2 = 1 - layout.exponentBias - layout.significandBitCount
  } else {
    significand = (UInt64(1) << layout.significandBitCount) | layout.significandBits
    exponent2 = Int(layout.exponentBits) - layout.exponentBias - layout.significandBitCount
  }
  var digits = compatibleDecimalDigits(significand)
  var scale = 0
  if exponent2 >= 0 {
    for _ in 0..<exponent2 {
      compatibleMultiplyDecimalDigits(&digits, by: 2)
    }
  } else {
    scale = -exponent2
    guard scale <= maxCompatibleDecimalDigits else {
      return nil
    }
    for _ in 0..<scale {
      compatibleMultiplyDecimalDigits(&digits, by: 5)
    }
  }
  guard digits.count <= maxCompatibleDecimalDigits else {
    return nil
  }
  return compatibleFormatFloatText(sign: layout.sign, digits: digits, scale: scale)
}

private func compatibleFormatFloatText(sign: Bool, digits: [UInt8], scale: Int) -> String {
  let digitText = compatibleDecimalDigitsString(digits)
  if scale == 0 {
    return "\(sign ? "-" : "")\(digitText).0"
  }
  if scale >= digitText.count {
    let zeros = String(repeating: "0", count: scale - digitText.count)
    var fraction = zeros + digitText
    while fraction.last == "0", fraction.count > 1 {
      fraction.removeLast()
    }
    return "\(sign ? "-" : "")0.\(fraction)"
  }
  let split = digitText.index(digitText.endIndex, offsetBy: -scale)
  let integer = String(digitText[..<split])
  var fraction = String(digitText[split...])
  while fraction.last == "0", fraction.count > 1 {
    fraction.removeLast()
  }
  if fraction.isEmpty {
    fraction = "0"
  }
  return "\(sign ? "-" : "")\(integer).\(fraction)"
}

private func compatibleDecimalDigits(_ value: UInt64) -> [UInt8] {
  guard value != 0 else {
    return [0]
  }
  var remaining = value
  var digits: [UInt8] = []
  while remaining > 0 {
    digits.append(UInt8(remaining % 10))
    remaining /= 10
  }
  return digits
}

private func compatibleMultiplyDecimalDigits(_ digits: inout [UInt8], by multiplier: UInt8) {
  var carry = 0
  for index in digits.indices {
    let value = Int(digits[index]) * Int(multiplier) + carry
    digits[index] = UInt8(value % 10)
    carry = value / 10
  }
  while carry > 0 {
    digits.append(UInt8(carry % 10))
    carry /= 10
  }
}

private func compatibleDecimalDigitsString(_ digits: [UInt8]) -> String {
  let scalars = digits.reversed().map { Character(String(UnicodeScalar(UInt32(48 + $0))!)) }
  return String(scalars)
}

private func compatibleBFloat16(from value: Float) -> BFloat16 {
  BFloat16(rawValue: UInt16(value.bitPattern >> 16))
}

private func compatibleFloat(from value: BFloat16) -> Float {
  Float(bitPattern: UInt32(value.rawValue) << 16)
}

private func compatibleZeroSign(_ value: Float) -> Bool {
  value == 0 && (value.bitPattern & 0x8000_0000) != 0
}

private func compatibleZeroSign(_ value: Double) -> Bool {
  value == 0 && (value.bitPattern & 0x8000_0000_0000_0000) != 0
}

@inline(never)
private func compatibleScalarError(
  fieldName: String,
  remoteTypeID: TypeId,
  localTypeID: TypeId,
  reason: String
) -> ForyError {
  ForyError.invalidData(
    "compatible scalar field '\(fieldName)' cannot convert remote type \(remoteTypeID.rawValue) "
      + "to local type \(localTypeID.rawValue): \(reason)"
  )
}
