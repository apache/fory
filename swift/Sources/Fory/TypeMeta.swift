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

private let smallNumFieldsThreshold = 0b1_1111
private let registerByNameFlag: UInt8 = 0b10_0000
private let compatibleTypeMetaFlag: UInt8 = 0b0100_0000
private let structTypeMetaFlag: UInt8 = 0b1000_0000
private let fieldNameSizeThreshold = 0b1111
private let bigNameThreshold = 0b11_1111

private let typeMetaCompressedFlag: UInt64 = 1 << 8
private let typeMetaReservedFlags: UInt64 = 0b111 << 9
private let typeMetaSizeMask: UInt64 = 0xFF
private let typeMetaNumHashBits: UInt64 = 52
private let typeMetaHashSeed: UInt64 = 47
private let noUserTypeID: UInt32 = UInt32.max

public let namespaceMetaStringEncodings: [MetaStringEncoding] = [
  .utf8,
  .allToLowerSpecial,
  .lowerUpperDigitSpecial,
]

public let typeNameMetaStringEncodings: [MetaStringEncoding] = [
  .utf8,
  .allToLowerSpecial,
  .lowerUpperDigitSpecial,
  .firstToLowerSpecial,
]

public let fieldNameMetaStringEncodings: [MetaStringEncoding] = [
  .utf8,
  .allToLowerSpecial,
  .lowerUpperDigitSpecial,
]

public final class TypeMeta: Equatable, @unchecked Sendable {

  public struct FieldType: Equatable, Sendable {
    public var typeID: UInt32
    public var nullable: Bool
    public var trackRef: Bool
    public var generics: [FieldType]

    public init(
      typeID: UInt32,
      nullable: Bool,
      trackRef: Bool = false,
      generics: [FieldType] = []
    ) {
      self.typeID = typeID
      self.nullable = nullable
      self.trackRef = trackRef
      self.generics = generics
    }

    fileprivate func write(
      _ buffer: ByteBuffer,
      writeFlags: Bool,
      nullableOverride: Bool? = nil
    ) {
      if writeFlags {
        var header = typeID << 2
        if nullableOverride ?? nullable {
          header |= 0b10
        }
        if trackRef {
          header |= 0b1
        }
        buffer.writeVarUInt32(header)
      } else {
        buffer.writeUInt8(UInt8(truncatingIfNeeded: typeID))
      }

      if typeID == TypeId.list.rawValue || typeID == TypeId.set.rawValue {
        let element = generics.first ?? FieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        element.write(buffer, writeFlags: true, nullableOverride: element.nullable)
      } else if typeID == TypeId.map.rawValue {
        let key = generics.first ?? FieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        let value =
          generics.dropFirst().first ?? FieldType(typeID: TypeId.unknown.rawValue, nullable: true)
        key.write(buffer, writeFlags: true, nullableOverride: key.nullable)
        value.write(buffer, writeFlags: true, nullableOverride: value.nullable)
      }
    }

    fileprivate static func read(
      _ buffer: ByteBuffer,
      readFlags: Bool,
      nullable: Bool? = nil,
      trackRef: Bool? = nil
    ) throws -> FieldType {
      let header: UInt32
      if readFlags {
        header = try buffer.readVarUInt32()
      } else {
        header = UInt32(try buffer.readUInt8())
      }

      let typeID: UInt32
      let resolvedNullable: Bool
      let resolvedTrackRef: Bool

      if readFlags {
        typeID = header >> 2
        resolvedNullable = (header & 0b10) != 0
        resolvedTrackRef = (header & 0b1) != 0
      } else {
        typeID = header
        resolvedNullable = nullable ?? false
        resolvedTrackRef = trackRef ?? false
      }

      if typeID == TypeId.list.rawValue || typeID == TypeId.set.rawValue {
        let element = try read(buffer, readFlags: true)
        return FieldType(
          typeID: typeID,
          nullable: resolvedNullable,
          trackRef: resolvedTrackRef,
          generics: [element]
        )
      }
      if typeID == TypeId.map.rawValue {
        let key = try read(buffer, readFlags: true)
        let value = try read(buffer, readFlags: true)
        return FieldType(
          typeID: typeID,
          nullable: resolvedNullable,
          trackRef: resolvedTrackRef,
          generics: [key, value]
        )
      }

      return FieldType(
        typeID: typeID,
        nullable: resolvedNullable,
        trackRef: resolvedTrackRef,
        generics: []
      )
    }
  }

