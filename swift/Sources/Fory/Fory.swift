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
    public let maxDepth: Int
    /// Approximate graph-memory gate for one root deserialization.
    ///
    /// Mainly gates materialized arrays, dictionaries, sets, structs, classes, and objects. Leaf
    /// values are gated by unread input bytes instead, and actual process memory can be higher.
    public let maxGraphMemoryBytes: Int64
    public let maxTypeFields: Int
    public let maxTypeMetaBytes: Int
    public let maxSchemaVersionsPerType: Int
    public let maxAverageSchemaVersionsPerType: Int

    public init(
        trackRef: Bool = false,
        compatible: Bool? = nil,
        checkClassVersion: Bool? = nil,
        maxDepth: Int = 5,
        maxGraphMemoryBytes: Int64 = 128 * 1024 * 1024,
        maxTypeFields: Int = 512,
        maxTypeMetaBytes: Int = 4096,
        maxSchemaVersionsPerType: Int = 10,
        maxAverageSchemaVersionsPerType: Int = 3
    ) {
        precondition(maxTypeFields > 0, "maxTypeFields must be positive")
        precondition(maxTypeMetaBytes > 0, "maxTypeMetaBytes must be positive")
        precondition(maxSchemaVersionsPerType > 0, "maxSchemaVersionsPerType must be positive")
        precondition(
            maxAverageSchemaVersionsPerType > 0,
            "maxAverageSchemaVersionsPerType must be positive")
        precondition(
            maxGraphMemoryBytes > 0 && maxGraphMemoryBytes <= Int64(Int.max),
            "maxGraphMemoryBytes must be in range [1, \(Int64(Int.max))]")
        let effectiveCompatible = compatible ?? true
        let effectiveCheckClassVersion = checkClassVersion ?? !effectiveCompatible
        self.trackRef = trackRef
        self.compatible = effectiveCompatible
        self.checkClassVersion = effectiveCheckClassVersion
        self.maxDepth = maxDepth
        self.maxGraphMemoryBytes = maxGraphMemoryBytes
        self.maxTypeFields = maxTypeFields
        self.maxTypeMetaBytes = maxTypeMetaBytes
        self.maxSchemaVersionsPerType = maxSchemaVersionsPerType
        self.maxAverageSchemaVersionsPerType = maxAverageSchemaVersionsPerType
    }
}

/// Single-threaded Fory runtime.
///
/// Reuse one `Fory` per thread for the fastest path. The runtime keeps one
/// reusable read/write context pair and must not be used concurrently from
/// multiple threads.
public final class Fory {
    let typeResolver: TypeResolver
    private let writeContext: WriteContext
    private let readContext: ReadContext
    public let config: Config

    public convenience init(
        ref: Bool = false,
        compatible: Bool? = nil,
        checkClassVersion: Bool? = nil,
        maxDepth: Int = 5,
        maxGraphMemoryBytes: Int64 = 128 * 1024 * 1024,
        maxTypeFields: Int = 512,
        maxTypeMetaBytes: Int = 4096,
        maxSchemaVersionsPerType: Int = 10,
        maxAverageSchemaVersionsPerType: Int = 3
    ) {
        self.init(
            config: Config(
                trackRef: ref,
                compatible: compatible,
                checkClassVersion: checkClassVersion,
                maxDepth: maxDepth,
                maxGraphMemoryBytes: maxGraphMemoryBytes,
                maxTypeFields: maxTypeFields,
                maxTypeMetaBytes: maxTypeMetaBytes,
                maxSchemaVersionsPerType: maxSchemaVersionsPerType,
                maxAverageSchemaVersionsPerType: maxAverageSchemaVersionsPerType
            ))
    }

    public init(config: Config) {
        self.typeResolver = TypeResolver(trackRef: config.trackRef)
        self.writeContext = WriteContext(
            buffer: ByteBuffer(),
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            checkClassVersion: config.checkClassVersion,
            maxDepth: config.maxDepth,
            metaStringWriteState: MetaStringWriteState()
        )
        self.readContext = ReadContext(
            buffer: ByteBuffer(),
            typeResolver: typeResolver,
            config: config
        )
        self.config = config
    }

    /// Registers a user serializer by numeric type ID.
    public func register<T: Serializer>(_ type: T.Type, id: UInt32) throws {
        try typeResolver.register(type, id: id)
    }

    /// Registers a user type by name. The last `.` separates namespace from the final type name.
    public func register<T: Serializer>(_ type: T.Type, name: String) throws {
        try typeResolver.register(type, name: name)
    }

    public func serialize<T>(_ value: T) throws -> Data
    where T: Serializer, T.Target == T {
        try serializeRoot { context in
            try writeRootValue(value, with: T.self, context: context)
        }
    }

    public func deserialize<T>(_ data: Data, as _: T.Type = T.self) throws -> T
    where T: Serializer, T.Target == T {
        try deserializeRoot(
            data: data
        ) { context in
            try readRootValue(with: T.self, context: context)
        }
    }

    public func serialize<T>(_ value: T, to buffer: inout Data) throws
    where T: Serializer, T.Target == T {
        try appendSerializedRoot(to: &buffer) { context in
            try writeRootValue(value, with: T.self, context: context)
        }
    }

    public func deserialize<T>(from buffer: ByteBuffer, as _: T.Type = T.self) throws -> T
    where T: Serializer, T.Target == T {
        try deserializeRoot(
            from: buffer
        ) { context in
            try readRootValue(with: T.self, context: context)
        }
    }

