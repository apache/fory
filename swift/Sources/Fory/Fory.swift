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

public struct Config {
  public let trackRef: Bool
  public let compatible: Bool
  public let checkClassVersion: Bool
  public let maxCollectionSize: Int
  public let maxBinarySize: Int
  public let maxDepth: Int

  public init(
    trackRef: Bool = false,
    compatible: Bool? = nil,
    checkClassVersion: Bool? = nil,
    maxCollectionSize: Int = 1_000_000,
    maxBinarySize: Int = 64 * 1024 * 1024,
    maxDepth: Int = 5
  ) {
    let effectiveCompatible = compatible ?? true
    let effectiveCheckClassVersion = checkClassVersion ?? !effectiveCompatible
    self.trackRef = trackRef
    self.compatible = effectiveCompatible
    self.checkClassVersion = effectiveCheckClassVersion
    self.maxCollectionSize = maxCollectionSize
    self.maxBinarySize = maxBinarySize
    self.maxDepth = maxDepth
  }
}

/// Single-threaded Fory runtime.
///
/// Reuse one `Fory` per thread for the fastest path. The runtime keeps one
/// reusable read/write context pair and must not be used concurrently from
/// multiple threads.
public final class Fory {
  public let config: Config
  let typeResolver: TypeResolver
  private let writeContext: WriteContext
  private let readContext: ReadContext

  public convenience init(
    ref: Bool = false,
    compatible: Bool? = nil,
    checkClassVersion: Bool? = nil,
    maxCollectionSize: Int = 1_000_000,
    maxBinarySize: Int = 64 * 1024 * 1024,
    maxDepth: Int = 5
  ) {
    self.init(
      config: Config(
        trackRef: ref,
        compatible: compatible,
        checkClassVersion: checkClassVersion,
        maxCollectionSize: maxCollectionSize,
        maxBinarySize: maxBinarySize,
        maxDepth: maxDepth
      ))
  }

  public init(config: Config) {
    self.config = config
    self.typeResolver = TypeResolver(trackRef: self.config.trackRef)
    self.writeContext = WriteContext(
      buffer: ByteBuffer(),
      typeResolver: typeResolver,
      trackRef: self.config.trackRef,
      compatible: self.config.compatible,
      checkClassVersion: self.config.checkClassVersion,
      maxDepth: self.config.maxDepth,
      metaStringWriteState: MetaStringWriteState()
    )
    self.readContext = ReadContext(
      buffer: ByteBuffer(),
      typeResolver: typeResolver,
      trackRef: self.config.trackRef,
      compatible: self.config.compatible,
      checkClassVersion: self.config.checkClassVersion,
      maxCollectionSize: self.config.maxCollectionSize,
      maxBinarySize: self.config.maxBinarySize,
      maxDepth: self.config.maxDepth
    )
  }

  public func register<T: Serializer>(_ type: T.Type, id: UInt32) {
    typeResolver.register(type, id: id)
  }

  /// Registers a user type by name. The last `.` separates namespace from the final type name.
  public func register<T: Serializer>(_ type: T.Type, name: String) throws {
    try typeResolver.register(type, name: name)
  }

  public func serialize<T: Serializer>(_ value: T) throws -> Data {
    try serializeRoot { context in
      try writeRootTypedValue(value, context: context)
    }
  }

  public func deserialize<T: Serializer>(_ data: Data, as _: T.Type = T.self) throws -> T {
    try deserializeRoot(
      data: data
    ) { context in
      try readRootTypedValue(context: context)
    }
  }

