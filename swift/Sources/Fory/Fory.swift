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

public struct ForyConfig {
    public var xlang: Bool
    public var trackRef: Bool
    public var compatible: Bool
    public var checkClassVersion: Bool
    public var maxCollectionSize: Int
    public var maxBinarySize: Int
    public var maxDepth: Int

    public init(
        xlang: Bool = true,
        trackRef: Bool = false,
        compatible: Bool = false,
        checkClassVersion: Bool = true,
        maxCollectionSize: Int = 1_000_000,
        maxBinarySize: Int = 64 * 1024 * 1024,
        maxDepth: Int = 5
    ) {
        self.xlang = xlang
        self.trackRef = trackRef
        self.compatible = compatible
        self.checkClassVersion = checkClassVersion
        self.maxCollectionSize = maxCollectionSize
        self.maxBinarySize = maxBinarySize
        self.maxDepth = maxDepth
    }

    static func resolved(
        xlang: Bool = true,
        trackRef: Bool = false,
        compatible: Bool = false,
        checkClassVersion: Bool? = nil,
        maxCollectionSize: Int = 1_000_000,
        maxBinarySize: Int = 64 * 1024 * 1024,
        maxDepth: Int = 5
    ) -> ForyConfig {
        let effectiveCheckClassVersion = checkClassVersion ?? (xlang && !compatible)
        return ForyConfig(
            xlang: xlang,
            trackRef: trackRef,
            compatible: compatible,
            checkClassVersion: effectiveCheckClassVersion,
            maxCollectionSize: maxCollectionSize,
            maxBinarySize: maxBinarySize,
            maxDepth: maxDepth
        )
    }
}

/// Single-threaded Fory runtime.
///
/// Reuse one `Fory` per thread for the fastest path. The runtime keeps one
/// reusable read/write context pair and must not be used concurrently from
/// multiple threads.
public final class Fory {
    public let config: ForyConfig
    let typeResolver: TypeResolver
    private let writeContext: WriteContext
    private let readContext: ReadContext

