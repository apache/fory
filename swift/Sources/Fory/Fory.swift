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
    public var maxCollectionLength: Int
    public var maxBinaryLength: Int

    public init(
        xlang: Bool = true,
        trackRef: Bool = false,
        compatible: Bool = false,
        checkClassVersion: Bool = true,
        maxCollectionLength: Int = 1_000_000,
        maxBinaryLength: Int = 64 * 1024 * 1024
    ) {
        self.xlang = xlang
        self.trackRef = trackRef
        self.compatible = compatible
        self.checkClassVersion = checkClassVersion
        self.maxCollectionLength = maxCollectionLength
        self.maxBinaryLength = maxBinaryLength
    }
}

private final class ForyRuntimeContext {
    let writeBuffer: ByteBuffer
    let writeContext: WriteContext
    let readBuffer: ByteBuffer
    let readContext: ReadContext

    var writeInUse = false
    var readInUse = false

    var lastReadDataAddress: UnsafeRawPointer?
    var lastReadDataCount: Int = -1

    init(typeResolver: TypeResolver, config: ForyConfig) {
        writeBuffer = ByteBuffer()
        writeContext = WriteContext(
            buffer: writeBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )

        readBuffer = ByteBuffer()
        readContext = ReadContext(
            buffer: readBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            maxCollectionLength: config.maxCollectionLength,
            maxBinaryLength: config.maxBinaryLength,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
    }
}

private final class ForyThreadContextCache {
    private var cachedID: UInt64 = .max
    private var cachedContext: ForyRuntimeContext?
    private var others: [UInt64: ForyRuntimeContext] = [:]

    @inline(__always)
    func getOrCreate(id: UInt64, create: () -> ForyRuntimeContext) -> ForyRuntimeContext {
        if cachedID == id, let cachedContext {
            return cachedContext
        }

        if let cachedContext {
            others[cachedID] = cachedContext
        }

        let context = others.removeValue(forKey: id) ?? create()
        cachedID = id
        cachedContext = context
        return context
    }
}

private final class ForyThreadContextStore: @unchecked Sendable {
    private let key: pthread_key_t

    init() {
        var localKey = pthread_key_t()
        let createResult = pthread_key_create(&localKey) { rawPointer in
            Unmanaged<ForyThreadContextCache>.fromOpaque(rawPointer).release()
        }
        precondition(createResult == 0, "failed to create pthread TLS key")
        key = localKey
    }

    deinit {
        pthread_key_delete(key)
    }

    @inline(__always)
    func get() -> ForyThreadContextCache {
        if let rawPointer = pthread_getspecific(key) {
            return Unmanaged<ForyThreadContextCache>.fromOpaque(rawPointer).takeUnretainedValue()
        }

        let cache = ForyThreadContextCache()
        let setResult = pthread_setspecific(key, Unmanaged.passRetained(cache).toOpaque())
        precondition(setResult == 0, "failed to set pthread TLS value")
        return cache
    }
}

public final class Fory {
    public let config: ForyConfig
    public let typeResolver: TypeResolver

    private let instanceID: UInt64

    private static let threadContextStore = ForyThreadContextStore()
    private static let instanceIDLock = NSLock()
    nonisolated(unsafe) private static var nextInstanceID: UInt64 = 0

    public init(
        xlang: Bool = true,
        trackRef: Bool = false,
        compatible: Bool = false,
        checkClassVersion: Bool? = nil,
        maxCollectionLength: Int = 1_000_000,
        maxBinaryLength: Int = 64 * 1024 * 1024
    ) {
        let effectiveCheckClassVersion = checkClassVersion ?? (xlang && !compatible)
        self.config = ForyConfig(
            xlang: xlang,
            trackRef: trackRef,
            compatible: compatible,
            checkClassVersion: effectiveCheckClassVersion,
            maxCollectionLength: maxCollectionLength,
            maxBinaryLength: maxBinaryLength
        )
        self.typeResolver = TypeResolver()
        self.instanceID = Self.allocateInstanceID()
    }

