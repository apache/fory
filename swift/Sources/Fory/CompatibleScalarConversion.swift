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

private enum CompatibleScalarKind {
  case bool
  case string
  case signedInteger
  case unsignedInteger
  case floatingPoint
  case decimal

  var isNumeric: Bool {
    switch self {
    case .signedInteger, .unsignedInteger, .floatingPoint, .decimal:
      return true
    case .bool, .string:
      return false
    }
  }
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
public func foryReadCompatibleScalarField<T>(
  _ context: ReadContext,
  remoteFieldType: TypeMeta.FieldType,
  localTypeID: UInt32,
  fieldName: String,
  directRead: () throws -> T
) throws -> T {
  guard let remoteTypeID = TypeId(rawValue: remoteFieldType.typeID),
    let localTypeID = TypeId(rawValue: localTypeID)
  else {
    return try directRead()
  }
  guard compatibleScalarKind(localTypeID) != nil else {
    return try directRead()
  }
  guard compatibleScalarKind(remoteTypeID) != nil else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "remote field type is not a compatible scalar"
    )
  }
  guard compatibleScalarPair(remoteTypeID: remoteTypeID, localTypeID: localTypeID) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "schema pair is outside the scalar conversion matrix"
    )
  }
  guard !remoteFieldType.trackRef else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "trackingRef scalar conversion is not supported"
    )
  }
  guard
    let remoteValue = try readCompatibleRemoteScalar(
      context, remoteTypeID: remoteTypeID, fieldType: remoteFieldType)
  else {
    return compatibleScalarDefault(T.self, localTypeID: localTypeID)
  }
  guard
    let converted = try convertCompatibleScalar(remoteValue, to: T.self, localTypeID: localTypeID)
  else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

@inline(never)
public func foryReadCompatibleOptionalScalarField<T>(
  _ context: ReadContext,
  remoteFieldType: TypeMeta.FieldType,
  localTypeID: UInt32,
  fieldName: String,
  directRead: () throws -> T?
) throws -> T? {
  guard let remoteTypeID = TypeId(rawValue: remoteFieldType.typeID),
    let localTypeID = TypeId(rawValue: localTypeID)
  else {
    return try directRead()
  }
  guard compatibleScalarKind(localTypeID) != nil else {
    return try directRead()
  }
  guard compatibleScalarKind(remoteTypeID) != nil else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "remote field type is not a compatible scalar"
    )
  }
  guard compatibleScalarPair(remoteTypeID: remoteTypeID, localTypeID: localTypeID) else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "schema pair is outside the scalar conversion matrix"
    )
  }
  guard !remoteFieldType.trackRef else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "trackingRef scalar conversion is not supported"
    )
  }
  guard
    let remoteValue = try readCompatibleRemoteScalar(
      context, remoteTypeID: remoteTypeID, fieldType: remoteFieldType)
  else {
    return nil
  }
  guard
    let converted = try convertCompatibleScalar(remoteValue, to: T.self, localTypeID: localTypeID)
  else {
    throw compatibleScalarError(
      fieldName: fieldName,
      remoteTypeID: remoteTypeID,
      localTypeID: localTypeID,
      reason: "value is not lossless for the local field type"
    )
  }
  return converted
}

private func compatibleScalarKind(_ typeID: TypeId) -> CompatibleScalarKind? {
  switch typeID {
  case .bool:
    return .bool
  case .string:
    return .string
  case .int8, .int16, .int32, .varint32, .int64, .varint64, .taggedInt64:
    return .signedInteger
  case .uint8, .uint16, .uint32, .varUInt32, .uint64, .varUInt64, .taggedUInt64:
    return .unsignedInteger
  case .float16, .bfloat16, .float32, .float64:
    return .floatingPoint
  case .decimal:
    return .decimal
  default:
    return nil
  }
}

