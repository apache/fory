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
func rowBitmapWidths() {
    let cases: [(count: Int, width: Int)] = [
        (0, 0), (1, 8), (63, 8), (64, 8), (65, 16), (128, 16), (129, 24)
    ]
    for (count, width) in cases {
        #expect(rowBitmapWidth(count) == width)
    }
}

@Test
func rowBitmapWordRounding() {
    let cases: [(size: Int, rounded: Int)] = [(0, 0), (1, 8), (7, 8), (8, 8), (9, 16)]
    for (size, rounded) in cases {
        #expect(rowRoundToWord(size) == rounded)
    }
}

@Test
func rowBitmapBits() {
    let nullIndexes: Set<Int> = [0, 7, 8, 63, 64]
    var bytes = [UInt8](repeating: 0, count: 24)
    bytes.withUnsafeMutableBytes { raw in
        for index in nullIndexes {
            rowSetBit(raw, bitmapOffset: 8, index: index)
        }
    }

    var expected = [UInt8](repeating: 0, count: 24)
    expected[8] = 0x81
    expected[9] = 0x01
    expected[15] = 0x80
    expected[16] = 0x01
    #expect(bytes == expected)

    bytes.withUnsafeBytes { raw in
        for index in 0..<72 {
            #expect(rowIsBitSet(raw, bitmapOffset: 8, index: index) == nullIndexes.contains(index))
        }
    }
}