  public struct FieldInfo: Equatable, Sendable {
    public var fieldID: Int16?
    public var fieldName: String
    public var fieldType: FieldType

    public init(fieldID: Int16?, fieldName: String, fieldType: FieldType) {
      self.fieldID = fieldID
      self.fieldName = fieldName
      self.fieldType = fieldType
    }

    fileprivate func write(_ buffer: ByteBuffer) throws {
      var header: UInt8 = 0
      if fieldType.trackRef {
        header |= 0b1
      }
      if fieldType.nullable {
        header |= 0b10
      }

      if let fieldID {
        if fieldID < 0 {
          throw ForyError.encodingError("negative field id is invalid")
        }
        let size = Int(fieldID)
        header |= UInt8(0b11 << 6)
        if size >= fieldNameSizeThreshold {
          header |= 0b0011_1100
          buffer.writeUInt8(header)
          buffer.writeVarUInt32(UInt32(size - fieldNameSizeThreshold))
        } else {
          header |= UInt8(size << 2)
          buffer.writeUInt8(header)
        }
        fieldType.write(buffer, writeFlags: false)
        return
      }

      let snakeName = lowerCamelToLowerUnderscore(fieldName)
      let encoded = try MetaStringEncoder.fieldName.encode(
        snakeName, allowedEncodings: fieldNameMetaStringEncodings)
      guard let encodingIndex = fieldNameMetaStringEncodings.firstIndex(of: encoded.encoding) else {
        throw ForyError.encodingError("unsupported field name encoding")
      }

      let size = encoded.bytes.count - 1
      header |= UInt8(encodingIndex << 6)
      if size >= fieldNameSizeThreshold {
        header |= 0b0011_1100
        buffer.writeUInt8(header)
        buffer.writeVarUInt32(UInt32(size - fieldNameSizeThreshold))
      } else {
        header |= UInt8(size << 2)
        buffer.writeUInt8(header)
      }

      fieldType.write(buffer, writeFlags: false)
      buffer.writeBytes(encoded.bytes)
    }

    fileprivate static func read(_ buffer: ByteBuffer) throws -> FieldInfo {
      let header = try buffer.readUInt8()
      let encodingFlags = Int((header >> 6) & 0b11)
      var size = Int((header >> 2) & 0b1111)
      if size == fieldNameSizeThreshold {
        size += Int(try buffer.readVarUInt32())
      }
      size += 1

      let nullable = (header & 0b10) != 0
      let trackRef = (header & 0b1) != 0
      let fieldType = try FieldType.read(
        buffer,
        readFlags: false,
        nullable: nullable,
        trackRef: trackRef
      )

      if encodingFlags == 3 {
        let fieldID = Int16(size - 1)
        return FieldInfo(
          fieldID: fieldID,
          fieldName: "$tag\(fieldID)",
          fieldType: fieldType
        )
      }

      guard encodingFlags < fieldNameMetaStringEncodings.count else {
        throw ForyError.invalidData("invalid field name encoding id")
      }
      let nameBytes = try buffer.readBytes(count: size)
      let name = try MetaStringDecoder.fieldName
        .decode(bytes: nameBytes, encoding: fieldNameMetaStringEncodings[encodingFlags])
        .value

      return FieldInfo(fieldID: nil, fieldName: name, fieldType: fieldType)
    }
  }

  public let typeID: UInt32?
  public let userTypeID: UInt32?
  public let namespace: MetaString
  public let typeName: MetaString
  public let registerByName: Bool
  public let fields: [FieldInfo]
  public let compressed: Bool
  public let headerHash: UInt64