private func compatibleScalarPair(remoteTypeID: TypeId, localTypeID: TypeId) -> Bool {
  guard let remote = compatibleScalarKind(remoteTypeID),
    let local = compatibleScalarKind(localTypeID)
  else {
    return false
  }
  if remoteTypeID == localTypeID {
    return true
  }
  if remote == .bool {
    return local == .string || local.isNumeric
  }
  if local == .bool {
    return remote == .string || remote.isNumeric
  }
  if remote == .string {
    return local.isNumeric
  }
  if local == .string {
    return remote.isNumeric
  }
  return remote.isNumeric && local.isNumeric
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

private func convertCompatibleScalar<T>(
  _ value: CompatibleScalarValue,
  to _: T.Type,
  localTypeID: TypeId
) throws -> T? {
  switch localTypeID {
  case .bool:
    guard T.self == Bool.self, let converted = try compatibleScalarToBool(value) else {
      return nil
    }
    return converted as? T
  case .string:
    guard T.self == String.self, let converted = try compatibleScalarToString(value) else {
      return nil
    }
    return converted as? T
  case .int8:
    return compatibleCastSigned(
      try compatibleScalarToInt64(value), min: Int64(Int8.min), max: Int64(Int8.max), to: T.self)
  case .int16:
    return compatibleCastSigned(
      try compatibleScalarToInt64(value), min: Int64(Int16.min), max: Int64(Int16.max), to: T.self)
  case .int32, .varint32:
    return compatibleCastSigned(
      try compatibleScalarToInt64(value), min: Int64(Int32.min), max: Int64(Int32.max), to: T.self)
  case .int64, .varint64, .taggedInt64:
    return compatibleCastSigned(
      try compatibleScalarToInt64(value), min: Int64.min, max: Int64.max, to: T.self)
  case .uint8:
    return compatibleCastUnsigned(
      try compatibleScalarToUInt64(value), max: UInt64(UInt8.max), to: T.self)
  case .uint16:
    return compatibleCastUnsigned(
      try compatibleScalarToUInt64(value), max: UInt64(UInt16.max), to: T.self)
  case .uint32, .varUInt32:
    return compatibleCastUnsigned(
      try compatibleScalarToUInt64(value), max: UInt64(UInt32.max), to: T.self)
  case .uint64, .varUInt64, .taggedUInt64:
    return compatibleCastUnsigned(try compatibleScalarToUInt64(value), max: UInt64.max, to: T.self)
  case .float16:
    guard T.self == Float16.self, let converted = try compatibleScalarToFloat16(value) else {
      return nil
    }
    return converted as? T
  case .bfloat16:
    guard T.self == BFloat16.self, let converted = try compatibleScalarToBFloat16(value) else {
      return nil
    }
    return converted as? T
  case .float32:
    guard T.self == Float.self, let converted = try compatibleScalarToFloat(value) else {
      return nil
    }
    return converted as? T
  case .float64:
    guard T.self == Double.self, let converted = try compatibleScalarToDouble(value) else {
      return nil
    }
    return converted as? T
  case .decimal:
    guard T.self == Decimal.self, let converted = try compatibleScalarToDecimal(value) else {
      return nil
    }
    return converted as? T
  default:
    return nil
  }
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
  -> DecimalLiteral?
{
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

private func compatibleCastSigned<T>(_ value: Int64?, min: Int64, max: Int64, to _: T.Type) -> T? {
  guard let value, value >= min, value <= max else {
    return nil
  }
  switch T.self {
  case is Int8.Type:
    return Int8(value) as? T
  case is Int16.Type:
    return Int16(value) as? T
  case is Int32.Type:
    return Int32(value) as? T
  case is Int64.Type:
    return value as? T
  case is Int.Type:
    guard let converted = Int(exactly: value) else {
      return nil
    }
    return converted as? T
  default:
    return nil
  }
}

private func compatibleCastUnsigned<T>(_ value: UInt64?, max: UInt64, to _: T.Type) -> T? {
  guard let value, value <= max else {
    return nil
  }
  switch T.self {
  case is UInt8.Type:
    return UInt8(value) as? T
  case is UInt16.Type:
    return UInt16(value) as? T
  case is UInt32.Type:
    return UInt32(value) as? T
  case is UInt64.Type:
    return value as? T
  case is UInt.Type:
    guard let converted = UInt(exactly: value) else {
      return nil
    }
    return converted as? T
  default:
    return nil
  }
}

private func compatibleScalarDefault<T>(_: T.Type, localTypeID: TypeId) -> T {
  guard let value = compatibleScalarDefaultValue(T.self, localTypeID: localTypeID) else {
    preconditionFailure("unsupported compatible scalar default")
  }
  return value
}

private func compatibleScalarDefaultValue<T>(_: T.Type, localTypeID: TypeId) -> T? {
  switch localTypeID {
  case .bool:
    return false as? T
  case .string:
    return "" as? T
  case .int8:
    return Int8(0) as? T
  case .int16:
    return Int16(0) as? T
  case .int32, .varint32:
    return Int32(0) as? T
  case .int64, .varint64, .taggedInt64:
    if T.self == Int.self {
      return Int(0) as? T
    }
    return Int64(0) as? T
  case .uint8:
    return UInt8(0) as? T
  case .uint16:
    return UInt16(0) as? T
  case .uint32, .varUInt32:
    return UInt32(0) as? T
  case .uint64, .varUInt64, .taggedUInt64:
    if T.self == UInt.self {
      return UInt(0) as? T
    }
    return UInt64(0) as? T
  case .float16:
    return Float16(0) as? T
  case .bfloat16:
    return BFloat16() as? T
  case .float32:
    return Float(0) as? T
  case .float64:
    return Double(0) as? T
  case .decimal:
    return Decimal.zero as? T
  default:
    return nil
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
  let normalized = String(decoding: digitBytes, as: UTF8.self)
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

private func compatibleFloatCanonicalText(_ literal: DecimalLiteral, forceFraction: Bool) -> String?
{
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
