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

@Test
func rowSlotEncode() throws {
    let slot = try rowEncodeOffsetAndSize(offset: 0x20, size: 2)
    #expect(slot == 0x0000_0020_0000_0002)
    #expect(withUnsafeBytes(of: slot.littleEndian, Array.init) == [0x02, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00])
}

@Test
func rowSlotDecode() {
    let ticket = rowDecodeOffsetAndSize(0x0000_0020_0000_0002)
    #expect(ticket.offset == 0x20)
    #expect(ticket.size == 2)

    let large = rowDecodeOffsetAndSize(0xFFFF_FFFF_0000_0001)
    #expect(large.offset == UInt32.max)
    #expect(large.size == 1)
}

@Test
func rowSlotEncodeLimits() throws {
    #expect(throws: ForyError.self) {
        try rowEncodeOffsetAndSize(offset: Int(UInt32.max) + 1, size: 0)
    }
    #expect(throws: ForyError.self) {
        try rowEncodeOffsetAndSize(offset: 0, size: -1)
    }
    let slot = try rowEncodeOffsetAndSize(offset: Int(UInt32.max), size: Int(UInt32.max))
    #expect(slot == UInt64.max)
}

@Test
func rowSlotCheckedRange() throws {
    #expect(try rowCheckedRange(offset: 8, size: 8, regionBase: 100, regionCount: 16) == 108..<116)
    #expect(try rowCheckedRange(offset: 0, size: 0, regionBase: 0, regionCount: 0).isEmpty)
    #expect(throws: ForyError.self) {
        try rowCheckedRange(offset: 8, size: 9, regionBase: 0, regionCount: 16)
    }
    #expect(throws: ForyError.self) {
        try rowCheckedRange(offset: UInt32.max, size: UInt32.max, regionBase: 0, regionCount: 16)
    }
}