  public init(
    typeID: UInt32?,
    userTypeID: UInt32?,
    namespace: MetaString,
    typeName: MetaString,
    registerByName: Bool,
    fields: [FieldInfo],
    compressed: Bool = false,
    headerHash: UInt64 = 0
  ) throws {
    guard typeID != nil else {
      throw ForyError.encodingError("type id is required in type metadata")
    }
    if registerByName {
      if typeName.value.isEmpty {
        throw ForyError.encodingError("type name is required in register-by-name mode")
      }
    } else {
      guard let userTypeID, userTypeID != noUserTypeID else {
        throw ForyError.encodingError("user type id is required in register-by-id mode")
      }
    }

    self.typeID = typeID
    self.userTypeID = userTypeID
    self.namespace = namespace
    self.typeName = typeName
    self.registerByName = registerByName
    self.fields = fields
    self.compressed = compressed
    self.headerHash = headerHash
  }

  public static func == (lhs: TypeMeta, rhs: TypeMeta) -> Bool {
    lhs.typeID == rhs.typeID && lhs.userTypeID == rhs.userTypeID && lhs.namespace == rhs.namespace
      && lhs.typeName == rhs.typeName && lhs.registerByName == rhs.registerByName
      && lhs.fields == rhs.fields && lhs.compressed == rhs.compressed
      && lhs.headerHash == rhs.headerHash
  }

  public func encode() throws -> [UInt8] {
    if compressed {
      throw ForyError.encodingError("compressed TypeMeta is not supported yet")
    }

    let body = try encodeBody()
    var headerLowBits = UInt64(min(body.count, Int(typeMetaSizeMask)))
    if compressed {
      headerLowBits |= typeMetaCompressedFlag
    }
    let header = Self.typeMetaHeaderHash(body, headerLowBits: headerLowBits) | headerLowBits

    let buffer = ByteBuffer(capacity: body.count + 16)
    buffer.writeUInt64(header)
    if body.count >= Int(typeMetaSizeMask) {
      buffer.writeVarUInt32(UInt32(body.count - Int(typeMetaSizeMask)))
    }
    buffer.writeBytes(body)
    return Array(buffer.storage.prefix(buffer.count))
  }

  public static func decode(_ bytes: [UInt8]) throws -> TypeMeta {
    try decode(ByteBuffer(bytes: bytes))
  }

  public static func decode(_ buffer: ByteBuffer) throws -> TypeMeta {
    let header = try buffer.readUInt64()
    if (header & typeMetaReservedFlags) != 0 {
      throw ForyError.invalidData("invalid TypeMeta global header")
    }
    let compressed = (header & typeMetaCompressedFlag) != 0

    var metaSize = Int(header & typeMetaSizeMask)
    if metaSize == Int(typeMetaSizeMask) {
      metaSize += Int(try buffer.readVarUInt32())
    }

    let encodedBody = try buffer.readBytes(count: metaSize)
    if compressed {
      throw ForyError.encodingError("compressed TypeMeta is not supported yet")
    }

    let bodyReader = ByteBuffer(bytes: encodedBody)
    let metaHeader = try bodyReader.readUInt8()

    let isStruct = (metaHeader & structTypeMetaFlag) != 0
    var numFields = 0
    let registerByName: Bool
    let typeID: UInt32
    let userTypeID: UInt32?
    let namespace: MetaString
    let typeName: MetaString

    if isStruct {
      registerByName = (metaHeader & registerByNameFlag) != 0
      let compatible = (metaHeader & compatibleTypeMetaFlag) != 0
      numFields = Int(metaHeader & UInt8(smallNumFieldsThreshold))
      if numFields == smallNumFieldsThreshold {
        numFields += Int(try bodyReader.readVarUInt32())
      }
      if registerByName {
        typeID = compatible ? TypeId.namedCompatibleStruct.rawValue : TypeId.namedStruct.rawValue
      } else {
        typeID = compatible ? TypeId.compatibleStruct.rawValue : TypeId.structType.rawValue
      }
    } else {
      if (metaHeader & 0b0111_0000) != 0 {
        throw ForyError.invalidData("invalid non-struct TypeMeta kind header")
      }
      let kind = try Self.typeID(forNonStructKindCode: metaHeader & 0b1111)
      registerByName = Self.isNamedTypeMetaKind(kind)
      typeID = kind.rawValue
    }

    if registerByName {
      namespace = try readName(
        bodyReader, decoder: .namespace, encodings: namespaceMetaStringEncodings)
      typeName = try readName(
        bodyReader, decoder: .typeName, encodings: typeNameMetaStringEncodings)
      userTypeID = nil
    } else {
      userTypeID = try bodyReader.readVarUInt32()
      namespace = MetaString.empty(specialChar1: ".", specialChar2: "_")
      typeName = MetaString.empty(specialChar1: "$", specialChar2: "_")
    }

    var fieldInfos: [FieldInfo] = []
    if numFields > bodyReader.remaining {
      throw ForyError.invalidData(
        "type meta field count \(numFields) exceeds remaining bytes \(bodyReader.remaining)"
      )
    }
    fieldInfos.reserveCapacity(numFields)
    for _ in 0..<numFields {
      fieldInfos.append(try FieldInfo.read(bodyReader))
    }

    if !isStruct && !fieldInfos.isEmpty {
      throw ForyError.invalidData("non-struct TypeMeta cannot carry field metadata")
    }
    if bodyReader.remaining != 0 {
      throw ForyError.invalidData("unexpected trailing bytes in TypeMeta body")
    }
    if (header & Self.hashMask())
      != Self.typeMetaHeaderHash(encodedBody, headerLowBits: header & ~Self.hashMask())
    {
      throw ForyError.invalidData("invalid TypeMeta metadata hash")
    }

    return try TypeMeta(
      typeID: typeID,
      userTypeID: userTypeID,
      namespace: namespace,
      typeName: typeName,
      registerByName: registerByName,
      fields: fieldInfos,
      compressed: compressed,
      headerHash: header >> (64 - typeMetaNumHashBits)
    )
  }

