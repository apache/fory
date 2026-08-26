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

import Testing

@testable import Fory

@ForyStruct
private struct NativeIntegerFields: Equatable {
    var marker: String = ""
    var intValue: Int = 0
    var uintValue: UInt = 0

    @ForyField(encoding: .fixed)
    var fixedInt: Int = 0

    @ForyField(encoding: .fixed)
    var fixedUInt: UInt = 0

    @ForyField(encoding: .tagged)
    var taggedInt: Int = 0

    @ForyField(encoding: .tagged)
    var taggedUInt: UInt = 0

    @ArrayField(element: .encoding(.fixed))
    var intArray: [Int] = []

    @ArrayField(element: .encoding(.fixed))
    var uintArray: [UInt] = []
}

@Test
func nativeIntegersRoundTrip() throws {
    let fory = Fory(config: .init(trackRef: false))
    try fory.register(NativeIntegerFields.self, id: 9_901)

    for value in [Int.min, -1, 0, 1, Int.max] {
        let decoded: Int = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }
    for value in [0, 1, UInt.max] {
        let decoded: UInt = try fory.deserialize(try fory.serialize(value))
        #expect(decoded == value)
    }

    let value = NativeIntegerFields(
        marker: "native-width",
        intValue: Int.min,
        uintValue: UInt.max,
        fixedInt: Int.max,
        fixedUInt: UInt.max,
        taggedInt: Int.min,
        taggedUInt: UInt.max,
        intArray: [Int.min, 0, Int.max],
        uintArray: [0, UInt.max]
    )
    let decoded: NativeIntegerFields = try fory.deserialize(try fory.serialize(value))
    #expect(decoded == value)

    let compatible = Fory(config: .init(trackRef: false, compatible: true))
    try compatible.register(NativeIntegerFields.self, id: 9_901)
    let compatibleDecoded: NativeIntegerFields = try compatible.deserialize(
        try compatible.serialize(value))
    #expect(compatibleDecoded == value)
}

@Test
func nativeIntegerConversionChecksBounds() throws {
    #expect(try checkedInt64ToInt(Int64(Int.min)) == Int.min)
    #expect(try checkedInt64ToInt(Int64(Int.max)) == Int.max)
    #expect(try checkedUInt64ToUInt(UInt64(UInt.max)) == UInt.max)

    #if arch(arm64_32)
        #expect(throws: ForyError.invalidData("int64 value \(Int64.max) overflows Int")) {
            try checkedInt64ToInt(Int64.max)
        }
        #expect(throws: ForyError.invalidData("uint64 value \(UInt64.max) overflows UInt")) {
            try checkedUInt64ToUInt(UInt64.max)
        }
    #endif
}
