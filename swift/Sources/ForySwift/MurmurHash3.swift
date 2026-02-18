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

enum MurmurHash3 {
    static func x64_128(_ bytes: [UInt8], seed: UInt64 = 47) -> (UInt64, UInt64) {
        let c1: UInt64 = 0x87c37b91114253d5
        let c2: UInt64 = 0x4cf5ad432745937f

        var h1 = seed
        var h2 = seed

        let length = bytes.count
        let nblocks = length / 16

        if nblocks > 0 {
            for i in 0..<nblocks {
                let base = i * 16
                var k1 = readUInt64LE(bytes, offset: base)
                var k2 = readUInt64LE(bytes, offset: base + 8)

                k1 &*= c1
                k1 = rotl64(k1, 31)
                k1 &*= c2
                h1 ^= k1

                h1 = rotl64(h1, 27)
                h1 &+= h2
                h1 = h1 &* 5 &+ 0x52dce729

                k2 &*= c2
                k2 = rotl64(k2, 33)
                k2 &*= c1
                h2 ^= k2

                h2 = rotl64(h2, 31)
                h2 &+= h1
                h2 = h2 &* 5 &+ 0x38495ab5
            }
        }

        let tailStart = nblocks * 16
        var k1: UInt64 = 0
        var k2: UInt64 = 0

        switch length & 15 {
        case 15:
            k2 ^= UInt64(bytes[tailStart + 14]) << 48
            fallthrough
        case 14:
            k2 ^= UInt64(bytes[tailStart + 13]) << 40
            fallthrough
        case 13:
            k2 ^= UInt64(bytes[tailStart + 12]) << 32
            fallthrough
        case 12:
            k2 ^= UInt64(bytes[tailStart + 11]) << 24
            fallthrough
        case 11:
            k2 ^= UInt64(bytes[tailStart + 10]) << 16
            fallthrough
        case 10:
            k2 ^= UInt64(bytes[tailStart + 9]) << 8
            fallthrough
        case 9:
            k2 ^= UInt64(bytes[tailStart + 8])
            k2 &*= c2
            k2 = rotl64(k2, 33)
            k2 &*= c1
            h2 ^= k2
            fallthrough
        case 8:
            k1 ^= UInt64(bytes[tailStart + 7]) << 56
            fallthrough
        case 7:
            k1 ^= UInt64(bytes[tailStart + 6]) << 48
            fallthrough
        case 6:
            k1 ^= UInt64(bytes[tailStart + 5]) << 40
            fallthrough
        case 5:
            k1 ^= UInt64(bytes[tailStart + 4]) << 32
            fallthrough
        case 4:
            k1 ^= UInt64(bytes[tailStart + 3]) << 24
            fallthrough
        case 3:
            k1 ^= UInt64(bytes[tailStart + 2]) << 16
            fallthrough
        case 2:
            k1 ^= UInt64(bytes[tailStart + 1]) << 8
            fallthrough
        case 1:
            k1 ^= UInt64(bytes[tailStart])
            k1 &*= c1
            k1 = rotl64(k1, 31)
            k1 &*= c2
            h1 ^= k1
        default:
            break
        }

        h1 ^= UInt64(length)
        h2 ^= UInt64(length)

        h1 &+= h2
        h2 &+= h1

        h1 = fmix64(h1)
        h2 = fmix64(h2)

        h1 &+= h2
        h2 &+= h1

        return (h1, h2)
    }

    private static func readUInt64LE(_ bytes: [UInt8], offset: Int) -> UInt64 {
        var value = UInt64(bytes[offset])
        value |= UInt64(bytes[offset + 1]) << 8
        value |= UInt64(bytes[offset + 2]) << 16
        value |= UInt64(bytes[offset + 3]) << 24
        value |= UInt64(bytes[offset + 4]) << 32
        value |= UInt64(bytes[offset + 5]) << 40
        value |= UInt64(bytes[offset + 6]) << 48
        value |= UInt64(bytes[offset + 7]) << 56
        return value
    }

    private static func rotl64(_ x: UInt64, _ r: UInt64) -> UInt64 {
        (x << r) | (x >> (64 - r))
    }

    private static func fmix64(_ value: UInt64) -> UInt64 {
        var x = value
        x ^= x >> 33
        x &*= 0xff51afd7ed558ccd
        x ^= x >> 33
        x &*= 0xc4ceb9fe1a85ec53
        x ^= x >> 33
        return x
    }
}