  private func encodeBody() throws -> [UInt8] {
    let buffer = ByteBuffer(capacity: 128)
    guard let rawTypeID = typeID, let resolvedTypeID = TypeId(rawValue: rawTypeID) else {
      throw ForyError.encodingError("unsupported TypeMeta kind \(String(describing: typeID))")
    }

    if Self.isStructTypeMetaKind(resolvedTypeID) {
      var metaHeader = structTypeMetaFlag | UInt8(min(fields.count, smallNumFieldsThreshold))
      if resolvedTypeID == .compatibleStruct || resolvedTypeID == .namedCompatibleStruct {
        metaHeader |= compatibleTypeMetaFlag
      }
      if registerByName {
        metaHeader |= registerByNameFlag
      }
      buffer.writeUInt8(metaHeader)

      if fields.count >= smallNumFieldsThreshold {
        buffer.writeVarUInt32(UInt32(fields.count - smallNumFieldsThreshold))
      }
    } else {
      if !fields.isEmpty {
        throw ForyError.encodingError("non-struct TypeMeta cannot carry field metadata")
      }
      buffer.writeUInt8(try Self.nonStructKindCode(resolvedTypeID))
    }

    if registerByName {
      try Self.writeName(buffer, name: namespace, encodings: namespaceMetaStringEncodings)
      try Self.writeName(buffer, name: typeName, encodings: typeNameMetaStringEncodings)
    } else {
      guard let userTypeID, userTypeID != noUserTypeID else {
        throw ForyError.encodingError("user type id is required in register-by-id mode")
      }
      buffer.writeVarUInt32(userTypeID)
    }

    for field in fields {
      try field.write(buffer)
    }

    return Array(buffer.storage.prefix(buffer.count))
  }

  private static func hashMask() -> UInt64 {
    UInt64.max << (64 - typeMetaNumHashBits)
  }

  private static func typeMetaHeaderHash(_ body: [UInt8], headerLowBits: UInt64) -> UInt64 {
    var hashInput = body
    hashInput.append(UInt8(truncatingIfNeeded: headerLowBits))
    hashInput.append(UInt8(truncatingIfNeeded: headerLowBits >> 8))
    let bodyHash = MurmurHash3.x64_128(hashInput, seed: typeMetaHashSeed).0
    let shifted = bodyHash << (64 - typeMetaNumHashBits)
    let signed = Int64(bitPattern: shifted)
    let absSigned = signed == Int64.min ? signed : Swift.abs(signed)
    return UInt64(bitPattern: absSigned) & hashMask()
  }