    public init(
        xlang: Bool = true,
        trackRef: Bool = false,
        compatible: Bool = false,
        checkClassVersion: Bool? = nil,
        maxCollectionSize: Int = 1_000_000,
        maxBinarySize: Int = 64 * 1024 * 1024,
        maxDepth: Int = 5
    ) {
        self.config = ForyConfig.resolved(
            xlang: xlang,
            trackRef: trackRef,
            compatible: compatible,
            checkClassVersion: checkClassVersion,
            maxCollectionSize: maxCollectionSize,
            maxBinarySize: maxBinarySize,
            maxDepth: maxDepth
        )
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

    public convenience init(config: ForyConfig) {
        self.init(
            xlang: config.xlang,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            maxCollectionSize: config.maxCollectionSize,
            maxBinarySize: config.maxBinarySize,
            maxDepth: config.maxDepth
        )
    }

    public func register<T: Serializer>(_ type: T.Type, id: UInt32) {
        typeResolver.register(type, id: id)
    }

    public func register<T: Serializer>(_ type: T.Type, name: String) throws {
        try typeResolver.register(type, name: name)
    }

    public func register<T: Serializer>(_ type: T.Type, namespace: String, name: String) throws {
        try typeResolver.register(type, namespace: namespace, typeName: name)
    }

    public func serialize<T: Serializer>(_ value: T) throws -> Data {
        let context = writeContext
        context.buffer.clear()
        defer {
            context.reset()
        }

        writeHead(buffer: context.buffer, isNone: value.foryIsNone)
        if !value.foryIsNone {
            try writeRootTypedValue(value, context: context)
        }
        return context.buffer.copyToData()
    }

    public func deserialize<T: Serializer>(_ data: Data, as _: T.Type = T.self) throws -> T {
        try withReusableReadContext(data: data) { context in
            if try readHead(buffer: context.buffer) {
                return T.foryDefault()
            }
            let value: T = try readRootTypedValue(context: context)
            if context.buffer.remaining != 0 {
                throw ForyError.invalidData("unexpected trailing bytes at root: \(context.buffer.remaining)")
            }
            return value
        }
    }

    public func serialize<T: Serializer>(_ value: T, to buffer: inout Data) throws {
        let context = writeContext
        context.buffer.clear()
        defer {
            context.reset()
        }

        writeHead(buffer: context.buffer, isNone: value.foryIsNone)
        if !value.foryIsNone {
            try writeRootTypedValue(value, context: context)
        }
        buffer.append(contentsOf: context.buffer.storage.prefix(context.buffer.count))
    }

    public func deserialize<T: Serializer>(from buffer: ByteBuffer, as _: T.Type = T.self) throws -> T {
        try deserializeRoot(
            from: buffer,
            nilValue: T.foryDefault()
        ) { context in
            try readRootTypedValue(context: context)
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: Any) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: Any.Type = Any.self) throws -> Any {
        try deserializeRoot(
            data: data,
            nilValue: ForyAnyNullValue()
        ) { context in
            try castAnyDynamicValue(
                context.readAny(refMode: refMode, readTypeInfo: true),
                to: Any.self
            )
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: AnyObject) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: AnyObject.Type = AnyObject.self) throws -> AnyObject {
        try deserializeRoot(
            data: data,
            nilValue: NSNull()
        ) { context in
            try castAnyDynamicValue(
                context.readAny(refMode: refMode, readTypeInfo: true),
                to: AnyObject.self
            )
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: any Serializer) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: (any Serializer).Type = (any Serializer).self) throws -> any Serializer {
        try deserializeRoot(
            data: data,
            nilValue: ForyAnyNullValue()
        ) { context in
            try castAnyDynamicValue(
                context.readAny(refMode: refMode, readTypeInfo: true),
                to: (any Serializer).self
            )
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [Any]) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeAnyList(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [Any].Type = [Any].self) throws -> [Any] {
        try deserializeRoot(
            data: data,
            nilValue: []
        ) { context in
            try context.readAnyList(refMode: refMode, readTypeInfo: true) ?? []
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [String: Any]) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeStringAnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [String: Any].Type = [String: Any].self) throws -> [String: Any] {
        try deserializeRoot(
            data: data,
            nilValue: [:]
        ) { context in
            try context.readStringAnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [Int32: Any]) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeInt32AnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [Int32: Any].Type = [Int32: Any].self) throws -> [Int32: Any] {
        try deserializeRoot(
            data: data,
            nilValue: [:]
        ) { context in
            try context.readInt32AnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [AnyHashable: Any]) throws -> Data {
        try serializeRoot(isNone: false) { context in
            try context.writeAnyHashableAnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [AnyHashable: Any].Type = [AnyHashable: Any].self) throws -> [AnyHashable: Any] {
        try deserializeRoot(
            data: data,
            nilValue: [:]
        ) { context in
            try context.readAnyHashableAnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [Any], to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeAnyList(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: Any, to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: Any.Type = Any.self) throws -> Any {
        try deserializeRoot(
            from: buffer,
            nilValue: ForyAnyNullValue()
        ) { context in
            try castAnyDynamicValue(
                context.readAny(refMode: refMode, readTypeInfo: true),
                to: Any.self
            )
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: AnyObject, to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: AnyObject.Type = AnyObject.self) throws -> AnyObject {
        try deserializeRoot(
            from: buffer,
            nilValue: NSNull()
        ) { context in
            try castAnyDynamicValue(
                context.readAny(refMode: refMode, readTypeInfo: true),
                to: AnyObject.self
            )
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: any Serializer, to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(
        from buffer: ByteBuffer,
        as _: (any Serializer).Type = (any Serializer).self
    ) throws -> any Serializer {
        try deserializeRoot(
            from: buffer,
            nilValue: ForyAnyNullValue()
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
            from: buffer,
            nilValue: []
        ) { context in
            try context.readAnyList(refMode: refMode, readTypeInfo: true) ?? []
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [String: Any], to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeStringAnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: [String: Any].Type = [String: Any].self) throws -> [String: Any] {
        try deserializeRoot(
            from: buffer,
            nilValue: [:]
        ) { context in
            try context.readStringAnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [Int32: Any], to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeInt32AnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func serialize(_ value: [AnyHashable: Any], to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: false) { context in
            try context.writeAnyHashableAnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        }
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: [Int32: Any].Type = [Int32: Any].self) throws -> [Int32: Any] {
        try deserializeRoot(
            from: buffer,
            nilValue: [:]
        ) { context in
            try context.readInt32AnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        }
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: [AnyHashable: Any].Type = [AnyHashable: Any].self) throws -> [AnyHashable: Any] {
        try deserializeRoot(
            from: buffer,
            nilValue: [:]
        ) { context in
            try context.readAnyHashableAnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        }
    }

    @inlinable
    @inline(__always)
    func writeHead(buffer: ByteBuffer, isNone: Bool) {
        var bitmap: UInt8 = 0
        if config.xlang {
            bitmap |= ForyHeaderFlag.isXlang
        }
        if isNone {
            bitmap |= ForyHeaderFlag.isNull
        }
        buffer.writeUInt8(bitmap)
    }

    @inlinable
    @inline(__always)
    func readHead(buffer: ByteBuffer) throws -> Bool {
        let bitmap = try buffer.readUInt8()
        let peerIsXlang = (bitmap & ForyHeaderFlag.isXlang) != 0
        if peerIsXlang != config.xlang {
            throw ForyError.invalidData("xlang bitmap mismatch")
        }
        return (bitmap & ForyHeaderFlag.isNull) != 0
    }

    @inline(__always)
    private var refMode: RefMode {
        config.trackRef ? .tracking : .nullOnly
    }

    private func writeRootTypedValue<T: Serializer>(
        _ value: T,
        context: WriteContext
    ) throws {
        let writeTypeInfo = config.xlang || config.compatible
        try value.foryWrite(
            context,
            refMode: config.trackRef ? .tracking : (writeTypeInfo ? .nullOnly : .none),
            writeTypeInfo: writeTypeInfo,
            hasGenerics: false
        )
    }

    @inline(__always)
    private func readRootTypedValue<T: Serializer>(
        context: ReadContext
    ) throws -> T {
        let readTypeInfo = config.xlang || config.compatible
        return try T.foryRead(
            context,
            refMode: config.trackRef ? .tracking : (readTypeInfo ? .nullOnly : .none),
            readTypeInfo: readTypeInfo
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
        isNone: Bool,
        _ body: (WriteContext) throws -> Void
    ) throws -> Data {
        let context = writeContext
        context.buffer.clear()
        defer {
            context.reset()
        }
        writeHead(buffer: context.buffer, isNone: isNone)
        if !isNone {
            try body(context)
        }
        return context.buffer.copyToData()
    }

    @inline(__always)
    private func appendSerializedRoot(
        to output: inout Data,
        isNone: Bool,
        _ body: (WriteContext) throws -> Void
    ) throws {
        let context = writeContext
        context.buffer.clear()
        defer {
            context.reset()
        }
        writeHead(buffer: context.buffer, isNone: isNone)
        if !isNone {
            try body(context)
        }
        output.append(contentsOf: context.buffer.storage.prefix(context.buffer.count))
    }

    @inline(__always)
    private func deserializeRoot<R>(
        data: Data,
        nilValue: @autoclosure () -> R,
        _ body: (ReadContext) throws -> R
    ) throws -> R {
        try withReusableReadContext(data: data) { context in
            if try readHead(buffer: context.buffer) {
                return nilValue()
            }
            let value = try body(context)
            if context.buffer.remaining != 0 {
                throw ForyError.invalidData("unexpected trailing bytes at root: \(context.buffer.remaining)")
            }
            return value
        }
    }

    @inline(__always)
    private func deserializeRoot<R>(
        from buffer: ByteBuffer,
        nilValue: @autoclosure () -> R,
        _ body: (ReadContext) throws -> R
    ) throws -> R {
        readContext.buffer.swapState(with: buffer)
        defer {
            readContext.buffer.swapState(with: buffer)
            readContext.reset()
        }
        if try readHead(buffer: readContext.buffer) {
            return nilValue()
        }
        return try body(readContext)
    }
}
