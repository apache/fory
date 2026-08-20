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

@ForyStruct
private struct DecimalEnvelope: Equatable {
    var amount: Decimal = .zero
    var note: String = ""
}

private func decimalWireData(
    scale: Int32,
    header: UInt64,
    magnitude: [UInt8] = []
) -> Data {
    let buffer = ByteBuffer()
    buffer.writeUInt8(ForyHeaderFlag.isXlang)
    buffer.writeInt8(RefFlag.notNullValue.rawValue)
    buffer.writeUInt8(UInt8(TypeId.decimal.rawValue))
    buffer.writeVarInt32(scale)
    buffer.writeVarUInt64(header)
    buffer.writeBytes(magnitude)
    return buffer.toData()
}

private func bigDecimalHeader(length: Int, negative: Bool = false) -> UInt64 {
    let sign: UInt64 = negative ? 1 : 0
    let meta = (UInt64(length) << 1) | sign
    return (meta << 1) | 1
}

private func makeDecimal(unscaled: String, scale: Int32) throws -> Decimal {
    var digits = unscaled
    var sign = ""
    if digits.first == "-" {
        sign = "-"
        digits.removeFirst()
    }
    guard !digits.isEmpty, digits.allSatisfy(\.isNumber) else {
        throw ForyError.invalidData("failed to create decimal \(unscaled) scale \(scale)")
    }
    if scale == 0 {
        guard let value = Decimal(string: sign + digits, locale: Locale(identifier: "en_US_POSIX"))
        else {
            throw ForyError.invalidData("failed to create decimal \(unscaled) scale \(scale)")
        }
        return value
    }
    let valueString: String
    if scale > 0 {
        let scaleInt = Int(scale)
        if digits.count > scaleInt {
            let split = digits.index(digits.endIndex, offsetBy: -scaleInt)
            valueString = sign + String(digits[..<split]) + "." + String(digits[split...])
        } else {
            let padding = String(repeating: "0", count: scaleInt - digits.count)
            valueString = sign + "0." + padding + digits
        }
    } else {
        valueString = sign + digits + String(repeating: "0", count: Int(-scale))
    }
    guard let value = Decimal(string: valueString, locale: Locale(identifier: "en_US_POSIX")) else {
        throw ForyError.invalidData("failed to create decimal \(unscaled) scale \(scale)")
    }
    return value
}

private let decimalCases: [(unscaled: String, scale: Int32)] = [
    ("0", 0),
    ("0", 3),
    ("1", 0),
    ("-1", 0),
    ("12345", 2),
    ("9223372036854775807", 0),
    ("-9223372036854775808", 0),
    ("4611686018427387903", 0),
    ("-4611686018427387904", 0),
    ("9223372036854775808", 0),
    ("-9223372036854775809", 0),
    ("123456789012345678901234567890123456789", 37),
    ("-123456789012345678901234567890123456789", -17)
]

@Test
func decimalRoundTripPreservesUnscaledValueAndScale() throws {
    let fory = Fory()
    #expect(Decimal.staticTypeId == .decimal)

    for testCase in decimalCases {
        let value = try makeDecimal(unscaled: testCase.unscaled, scale: testCase.scale)
        let encoded = try fory.serialize(value)
        let decoded: Decimal = try fory.deserialize(encoded)
        #expect(decoded.foryScale == testCase.scale)
        #expect(decoded.foryUnscaledString == testCase.unscaled)
    }
}

@Test
func decimalFieldRoundTripUsesGeneratedSerializerMetadata() throws {
    let fory = Fory()
    try fory.register(DecimalEnvelope.self, id: 240)

    let expected = try makeDecimal(
        unscaled: "123456789012345678901234567890123456789",
        scale: 37
    )
    let envelope = DecimalEnvelope(amount: expected, note: "principal")
    let encoded = try fory.serialize(envelope)
    let decoded: DecimalEnvelope = try fory.deserialize(encoded)

    #expect(decoded.note == envelope.note)
    #expect(decoded.amount.foryScale == 37)
    #expect(decoded.amount.foryUnscaledString == "123456789012345678901234567890123456789")
}