  private static func isStructTypeMetaKind(_ typeID: TypeId) -> Bool {
    switch typeID {
    case .structType, .compatibleStruct, .namedStruct, .namedCompatibleStruct:
      return true
    default:
      return false
    }
  }

  private static func isNamedTypeMetaKind(_ typeID: TypeId) -> Bool {
    switch typeID {
    case .namedStruct, .namedCompatibleStruct, .namedEnum, .namedExt, .namedUnion:
      return true
    default:
      return false
    }
  }

  private static func nonStructKindCode(_ typeID: TypeId) throws -> UInt8 {
    switch typeID {
    case .enumType:
      return 0
    case .namedEnum:
      return 1
    case .ext:
      return 2
    case .namedExt:
      return 3
    case .typedUnion:
      return 4
    case .namedUnion:
      return 5
    default:
      throw ForyError.encodingError("unsupported TypeMeta kind \(typeID)")
    }
  }

  private static func typeID(forNonStructKindCode code: UInt8) throws -> TypeId {
    switch code {
    case 0:
      return .enumType
    case 1:
      return .namedEnum
    case 2:
      return .ext
    case 3:
      return .namedExt
    case 4:
      return .typedUnion
    case 5:
      return .namedUnion
    default:
      throw ForyError.invalidData("unsupported TypeMeta kind code \(code)")
    }
  }

  private static func writeName(
    _ buffer: ByteBuffer,
    name: MetaString,
    encodings: [MetaStringEncoding]
  ) throws {
    let normalizedName: MetaString
    if encodings.contains(name.encoding) {
      normalizedName = name
    } else {
      let encoder: MetaStringEncoder
      if encodings == namespaceMetaStringEncodings {
        encoder = .namespace
      } else if encodings == typeNameMetaStringEncodings {
        encoder = .typeName
      } else {
        encoder = .fieldName
      }
      normalizedName = try encoder.encode(name.value, allowedEncodings: encodings)
    }

    guard let encodingIndex = encodings.firstIndex(of: normalizedName.encoding) else {
      throw ForyError.encodingError("failed to normalize meta string encoding")
    }

    let bytes = normalizedName.bytes
    if bytes.count >= bigNameThreshold {
      buffer.writeUInt8(UInt8((bigNameThreshold << 2) | encodingIndex))
      buffer.writeVarUInt32(UInt32(bytes.count - bigNameThreshold))
    } else {
      buffer.writeUInt8(UInt8((bytes.count << 2) | encodingIndex))
    }
    buffer.writeBytes(bytes)
  }

  private static func readName(
    _ buffer: ByteBuffer,
    decoder: MetaStringDecoder,
    encodings: [MetaStringEncoding]
  ) throws -> MetaString {
    let header = try buffer.readUInt8()
    let encodingIndex = Int(header & 0b11)
    guard encodingIndex < encodings.count else {
      throw ForyError.invalidData("invalid meta string encoding index")
    }

    var length = Int(header >> 2)
    if length >= bigNameThreshold {
      length = bigNameThreshold + Int(try buffer.readVarUInt32())
    }
    let bytes = try buffer.readBytes(count: length)
    return try decoder.decode(bytes: bytes, encoding: encodings[encodingIndex])
  }

