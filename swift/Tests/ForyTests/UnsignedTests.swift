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
import Testing
@testable import Fory

@ForyObject
private struct UnsignedFieldBundle: Equatable {
    var u8: UInt8 = 0
    var u16: UInt16 = 0
    var u32Var: UInt32 = 0
    var u32Fixed: ForyUInt32Fixed = .init()
    var u64Var: UInt64 = 0
    var u64Fixed: ForyUInt64Fixed = .init()
    var u64Tagged: ForyUInt64Tagged = .init()

    var u8Nullable: UInt8?
    var u16Nullable: UInt16?
    var u32VarNullable: UInt32?
    var u32FixedNullable: ForyUInt32Fixed?
    var u64VarNullable: UInt64?
    var u64FixedNullable: ForyUInt64Fixed?
    var u64TaggedNullable: ForyUInt64Tagged?
}

@Test
func unsignedPrimitiveRoundTripsCoverZeroMidpointAndMax() throws {
    let fory = Fory()

    let uint8Values: [UInt8] = [0, 127, 128, UInt8.max]
    let uint16Values: [UInt16] = [0, 32_767, 32_768, UInt16.max]
    let uint32Values: [UInt32] = [0, UInt32(Int32.max), UInt32(Int32.max) + 1, UInt32.max]
    let uint64Values: [UInt64] = [0, UInt64(Int64.max), UInt64(Int64.max) + 1, UInt64.max]

    for value in uint8Values {
        let decoded: UInt8 = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }
    for value in uint16Values {
        let decoded: UInt16 = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }
    for value in uint32Values {
        let decoded: UInt32 = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }
    for value in uint64Values {
        let decoded: UInt64 = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }
}

@Test
func unsignedEncodingWrappersPreserveExpectedWireWidths() throws {
    let fixed32 = ForyUInt32Fixed(rawValue: UInt32.max)
    let fixed32Context = WriteContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    try fixed32.foryWriteData(fixed32Context, hasGenerics: false)
    #expect(fixed32Context.buffer.count == 4)
    let fixed32Decoded = try ForyUInt32Fixed.foryReadData(
        ReadContext(buffer: fixed32Context.buffer, typeResolver: TypeResolver(trackRef: false), trackRef: false)
    )
    #expect(fixed32Decoded == fixed32)

    let fixed64 = ForyUInt64Fixed(rawValue: UInt64.max)
    let fixed64Context = WriteContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    try fixed64.foryWriteData(fixed64Context, hasGenerics: false)
    #expect(fixed64Context.buffer.count == 8)
    let fixed64Decoded = try ForyUInt64Fixed.foryReadData(
        ReadContext(buffer: fixed64Context.buffer, typeResolver: TypeResolver(trackRef: false), trackRef: false)
    )
    #expect(fixed64Decoded == fixed64)

    let compactTagged = ForyUInt64Tagged(rawValue: UInt64(Int32.max))
    let compactContext = WriteContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    try compactTagged.foryWriteData(compactContext, hasGenerics: false)
    #expect(compactContext.buffer.count == 4)
    let compactDecoded = try ForyUInt64Tagged.foryReadData(
        ReadContext(buffer: compactContext.buffer, typeResolver: TypeResolver(trackRef: false), trackRef: false)
    )
    #expect(compactDecoded == compactTagged)

    let wideTagged = ForyUInt64Tagged(rawValue: UInt64(Int32.max) + 1)
    let wideContext = WriteContext(
        buffer: ByteBuffer(),
        typeResolver: TypeResolver(trackRef: false),
        trackRef: false
    )
    try wideTagged.foryWriteData(wideContext, hasGenerics: false)
    #expect(wideContext.buffer.count == 9)
    let wideDecoded = try ForyUInt64Tagged.foryReadData(
        ReadContext(buffer: wideContext.buffer, typeResolver: TypeResolver(trackRef: false), trackRef: false)
    )
    #expect(wideDecoded == wideTagged)
}

@Test
func unsignedMacroFieldsRoundTripAcrossSchemaModes() throws {
    let cases = [
        UnsignedFieldBundle(
            u8: 0,
            u16: 0,
            u32Var: 0,
            u32Fixed: .init(rawValue: 0),
            u64Var: 0,
            u64Fixed: .init(rawValue: 0),
            u64Tagged: .init(rawValue: 0),
            u8Nullable: nil,
            u16Nullable: nil,
            u32VarNullable: nil,
            u32FixedNullable: nil,
            u64VarNullable: nil,
            u64FixedNullable: nil,
            u64TaggedNullable: nil
        ),
        UnsignedFieldBundle(
            u8: 128,
            u16: 32_768,
            u32Var: UInt32(Int32.max) + 1,
            u32Fixed: .init(rawValue: UInt32(Int32.max) + 1),
            u64Var: UInt64(Int64.max) + 1,
            u64Fixed: .init(rawValue: UInt64(Int64.max) + 1),
            u64Tagged: .init(rawValue: UInt64(Int32.max) + 1),
            u8Nullable: 128,
            u16Nullable: 32_768,
            u32VarNullable: UInt32(Int32.max) + 1,
            u32FixedNullable: .init(rawValue: UInt32(Int32.max) + 1),
            u64VarNullable: UInt64(Int64.max) + 1,
            u64FixedNullable: .init(rawValue: UInt64(Int64.max) + 1),
            u64TaggedNullable: .init(rawValue: UInt64(Int32.max) + 1)
        ),
        UnsignedFieldBundle(
            u8: UInt8.max,
            u16: UInt16.max,
            u32Var: UInt32.max,
            u32Fixed: .init(rawValue: UInt32.max),
            u64Var: UInt64.max,
            u64Fixed: .init(rawValue: UInt64.max),
            u64Tagged: .init(rawValue: UInt64.max),
            u8Nullable: UInt8.max,
            u16Nullable: UInt16.max,
            u32VarNullable: UInt32.max,
            u32FixedNullable: .init(rawValue: UInt32.max),
            u64VarNullable: UInt64.max,
            u64FixedNullable: .init(rawValue: UInt64.max),
            u64TaggedNullable: .init(rawValue: UInt64.max)
        )
    ]

    let schemaConsistent = Fory(config: .init(xlang: true, trackRef: false, compatible: false))
    schemaConsistent.register(UnsignedFieldBundle.self, id: 9801)

    let compatible = Fory(config: .init(xlang: true, trackRef: false, compatible: true))
    compatible.register(UnsignedFieldBundle.self, id: 9801)

    for value in cases {
        let decodedSchema: UnsignedFieldBundle = try schemaConsistent.deserialize(try schemaConsistent.serialize(value))
        let decodedCompatible: UnsignedFieldBundle = try compatible.deserialize(try compatible.serialize(value))
        #expect(decodedSchema == value)
        #expect(decodedCompatible == value)
    }
}