@Test
func decimalRejectsNonCanonicalBigPayloads() throws {
    let fory = Fory()
    let zeroBigEncoding = Data([0x01, 0xff, 0x28, 0x00, 0x01])
    let trailingZeroPayload = Data([0x01, 0xff, 0x28, 0x00, 0x09, 0x01, 0x00])

    #expect(throws: ForyError.self) {
        let _: Decimal = try fory.deserialize(zeroBigEncoding)
    }
    #expect(throws: ForyError.self) {
        let _: Decimal = try fory.deserialize(trailingZeroPayload)
    }
}

@Test
func decimalWriterUsesBinaryMagnitudeOrder() throws {
    let fory = Fory()

    let positive = try fory.serialize(Decimal(UInt64.max))
    #expect(
        positive
            == decimalWireData(
                scale: 0,
                header: bigDecimalHeader(length: 8),
                magnitude: Array(repeating: 0xff, count: 8)
            )
    )

    let negative = try fory.serialize(Decimal(Int64.min))
    #expect(
        negative
            == decimalWireData(
                scale: 0,
                header: bigDecimalHeader(length: 8, negative: true),
                magnitude: [0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x80]
            )
    )
}

@Test
func decimalReaderChecksNativeMagnitude() throws {
    let fory = Fory()
    let boundaryMagnitude = Array(repeating: UInt8(0), count: 15) + [0x01]
    let boundaryWire = decimalWireData(
        scale: 0,
        header: bigDecimalHeader(length: boundaryMagnitude.count),
        magnitude: boundaryMagnitude
    )

    let decoded: Decimal = try fory.deserialize(boundaryWire)
    #expect(decoded.foryScale == 0)
    #expect(try fory.serialize(decoded) == boundaryWire)

    let oversizedMagnitude = Array(repeating: UInt8(1), count: 17)
    let oversizedBuffer = ByteBuffer(
        data: decimalWireData(
            scale: 0,
            header: bigDecimalHeader(length: oversizedMagnitude.count),
            magnitude: oversizedMagnitude
        )
    )
    #expect(throws: ForyError.self) {
        let _: Decimal = try fory.deserialize(from: oversizedBuffer)
    }
    #expect(oversizedBuffer.remaining == oversizedMagnitude.count)

    let overflowBuffer = ByteBuffer(
        data: decimalWireData(scale: 0, header: UInt64.max)
    )
    #expect(throws: ForyError.self) {
        let _: Decimal = try fory.deserialize(from: overflowBuffer)
    }

    let legalLength = 16
    let truncatedMagnitude = Array(repeating: UInt8(1), count: legalLength - 1)
    let truncatedBuffer = ByteBuffer(
        data: decimalWireData(
            scale: 0,
            header: bigDecimalHeader(length: legalLength),
            magnitude: truncatedMagnitude
        )
    )
    #expect(throws: ForyError.self) {
        let _: Decimal = try fory.deserialize(from: truncatedBuffer)
    }
    #expect(truncatedBuffer.remaining == truncatedMagnitude.count)
}

@Test
func decimalReaderUsesFoundationScaleRange() throws {
    let fory = Fory()
    let nativeCases: [(value: Decimal, scale: Int32)] = [
        (Decimal(sign: .plus, exponent: -128, significand: Decimal(1)), 128),
        (Decimal(sign: .plus, exponent: 127, significand: Decimal(1)), -127)
    ]

    for testCase in nativeCases {
        let encoded = try fory.serialize(testCase.value)
        let decoded: Decimal = try fory.deserialize(encoded)
        #expect(decoded == testCase.value)
        #expect(decoded.foryScale == testCase.scale)
    }

    for scale in [Int32(-128), 129, -10_001, -10_000, 10_000, 10_001, .min, .max] {
        #expect(throws: ForyError.self) {
            let _: Decimal = try fory.deserialize(
                decimalWireData(scale: scale, header: 0x04)
            )
        }
    }
}