  func assigningFieldIDs(from localTypeMeta: TypeMeta) throws -> TypeMeta {
    guard !fields.isEmpty else {
      return self
    }

    let localFields = localTypeMeta.fields
    guard !localFields.isEmpty else {
      return self
    }

    var fieldIndexByName: [String: (Int, FieldInfo)] = [:]
    var fieldIndexByID: [Int16: (Int, FieldInfo)] = [:]
    fieldIndexByName.reserveCapacity(localFields.count)
    fieldIndexByID.reserveCapacity(localFields.count)

    for (index, localField) in localFields.enumerated() {
      fieldIndexByName[toSnakeCase(localField.fieldName)] = (index, localField)
      if let fieldID = localField.fieldID, fieldID >= 0 {
        fieldIndexByID[fieldID] = (index, localField)
      }
    }

    var resolvedFields = fields
    var changed = false
    var usedLocalFields = Array(repeating: false, count: localFields.count)

    for index in resolvedFields.indices {
      let field = resolvedFields[index]

      var localMatch: (Int, FieldInfo)?
      if let fieldID = field.fieldID, fieldID >= 0 {
        if let candidate = fieldIndexByID[fieldID],
          Self.isCompatibleFieldType(field.fieldType, candidate.1.fieldType)
        {
          localMatch = candidate
        }
      }

      if localMatch == nil {
        if let candidate = fieldIndexByName[toSnakeCase(field.fieldName)],
          Self.isCompatibleFieldType(field.fieldType, candidate.1.fieldType)
        {
          localMatch = candidate
        }
      }

      if localMatch == nil {
        for localIndex in localFields.indices where !usedLocalFields[localIndex] {
          if Self.isCompatibleFieldType(
            field.fieldType,
            localFields[localIndex].fieldType,
            allowScalarConversion: false
          ) {
            localMatch = (localIndex, localFields[localIndex])
            break
          }
        }
      }

      guard let (sortedIndex, _) = localMatch,
        sortedIndex <= Int(Int16.max)
      else {
        if field.fieldID != -1 {
          resolvedFields[index].fieldID = -1
          changed = true
        }
        continue
      }

      let resolvedFieldID = Int16(sortedIndex)
      if field.fieldID != resolvedFieldID {
        resolvedFields[index].fieldID = resolvedFieldID
        changed = true
      }
      usedLocalFields[sortedIndex] = true
    }

    guard changed else {
      return self
    }

    return try TypeMeta(
      typeID: typeID,
      userTypeID: userTypeID,
      namespace: namespace,
      typeName: typeName,
      registerByName: registerByName,
      fields: resolvedFields,
      compressed: compressed,
      headerHash: headerHash
    )
  }

  private static func isCompatibleFieldType(
    _ remoteType: FieldType,
    _ localType: FieldType,
    topLevel: Bool = true,
    allowScalarConversion: Bool = true
  ) -> Bool {
    if topLevel, isCompatibleTopLevelListArrayFieldType(remoteType, localType) {
      return true
    }
    if topLevel,
      remoteType.trackRef != localType.trackRef,
      compatibleScalarKind(remoteType.typeID) != nil,
      compatibleScalarKind(localType.typeID) != nil
    {
      return false
    }
    if topLevel,
      remoteType.trackRef || localType.trackRef,
      compatibleScalarKind(remoteType.typeID) != nil,
      compatibleScalarKind(localType.typeID) != nil,
      remoteType.typeID != localType.typeID || remoteType.nullable != localType.nullable
    {
      return false
    }
    if topLevel, allowScalarConversion, isCompatibleScalarFieldType(remoteType, localType) {
      return true
    }
    if normalizeCompatibleTypeIDForComparison(remoteType.typeID)
      != normalizeCompatibleTypeIDForComparison(localType.typeID)
    {
      return false
    }
    if remoteType.generics.count != localType.generics.count {
      return false
    }
    for (remoteGeneric, localGeneric) in zip(remoteType.generics, localType.generics)
    where !isCompatibleFieldType(
      remoteGeneric,
      localGeneric,
      topLevel: false,
      allowScalarConversion: false
    ) {
      return false
    }
    return true
  }

  private static func isCompatibleTopLevelListArrayFieldType(
    _ remoteType: FieldType,
    _ localType: FieldType
  ) -> Bool {
    if remoteType.typeID == TypeId.list.rawValue {
      return listElementMatchesDenseArrayTypeID(remoteType, arrayTypeID: localType.typeID)
    }
    if localType.typeID == TypeId.list.rawValue {
      return listElementMatchesDenseArrayTypeID(localType, arrayTypeID: remoteType.typeID)
    }
    return false
  }

  private static func listElementMatchesDenseArrayTypeID(
    _ listType: FieldType,
    arrayTypeID: UInt32
  ) -> Bool {
    guard listType.typeID == TypeId.list.rawValue,
      let elementType = listType.generics.first
    else {
      return false
    }
    return TypeId.listElementTypeID(elementType.typeID, matchesDenseArrayTypeID: arrayTypeID)
  }

