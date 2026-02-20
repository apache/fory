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

    public init(xlang: Bool = true, trackRef: Bool = false, compatible: Bool = false) {
        self.xlang = xlang
        self.trackRef = trackRef
        self.compatible = compatible
    }
}

public final class Fory {
    public let config: ForyConfig
    public let typeResolver: TypeResolver

    public init(config: ForyConfig = ForyConfig()) {
        self.config = config
        self.typeResolver = TypeResolver()
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
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: value.foryIsNone)

        if !value.foryIsNone {
            let compatibleTypeDefState = CompatibleTypeDefWriteState()
            let context = WriteContext(
                buffer: byteBuffer,
                typeResolver: typeResolver,
                trackRef: config.trackRef,
                compatible: config.compatible,
                compatibleTypeDefState: compatibleTypeDefState,
                metaStringWriteState: MetaStringWriteState()
            )
            let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
            try value.foryWrite(context, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
            context.resetObjectState()
        }

        return byteBuffer.toData()
    }

    public func deserialize<T: Serializer>(_ data: Data, as _: T.Type = T.self) throws -> T {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return T.foryDefault()
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try T.foryRead(context, refMode: refMode, readTypeInfo: true)
        context.resetObjectState()
        return value
    }

    public func serialize<T: Serializer>(_ value: T, to buffer: inout Data) throws {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: value.foryIsNone)
        if !value.foryIsNone {
            let context = WriteContext(
                buffer: byteBuffer,
                typeResolver: typeResolver,
                trackRef: config.trackRef,
                compatible: config.compatible,
                compatibleTypeDefState: CompatibleTypeDefWriteState(),
                metaStringWriteState: MetaStringWriteState()
            )
            let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
            try value.foryWrite(context, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
            context.resetObjectState()
        }
        buffer.append(byteBuffer.toData())
    }

    public func deserialize<T: Serializer>(from buffer: ByteBuffer, as _: T.Type = T.self) throws -> T {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return T.foryDefault()
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try T.foryRead(context, refMode: refMode, readTypeInfo: true)
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: Any) throws -> Data {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: false)

        let context = WriteContext(
            buffer: byteBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        context.resetObjectState()
        return byteBuffer.toData()
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: Any.Type = Any.self) throws -> Any {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return ForyAnyNullValue()
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try castAnyDynamicValue(
            context.readAny(refMode: refMode, readTypeInfo: true),
            to: Any.self
        )
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: AnyObject) throws -> Data {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: false)

        let context = WriteContext(
            buffer: byteBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        context.resetObjectState()
        return byteBuffer.toData()
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: AnyObject.Type = AnyObject.self) throws -> AnyObject {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return NSNull()
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try castAnyDynamicValue(
            context.readAny(refMode: refMode, readTypeInfo: true),
            to: AnyObject.self
        )
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: any Serializer) throws -> Data {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: false)

        let context = WriteContext(
            buffer: byteBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        try context.writeAny(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        context.resetObjectState()
        return byteBuffer.toData()
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: (any Serializer).Type = (any Serializer).self) throws -> any Serializer {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return ForyAnyNullValue()
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try castAnyDynamicValue(
            context.readAny(refMode: refMode, readTypeInfo: true),
            to: (any Serializer).self
        )
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: [Any]) throws -> Data {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: false)

        let context = WriteContext(
            buffer: byteBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        try context.writeAnyList(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        context.resetObjectState()
        return byteBuffer.toData()
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [Any].Type = [Any].self) throws -> [Any] {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return []
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try context.readAnyList(refMode: refMode, readTypeInfo: true) ?? []
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: [String: Any]) throws -> Data {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: false)

        let context = WriteContext(
            buffer: byteBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        try context.writeStringAnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        context.resetObjectState()
        return byteBuffer.toData()
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [String: Any].Type = [String: Any].self) throws -> [String: Any] {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return [:]
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try context.readStringAnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: [Int32: Any]) throws -> Data {
        let byteBuffer = ByteBuffer()
        writeHead(buffer: byteBuffer, isNone: false)

        let context = WriteContext(
            buffer: byteBuffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefWriteState(),
            metaStringWriteState: MetaStringWriteState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        try context.writeInt32AnyMap(value, refMode: refMode, writeTypeInfo: true, hasGenerics: false)
        context.resetObjectState()
        return byteBuffer.toData()
    }

    @_disfavoredOverload
    public func deserialize(_ data: Data, as _: [Int32: Any].Type = [Int32: Any].self) throws -> [Int32: Any] {
        let buffer = ByteBuffer(data: data)
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return [:]
        }

        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try context.readInt32AnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: [Any], to buffer: inout Data) throws {
        buffer.append(try serialize(value))
    }

    @_disfavoredOverload
    public func serialize(_ value: Any, to buffer: inout Data) throws {
        buffer.append(try serialize(value))
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: Any.Type = Any.self) throws -> Any {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return ForyAnyNullValue()
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try castAnyDynamicValue(
            context.readAny(refMode: refMode, readTypeInfo: true),
            to: Any.self
        )
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: AnyObject, to buffer: inout Data) throws {
        buffer.append(try serialize(value))
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: AnyObject.Type = AnyObject.self) throws -> AnyObject {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return NSNull()
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try castAnyDynamicValue(
            context.readAny(refMode: refMode, readTypeInfo: true),
            to: AnyObject.self
        )
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: any Serializer, to buffer: inout Data) throws {
        buffer.append(try serialize(value))
    }

    @_disfavoredOverload
    public func deserialize(
        from buffer: ByteBuffer,
        as _: (any Serializer).Type = (any Serializer).self
    ) throws -> any Serializer {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return ForyAnyNullValue()
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try castAnyDynamicValue(
            context.readAny(refMode: refMode, readTypeInfo: true),
            to: (any Serializer).self
        )
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: [Any].Type = [Any].self) throws -> [Any] {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return []
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try context.readAnyList(refMode: refMode, readTypeInfo: true) ?? []
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: [String: Any], to buffer: inout Data) throws {
        buffer.append(try serialize(value))
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: [String: Any].Type = [String: Any].self) throws -> [String: Any] {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return [:]
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try context.readStringAnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        context.resetObjectState()
        return value
    }

    @_disfavoredOverload
    public func serialize(_ value: [Int32: Any], to buffer: inout Data) throws {
        buffer.append(try serialize(value))
    }

    @_disfavoredOverload
    public func deserialize(from buffer: ByteBuffer, as _: [Int32: Any].Type = [Int32: Any].self) throws -> [Int32: Any] {
        let isNone = try readHead(buffer: buffer)
        if isNone {
            return [:]
        }
        let context = ReadContext(
            buffer: buffer,
            typeResolver: typeResolver,
            trackRef: config.trackRef,
            compatible: config.compatible,
            compatibleTypeDefState: CompatibleTypeDefReadState(),
            metaStringReadState: MetaStringReadState()
        )
        let refMode: RefMode = config.trackRef ? .tracking : .nullOnly
        let value = try context.readInt32AnyMap(refMode: refMode, readTypeInfo: true) ?? [:]
        context.resetObjectState()
        return value
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
}