  public func serialize<T: Serializer>(_ value: T, to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try writeRootTypedValue(value, context: context)
    }
  }

  public func deserialize<T: Serializer>(from buffer: ByteBuffer, as _: T.Type = T.self) throws -> T {
    try deserializeRoot(
      from: buffer
    ) { context in
      try readRootTypedValue(context: context)
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: Any) throws -> Data {
    try serializeRoot { context in
      try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: Any.Type = Any.self) throws -> Any {
    try deserializeRoot(
      data: data
    ) { context in
      try castAnyDynamicValue(
        context.readAny(refMode: refMode, readTypeInfo: true),
        to: Any.self
      )
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: AnyObject) throws -> Data {
    try serializeRoot { context in
      try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: AnyObject.Type = AnyObject.self) throws -> AnyObject {
    try deserializeRoot(
      data: data
    ) { context in
      try castAnyDynamicValue(
        context.readAny(refMode: refMode, readTypeInfo: true),
        to: AnyObject.self
      )
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: any Serializer) throws -> Data {
    try serializeRoot { context in
      try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: (any Serializer).Type = (any Serializer).self) throws
    -> any Serializer {
    try deserializeRoot(
      data: data
    ) { context in
      try castAnyDynamicValue(
        context.readAny(refMode: refMode, readTypeInfo: true),
        to: (any Serializer).self
      )
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [Any]) throws -> Data {
    try serializeRoot { context in
      try context.writeListOfAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: [Any].Type = [Any].self) throws -> [Any] {
    try deserializeRoot(
      data: data
    ) { context in
      try context.readListOfAny(refMode: refMode, readTypeInfo: true) ?? []
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [String: Any]) throws -> Data {
    try serializeRoot { context in
      try context.writeMapStringToAny(
        value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: [String: Any].Type = [String: Any].self) throws
    -> [String: Any] {
    try deserializeRoot(
      data: data
    ) { context in
      try context.readMapStringToAny(refMode: refMode, readTypeInfo: true) ?? [:]
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [Int32: Any]) throws -> Data {
    try serializeRoot { context in
      try context.writeMapInt32ToAny(
        value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: [Int32: Any].Type = [Int32: Any].self) throws
    -> [Int32: Any] {
    try deserializeRoot(
      data: data
    ) { context in
      try context.readMapInt32ToAny(refMode: refMode, readTypeInfo: true) ?? [:]
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [AnyHashable: Any]) throws -> Data {
    try serializeRoot { context in
      try context.writeMapAnyHashableToAny(
        value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(_ data: Data, as _: [AnyHashable: Any].Type = [AnyHashable: Any].self)
    throws -> [AnyHashable: Any] {
    try deserializeRoot(
      data: data
    ) { context in
      try context.readMapAnyHashableToAny(refMode: refMode, readTypeInfo: true) ?? [:]
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [Any], to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeListOfAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: Any, to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(from buffer: ByteBuffer, as _: Any.Type = Any.self) throws -> Any {
    try deserializeRoot(
      from: buffer
    ) { context in
      try castAnyDynamicValue(
        context.readAny(refMode: refMode, readTypeInfo: true),
        to: Any.self
      )
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: AnyObject, to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(from buffer: ByteBuffer, as _: AnyObject.Type = AnyObject.self) throws
    -> AnyObject {
    try deserializeRoot(
      from: buffer
    ) { context in
      try castAnyDynamicValue(
        context.readAny(refMode: refMode, readTypeInfo: true),
        to: AnyObject.self
      )
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: any Serializer, to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(
    from buffer: ByteBuffer,
    as _: (any Serializer).Type = (any Serializer).self
  ) throws -> any Serializer {
    try deserializeRoot(
      from: buffer
    ) { context in
      try castAnyDynamicValue(
        context.readAny(refMode: refMode, readTypeInfo: true),
        to: (any Serializer).self
      )
    }
  }

  @_disfavoredOverload
  public func deserialize(from buffer: ByteBuffer, as _: [Any].Type = [Any].self) throws -> [Any] {
    try deserializeRoot(
      from: buffer
    ) { context in
      try context.readListOfAny(refMode: refMode, readTypeInfo: true) ?? []
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [String: Any], to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeMapStringToAny(
        value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(from buffer: ByteBuffer, as _: [String: Any].Type = [String: Any].self)
    throws -> [String: Any] {
    try deserializeRoot(
      from: buffer
    ) { context in
      try context.readMapStringToAny(refMode: refMode, readTypeInfo: true) ?? [:]
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [Int32: Any], to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeMapInt32ToAny(
        value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func serialize(_ value: [AnyHashable: Any], to buffer: inout Data) throws {
    try appendSerializedRoot(to: &buffer) { context in
      try context.writeMapAnyHashableToAny(
        value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
    }
  }

  @_disfavoredOverload
  public func deserialize(from buffer: ByteBuffer, as _: [Int32: Any].Type = [Int32: Any].self)
    throws -> [Int32: Any] {
    try deserializeRoot(
      from: buffer
    ) { context in
      try context.readMapInt32ToAny(refMode: refMode, readTypeInfo: true) ?? [:]
    }
  }

  @_disfavoredOverload
  public func deserialize(
    from buffer: ByteBuffer, as _: [AnyHashable: Any].Type = [AnyHashable: Any].self
  ) throws -> [AnyHashable: Any] {
    try deserializeRoot(
      from: buffer
    ) { context in
      try context.readMapAnyHashableToAny(refMode: refMode, readTypeInfo: true) ?? [:]
    }
  }

  @inlinable
  @inline(__always)
  func writeHead(buffer: ByteBuffer) {
    buffer.writeUInt8(ForyHeaderFlag.isXlang)
  }

  @inlinable
  @inline(__always)
  func readHead(buffer: ByteBuffer) throws {
    let bitmap = try buffer.readUInt8()
    let expected = ForyHeaderFlag.isXlang
    if bitmap != expected {
      try readHeadSlow(bitmap: bitmap, expected: expected)
    }
  }

  @usableFromInline
  @inline(never)
  func readHeadSlow(bitmap: UInt8, expected: UInt8) throws {
    if (bitmap & ~ForyHeaderFlag.knownMask) != 0 || (bitmap & ForyHeaderFlag.isOutOfBand) != 0 {
      throw ForyError.invalidData("unsupported root header bitmap 0x\(String(bitmap, radix: 16))")
    }
    if (bitmap & ForyHeaderFlag.isXlang) != (expected & ForyHeaderFlag.isXlang) {
      throw ForyError.invalidData("xlang bitmap mismatch")
    }
  }

  @inline(__always)
  private var refMode: RefMode {
    config.trackRef ? .tracking : .nullOnly
  }

  private func writeRootTypedValue<T: Serializer>(
    _ value: T,
    context: WriteContext
  ) throws {
    try value.foryWrite(
      context,
      refMode: refMode,
      writeTypeInfo: true,
      hasGenerics: false
    )
  }

  @inline(__always)
  private func readRootTypedValue<T: Serializer>(
    context: ReadContext
  ) throws -> T {
    return try T.foryRead(
      context,
      refMode: refMode,
      readTypeInfo: true
    )
  }

  @inline(__always)
  func withReusableReadContext<R>(
    data: Data,
    _ body: (ReadContext) throws -> R
  ) rethrows -> R {
    readContext.buffer.replace(with: data)
    defer {
      readContext.reset()
    }
    return try body(readContext)
  }

  @inline(__always)
  private func serializeRoot(
    _ body: (WriteContext) throws -> Void
  ) throws -> Data {
    typeResolver.finishRegistration()
    let context = writeContext
    context.buffer.clear()
    defer {
      context.reset()
    }
    writeHead(buffer: context.buffer)
    try body(context)
    return context.buffer.copyToData()
  }

  @inline(__always)
  private func appendSerializedRoot(
    to output: inout Data,
    _ body: (WriteContext) throws -> Void
  ) throws {
    typeResolver.finishRegistration()
    let context = writeContext
    context.buffer.clear()
    defer {
      context.reset()
    }
    writeHead(buffer: context.buffer)
    try body(context)
    output.append(contentsOf: context.buffer.storage.prefix(context.buffer.count))
  }

  @inline(__always)
  private func deserializeRoot<R>(
    data: Data,
    _ body: (ReadContext) throws -> R
  ) throws -> R {
    typeResolver.finishRegistration()
    return try withReusableReadContext(data: data) { context in
      try readHead(buffer: context.buffer)
      let value = try body(context)
      if context.buffer.remaining != 0 {
        throw ForyError.invalidData(
          "unexpected trailing bytes at root: \(context.buffer.remaining)")
      }
      return value
    }
  }

  @inline(__always)
  private func deserializeRoot<R>(
    from buffer: ByteBuffer,
    _ body: (ReadContext) throws -> R
  ) throws -> R {
    typeResolver.finishRegistration()
    readContext.buffer.swapState(with: buffer)
    defer {
      readContext.buffer.swapState(with: buffer)
      readContext.reset()
    }
    try readHead(buffer: readContext.buffer)
    return try body(readContext)
  }
}