  private static func isCompatibleScalarFieldType(
    _ remoteType: FieldType,
    _ localType: FieldType
  ) -> Bool {
    guard remoteType.generics.isEmpty,
      localType.generics.isEmpty,
      !remoteType.trackRef,
      !localType.trackRef,
      remoteType.typeID != localType.typeID,
      let remoteKind = compatibleScalarKind(remoteType.typeID),
      let localKind = compatibleScalarKind(localType.typeID)
    else {
      return false
    }
    if remoteKind == .bool {
      return localKind == .string || localKind.isNumeric
    }
    if localKind == .bool {
      return remoteKind == .string || remoteKind.isNumeric
    }
    if remoteKind == .string {
      return localKind.isNumeric
    }
    if localKind == .string {
      return remoteKind.isNumeric
    }
    return remoteKind.isNumeric && localKind.isNumeric
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

  private static func compatibleScalarKind(_ typeID: UInt32) -> CompatibleScalarKind? {
    switch typeID {
    case TypeId.bool.rawValue:
      return .bool
    case TypeId.string.rawValue:
      return .string
    case TypeId.int8.rawValue,
      TypeId.int16.rawValue,
      TypeId.int32.rawValue,
      TypeId.varint32.rawValue,
      TypeId.int64.rawValue,
      TypeId.varint64.rawValue,
      TypeId.taggedInt64.rawValue:
      return .signedInteger
    case TypeId.uint8.rawValue,
      TypeId.uint16.rawValue,
      TypeId.uint32.rawValue,
      TypeId.varUInt32.rawValue,
      TypeId.uint64.rawValue,
      TypeId.varUInt64.rawValue,
      TypeId.taggedUInt64.rawValue:
      return .unsignedInteger
    case TypeId.float16.rawValue,
      TypeId.bfloat16.rawValue,
      TypeId.float32.rawValue,
      TypeId.float64.rawValue:
      return .floatingPoint
    case TypeId.decimal.rawValue:
      return .decimal
    default:
      return nil
    }
  }

  private static func normalizeCompatibleTypeIDForComparison(_ typeID: UInt32) -> UInt32 {
    switch typeID {
    case TypeId.structType.rawValue,
      TypeId.compatibleStruct.rawValue,
      TypeId.namedStruct.rawValue,
      TypeId.namedCompatibleStruct.rawValue,
      TypeId.unknown.rawValue:
      return TypeId.structType.rawValue
    case TypeId.enumType.rawValue,
      TypeId.namedEnum.rawValue:
      return TypeId.enumType.rawValue
    case TypeId.ext.rawValue,
      TypeId.namedExt.rawValue:
      return TypeId.ext.rawValue
    case TypeId.binary.rawValue,
      TypeId.int8Array.rawValue,
      TypeId.uint8Array.rawValue:
      return TypeId.binary.rawValue
    case TypeId.union.rawValue,
      TypeId.typedUnion.rawValue,
      TypeId.namedUnion.rawValue:
      return TypeId.union.rawValue
    default:
      return typeID
    }
  }
}

private func lowerCamelToLowerUnderscore(_ name: String) -> String {
  if name.isEmpty {
    return name
  }

  let chars = Array(name)
  var result = String()
  result.reserveCapacity(name.count + 4)

  for (index, char) in chars.enumerated() {
    if char.isUppercase {
      if index > 0 {
        let prevUpper = chars[index - 1].isUppercase
        let nextUpperOrEnd = (index + 1 >= chars.count) || chars[index + 1].isUppercase
        if !prevUpper || !nextUpperOrEnd {
          result.append("_")
        }
      }
      result.append(char.lowercased())
    } else {
      result.append(char)
    }
  }

  return result
}

private func toSnakeCase(_ name: String) -> String {
  if name.isEmpty {
    return name
  }

  let chars = Array(name)
  var result = String()
  result.reserveCapacity(name.count + 4)

  for (index, char) in chars.enumerated() {
    if char.isUppercase {
      if index > 0 {
        let prevUpper = chars[index - 1].isUppercase
        let nextUpperOrEnd = (index + 1 >= chars.count) || chars[index + 1].isUppercase
        if !prevUpper || !nextUpperOrEnd {
          result.append("_")
        }
      }
      result.append(char.lowercased())
    } else {
      result.append(char)
    }
  }

  return result
}