    public convenience init(config: ForyConfig) {
        self.init(
            xlang: config.xlang,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            maxCollectionLength: config.maxCollectionLength,
            maxBinaryLength: config.maxBinaryLength
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
        try serializeRoot(isNone: value.foryIsNone) { context in
            if useRootDataFastPath {
                try value.foryWriteData(context, hasGenerics: false)
            } else {
                try value.foryWrite(context, refMode: rootRefMode, writeTypeInfo: shouldWriteRootTypeInfo, hasGenerics: false)
            }
        }
    }

    public func deserialize<T: Serializer>(_ data: Data, as _: T.Type = T.self) throws -> T {
        try deserializeRoot(
            data: data,
            nilValue: T.foryDefault()
        ) { context in
            if useRootDataFastPath {
                return try T.foryReadData(context)
            }
            return try T.foryRead(context, refMode: rootRefMode, readTypeInfo: shouldWriteRootTypeInfo)
        }
    }

    public func serialize<T: Serializer>(_ value: T, to buffer: inout Data) throws {
        try appendSerializedRoot(to: &buffer, isNone: value.foryIsNone) { context in
            if useRootDataFastPath {
                try value.foryWriteData(context, hasGenerics: false)
            } else {
                try value.foryWrite(context, refMode: rootRefMode, writeTypeInfo: shouldWriteRootTypeInfo, hasGenerics: false)
            }
        }
    }

    public func deserialize<T: Serializer>(from buffer: ByteBuffer, as _: T.Type = T.self) throws -> T {
        try deserializeRoot(
            from: buffer,
            nilValue: T.foryDefault()
        ) { context in
            if useRootDataFastPath {
                return try T.foryReadData(context)
            }
            return try T.foryRead(context, refMode: rootRefMode, readTypeInfo: shouldWriteRootTypeInfo)
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

    public func writeHead(buffer: ByteBuffer, isNone: Bool) {
        var bitmap: UInt8 = 0
        if config.xlang {
            bitmap |= ForyHeaderFlag.isXlang
        }
        if isNone {
            bitmap |= ForyHeaderFlag.isNull
        }
        buffer.writeUInt8(bitmap)
    }

    public func readHead(buffer: ByteBuffer) throws -> Bool {
        let bitmap = try buffer.readUInt8()
        let peerIsXlang = (bitmap & ForyHeaderFlag.isXlang) != 0
        if peerIsXlang != config.xlang {
            throw ForyError.invalidData("xlang bitmap mismatch")
        }
        return (bitmap & ForyHeaderFlag.isNull) != 0
    }

    private static func allocateInstanceID() -> UInt64 {
        instanceIDLock.lock()
        defer { instanceIDLock.unlock() }
        let id = nextInstanceID
        nextInstanceID &+= 1
        return id
    }

    @inline(__always)
    private var refMode: RefMode {
        config.trackRef ? .tracking : .nullOnly
    }

    @inline(__always)
    private var shouldWriteRootTypeInfo: Bool {
        config.xlang || config.compatible
    }

    @inline(__always)
    private var rootRefMode: RefMode {
        if config.trackRef {
            return .tracking
        }
        return shouldWriteRootTypeInfo ? .nullOnly : .none
    }

    @inline(__always)
    private var useRootDataFastPath: Bool {
        !shouldWriteRootTypeInfo && rootRefMode == .none
    }

    @inline(__always)
    private func makeWriteContext(buffer: ByteBuffer) -> WriteContext {
        WriteContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
    }

    @inline(__always)
    private func makeReadContext(buffer: ByteBuffer) -> ReadContext {
        ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            maxCollectionLength: config.maxCollectionLength,
            maxBinaryLength: config.maxBinaryLength,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
    }

    @inline(__always)
    private func runtimeContext() -> ForyRuntimeContext {
        let cache = threadContextCache()
        return cache.getOrCreate(id: instanceID) {
            ForyRuntimeContext(typeResolver: typeResolver, config: config)
        }
    }

    @inline(__always)
    private func threadContextCache() -> ForyThreadContextCache {
        Self.threadContextStore.get()
    }

    @inline(__always)
    private func withReusableWriteContext<R>(
        _ body: (WriteContext) throws -> R
    ) rethrows -> R {
        let runtimeContext = runtimeContext()

        if runtimeContext.writeInUse {
            let temporaryBuffer = ByteBuffer()
            let temporaryContext = makeWriteContext(buffer: temporaryBuffer)
            defer { temporaryContext.reset() }
            return try body(temporaryContext)
        }

        runtimeContext.writeInUse = true
        runtimeContext.writeBuffer.clear()
        defer {
            runtimeContext.writeContext.reset()
            runtimeContext.writeInUse = false
        }
        return try body(runtimeContext.writeContext)
    }

    @inline(__always)
    private func withReusableReadContext<R>(
        data: Data,
        _ body: (ReadContext) throws -> R
    ) rethrows -> R {
        let runtimeContext = runtimeContext()

        if runtimeContext.readInUse {
            let temporaryBuffer = ByteBuffer(data: data)
            let temporaryContext = makeReadContext(buffer: temporaryBuffer)
            defer { temporaryContext.reset() }
            return try body(temporaryContext)
        }

        runtimeContext.readInUse = true
        runtimeContext.readBuffer.replace(with: data)
        data.withUnsafeBytes { rawBytes in
            runtimeContext.lastReadDataAddress = rawBytes.baseAddress
            runtimeContext.lastReadDataCount = rawBytes.count
        }
        defer {
            runtimeContext.readContext.reset()
            runtimeContext.readInUse = false
        }
        return try body(runtimeContext.readContext)
    }

    @inline(__always)
    private func withTemporaryReadContext<R>(
        buffer: ByteBuffer,
        _ body: (ReadContext) throws -> R
    ) rethrows -> R {
        let context = makeReadContext(buffer: buffer)
        defer { context.reset() }
        return try body(context)
    }

    @inline(__always)
    private func serializeRoot(
        isNone: Bool,
        _ body: (WriteContext) throws -> Void
    ) throws -> Data {
        try withReusableWriteContext { context in
            writeHead(buffer: context.buffer, isNone: isNone)
            if !isNone {
                try body(context)
            }
            return context.buffer.toData()
        }
    }

    @inline(__always)
    private func appendSerializedRoot(
        to output: inout Data,
        isNone: Bool,
        _ body: (WriteContext) throws -> Void
    ) throws {
        try withReusableWriteContext { context in
            writeHead(buffer: context.buffer, isNone: isNone)
            if !isNone {
                try body(context)
            }
            output.append(contentsOf: context.buffer.storage)
        }
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
        try withTemporaryReadContext(buffer: buffer) { context in
            if try readHead(buffer: buffer) {
                return nilValue()
            }
            return try body(context)
        }
    }
}
