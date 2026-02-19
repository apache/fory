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
        try typeResolver.register(type, namespace: "", typeName: name)
    }

    public func register<T: Serializer>(_ type: T.Type, namespace: String, name: String) throws {
        try typeResolver.register(type, namespace: namespace, typeName: name)
    }

    public func serialize<T: Serializer>(_ value: T) throws -> Data {
        let writer = ByteWriter()
        writeHead(writer: writer, isNone: value.foryIsNone)

        if !value.foryIsNone {
            let compatibleTypeDefState = CompatibleTypeDefWriteState()
            let context = WriteContext(
                writer: writer,
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

        return writer.toData()
    }

    public func deserialize<T: Serializer>(_ data: Data, as _: T.Type = T.self) throws -> T {
        let reader = ByteReader(data: data)
        let isNone = try readHead(reader: reader)
        if isNone {
            return T.foryDefault()
        }

        let context = ReadContext(
            reader: reader,
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

    public func serializeTo<T: Serializer>(_ buffer: inout Data, value: T) throws {
        let writer = ByteWriter()
        writeHead(writer: writer, isNone: value.foryIsNone)
        if !value.foryIsNone {
            let context = WriteContext(
                writer: writer,
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
        buffer.append(writer.toData())
    }

    public func deserializeFrom<T: Serializer>(_ reader: ByteReader, as _: T.Type = T.self) throws -> T {
        let isNone = try readHead(reader: reader)
        if isNone {
            return T.foryDefault()
        }
        let context = ReadContext(
            reader: reader,
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

    public func writeHead(writer: ByteWriter, isNone: Bool) {
        var bitmap: UInt8 = 0
        if config.xlang {
            bitmap |= ForyHeaderFlag.isXlang
        }
        if isNone {
            bitmap |= ForyHeaderFlag.isNull
        }
        writer.writeUInt8(bitmap)
    }

    public func readHead(reader: ByteReader) throws -> Bool {
        let bitmap = try reader.readUInt8()
        let peerIsXlang = (bitmap & ForyHeaderFlag.isXlang) != 0
        if peerIsXlang != config.xlang {
            throw ForyError.invalidData("xlang bitmap mismatch")
        }
        return (bitmap & ForyHeaderFlag.isNull) != 0
    }
}