    /// Serializes an `Any` root through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func serialize(_ value: Any) throws -> Data {
        try serialize(value, with: DynamicSerializer<Any>.self)
    }

    /// Deserializes an `Any` root through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func deserialize(_ data: Data, as _: Any.Type = Any.self) throws -> Any {
        try deserialize(data, with: DynamicSerializer<Any>.self)
    }

    /// Appends an `Any` root serialized through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func serialize(_ value: Any, to buffer: inout Data) throws {
        try serialize(value, with: DynamicSerializer<Any>.self, to: &buffer)
    }

    /// Deserializes an `Any` root from a byte buffer through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func deserialize(from buffer: ByteBuffer, as _: Any.Type = Any.self) throws -> Any {
        try deserialize(from: buffer, with: DynamicSerializer<Any>.self)
    }

    /// Serializes an `AnyObject` root through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func serialize(_ value: AnyObject) throws -> Data {
        try serialize(value, with: DynamicSerializer<AnyObject>.self)
    }

    /// Deserializes an `AnyObject` root through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func deserialize(
        _ data: Data,
        as _: AnyObject.Type = AnyObject.self
    ) throws -> AnyObject {
        try deserialize(data, with: DynamicSerializer<AnyObject>.self)
    }

    /// Appends an `AnyObject` root serialized through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func serialize(_ value: AnyObject, to buffer: inout Data) throws {
        try serialize(value, with: DynamicSerializer<AnyObject>.self, to: &buffer)
    }

    /// Deserializes an `AnyObject` root from a byte buffer through its concrete target serializer.
    @_disfavoredOverload
    @inlinable
    @inline(__always)
    public func deserialize(
        from buffer: ByteBuffer,
        as _: AnyObject.Type = AnyObject.self
    ) throws -> AnyObject {
        try deserialize(from: buffer, with: DynamicSerializer<AnyObject>.self)
    }

    /// Serializes a root value with an explicitly selected serializer.
    public func serialize<S: Serializer>(
        _ value: S.Target,
        with _: S.Type
    ) throws -> Data {
        try serializeRoot { context in
            try writeRootValue(value, with: S.self, context: context)
        }
    }

    /// Deserializes a root value with an explicitly selected serializer.
    public func deserialize<T, S: Serializer>(
        _ data: Data,
        with _: S.Type
    ) throws -> T where S.Target == T {
        try deserializeRoot(
            data: data
        ) { context in
            try readRootValue(with: S.self, context: context)
        }
    }

    /// Appends a root value serialized with an explicitly selected serializer.
    public func serialize<S: Serializer>(
        _ value: S.Target,
        with _: S.Type,
        to buffer: inout Data
    ) throws {
        try appendSerializedRoot(to: &buffer) { context in
            try writeRootValue(value, with: S.self, context: context)
        }
    }

    /// Deserializes a root value from a byte buffer with an explicitly selected serializer.
    public func deserialize<T, S: Serializer>(
        from buffer: ByteBuffer,
        with _: S.Type
    ) throws -> T where S.Target == T {
        try deserializeRoot(
            from: buffer
        ) { context in
            try readRootValue(with: S.self, context: context)
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

    @inline(__always)
    private func writeRootValue<S: Serializer>(
        _ value: S.Target,
        with _: S.Type,
        context: WriteContext
    ) throws {
        try S.write(
            value,
            context,
            refMode: refMode,
            writeTypeInfo: true
        )
    }

    @inline(__always)
    private func readRootValue<S: Serializer>(
        with _: S.Type,
        context: ReadContext
    ) throws -> S.Target {
        return try S.read(
            context,
            refMode: refMode,
            readTypeInfo: true
        )
    }

    @inline(__always)
    private func withReusableReadContext<R>(
        data: Data,
        _ body: (ReadContext) throws -> R
    ) throws -> R {
        readContext.buffer.replace(with: data)
        readContext.remainingGraphMemoryBytes = Int(self.config.maxGraphMemoryBytes)
        defer {
            readContext.reset()
        }
        return try body(readContext)
    }

    @inline(__always)
    private func serializeRoot(
        _ body: (WriteContext) throws -> Void
    ) throws -> Data {
        try typeResolver.finishRegistration()
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
        try typeResolver.finishRegistration()
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
        try typeResolver.finishRegistration()
        return try withReusableReadContext(data: data) { context in
            try readHead(buffer: context.buffer)
            let value = try body(context)
            if context.buffer.remaining != 0 {
                throw unexpectedTrailingBytes(context.buffer.remaining)
            }
            return value
        }
    }

    @inline(__always)
    private func deserializeRoot<R>(
        from buffer: ByteBuffer,
        _ body: (ReadContext) throws -> R
    ) throws -> R {
        try typeResolver.finishRegistration()
        readContext.buffer.swapState(with: buffer)
        readContext.remainingGraphMemoryBytes = Int(self.config.maxGraphMemoryBytes)
        defer {
            readContext.buffer.swapState(with: buffer)
            readContext.reset()
        }
        try readHead(buffer: readContext.buffer)
        return try body(readContext)
    }

    @inline(never)
    private func unexpectedTrailingBytes(_ remaining: Int) -> ForyError {
        ForyError.invalidData("unexpected trailing bytes at root: \(remaining)")
    }
}
