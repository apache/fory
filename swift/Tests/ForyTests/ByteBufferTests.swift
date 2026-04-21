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
func byteBufferPrimitiveReadWriteAndCursorOps() throws {
    let buffer = ByteBuffer(capacity: 8)
    buffer.writeUInt8(0xFE)
    buffer.writeInt8(-12)
    buffer.writeInt16(Int16.max)
    buffer.writeInt32(Int32.min)
    buffer.writeInt64(Int64.max)
    buffer.writeFloat32(3.25)
    buffer.writeFloat64(-123.5)
    buffer.writeBytes([0x01, 0x02, 0x03, 0x04])

    #expect(buffer.count == 32)
    #expect(buffer.getCursor() == 0)

    #expect(try buffer.readUInt8() == 0xFE)
    #expect(try buffer.readInt8() == -12)
    #expect(try buffer.readInt16() == Int16.max)

    let int32Offset = buffer.getCursor()
    #expect(try buffer.readInt32() == Int32.min)
    buffer.moveBack(4)
    #expect(buffer.getCursor() == int32Offset)
    #expect(try buffer.readInt32() == Int32.min)

    #expect(try buffer.readInt64() == Int64.max)
    #expect(try buffer.readFloat32() == 3.25)
    #expect(try buffer.readFloat64() == -123.5)
    #expect(try buffer.readBytes(count: 4) == [0x01, 0x02, 0x03, 0x04])
    #expect(buffer.remaining == 0)
}

@Test
func byteBufferReplaceMutationAndSnapshots() throws {
    let buffer = ByteBuffer(bytes: [0x01, 0x02, 0x03, 0x04])
    _ = try buffer.readUInt8()
    _ = try buffer.readUInt8()
    #expect(buffer.getCursor() == 2)

    buffer.replace(with: Data([0x10, 0x11, 0x12]))
    #expect(buffer.getCursor() == 0)
    #expect(buffer.count == 3)
    #expect(Array(buffer.toData()) == [0x10, 0x11, 0x12])

    let bridged = Array(buffer.copyToData())
    buffer.setByte(at: 1, to: 0xFE)
    buffer.setBytes(at: 0, to: [0xAA, 0xBB])

    #expect(buffer.storage == [0xAA, 0xBB, 0x12])
    #expect(bridged == [0x10, 0x11, 0x12])

    buffer.setCursor(buffer.count)
    buffer.flip()
    #expect(buffer.getCursor() == 0)

    buffer.clear()
    #expect(buffer.count == 0)
    #expect(buffer.remaining == 0)

    buffer.writeBytes([0x21, 0x22])
    buffer.reset()
    #expect(buffer.count == 0)
    #expect(buffer.getCursor() == 0)
}

@Test
func byteBufferVarUIntBoundariesUseExpectedSizes() throws {
    let values32: [UInt32] = [
        0,
        1,
        1 << 6,
        1 << 7,
        1 << 13,
        1 << 14,
        1 << 20,
        1 << 21,
        1 << 27,
        1 << 28,
        UInt32.max
    ]
    let values64: [UInt64] = [
        0,
        1,
        1 << 6,
        1 << 7,
        1 << 13,
        1 << 14,
        1 << 20,
        1 << 21,
        1 << 27,
        1 << 28,
        1 << 35,
        1 << 42,
        1 << 49,
        1 << 56,
        UInt64.max
    ]

    let buffer = ByteBuffer()
    for value in values32 {
        let start = buffer.count
        buffer.writeVarUInt32(value)
        #expect(buffer.count - start == UnsafeUtil.varUInt32Size(value))
    }
    for value in values64 {
        let start = buffer.count
        buffer.writeVarUInt64(value)
        #expect(buffer.count - start == UnsafeUtil.varUInt64Size(value))
    }

    for value in values32 {
        #expect(try buffer.readVarUInt32() == value)
    }
    for value in values64 {
        #expect(try buffer.readVarUInt64() == value)
    }
    #expect(buffer.remaining == 0)
}

@Test
func byteBufferVarIntAndTaggedIntegerBoundariesRoundTrip() throws {
    let int32Values: [Int32] = [Int32.min, -1_000_000, -1, 0, 1, 127, Int32.max]
    let int64Values: [Int64] = [Int64.min, -1_000_000_000_000, -1, 0, 1, 127, Int64.max]
    let taggedIntValues: [(Int64, Int)] = [
        (-1_073_741_824, 4),
        (-1_073_741_823, 4),
        (-1, 4),
        (0, 4),
        (1_073_741_823, 4),
        (1_073_741_824, 9),
        (Int64.min, 9),
        (Int64.max, 9)
    ]
    let taggedUIntValues: [(UInt64, Int)] = [
        (0, 4),
        (1, 4),
        (UInt64(Int32.max), 4),
        (UInt64(Int32.max) + 1, 9),
        (UInt64.max, 9)
    ]

    let buffer = ByteBuffer()
    for value in int32Values {
        buffer.writeVarInt32(value)
    }
    for value in int64Values {
        buffer.writeVarInt64(value)
    }
    for (value, encodedWidth) in taggedIntValues {
        let start = buffer.count
        buffer.writeTaggedInt64(value)
        #expect(buffer.count - start == encodedWidth)
    }
    for (value, encodedWidth) in taggedUIntValues {
        let start = buffer.count
        buffer.writeTaggedUInt64(value)
        #expect(buffer.count - start == encodedWidth)
    }

    for value in int32Values {
        #expect(try buffer.readVarInt32() == value)
    }
    for value in int64Values {
        #expect(try buffer.readVarInt64() == value)
    }
    for (value, _) in taggedIntValues {
        #expect(try buffer.readTaggedInt64() == value)
    }
    for (value, _) in taggedUIntValues {
        #expect(try buffer.readTaggedUInt64() == value)
    }
    #expect(buffer.remaining == 0)
}

@Test
func byteBufferRejectsInvalidVarintsAndUtf8() throws {
    let varUInt32Overflow = ByteBuffer(bytes: [0x80, 0x80, 0x80, 0x80, 0x80])
    do {
        _ = try varUInt32Overflow.readVarUInt32()
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("varuint32 overflow"))
    }

    let truncatedVarUInt64 = ByteBuffer(bytes: [0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80])
    do {
        _ = try truncatedVarUInt64.readVarUInt64()
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("out of bounds"))
    }

    let varUInt36Overflow = ByteBuffer()
    varUInt36Overflow.writeVarUInt64(1 << 36)
    do {
        _ = try varUInt36Overflow.readVarUInt36Small()
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("varuint36small overflow"))
    }

    let invalidUTF8 = ByteBuffer(bytes: [0xC3, 0x28])
    do {
        _ = try invalidUTF8.readUTF8String(count: 2)
        #expect(Bool(false))
    } catch {
        #expect("\(error)".contains("invalid UTF-8"))
    }
}
