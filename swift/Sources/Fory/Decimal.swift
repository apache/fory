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

private let decimalSmallPositiveMax: UInt64 = 0x3fff_ffff_ffff_ffff
private let decimalSmallNegativeAbsMax: UInt64 = 0x4000_0000_0000_0000
private let decimalLengthMask: UInt8 = 0x0f
private let decimalNegativeMask: UInt8 = 0x10
private let decimalCompactMask: UInt8 = 0x20
private let decimalHeaderSize = 4
private let decimalMaxMantissaWords = 8
private let decimalMaxMagnitudeBytes = decimalMaxMantissaWords * 2

private struct FoundationDecimalWireState {
    let scale: Int32
    let signum: Int8
    let magnitude: [UInt8]
}

@inline(__always)
private func normalizeDecimalMagnitude(_ magnitude: [UInt8]) -> [UInt8] {
    var normalized = magnitude
    while let last = normalized.last, last == 0 {
        normalized.removeLast()
    }
    return normalized
}

@inline(__always)
private func decimalMagnitudeBytes(from value: UInt64) -> [UInt8] {
    guard value != 0 else {
        return []
    }
    var remaining = value
    var bytes: [UInt8] = []
    bytes.reserveCapacity(8)
    while remaining != 0 {
        bytes.append(UInt8(truncatingIfNeeded: remaining))
        remaining >>= 8
    }
    return bytes
}

@inline(__always)
private func decimalUInt64(from magnitude: [UInt8]) -> UInt64? {
    guard magnitude.count <= 8 else {
        return nil
    }
    var result: UInt64 = 0
    for (index, byte) in magnitude.enumerated() {
        result |= UInt64(byte) << (index * 8)
    }
    return result
}

private func divideDecimalMagnitudeBy10(_ magnitude: inout [UInt8]) -> UInt8 {
    guard !magnitude.isEmpty else {
        return 0
    }
    var remainder = 0
    for index in stride(from: magnitude.count - 1, through: 0, by: -1) {
        let value = remainder * 256 + Int(magnitude[index])
        magnitude[index] = UInt8(value / 10)
        remainder = value % 10
    }
    magnitude = normalizeDecimalMagnitude(magnitude)
    return UInt8(remainder)
}

private func decimalMagnitudeString(signum: Int8, magnitude: [UInt8]) -> String {
    let normalized = normalizeDecimalMagnitude(magnitude)
    guard !normalized.isEmpty else {
        return "0"
    }

    var remainderMagnitude = normalized
    var digits: [Character] = []
    while !remainderMagnitude.isEmpty {
        let digit = divideDecimalMagnitudeBy10(&remainderMagnitude)
        digits.append(Character(String(UnicodeScalar(48 + Int(digit))!)))
    }
    let decimalDigits = String(digits.reversed())
    return signum < 0 ? "-\(decimalDigits)" : decimalDigits
}

@inline(__always)
private func encodeDecimalZigZag64(_ value: Int64) -> UInt64 {
    UInt64(bitPattern: (value << 1) ^ (value >> 63))
}

@inline(__always)
private func decodeDecimalZigZag64(_ value: UInt64) -> Int64 {
    let shifted = Int64(bitPattern: value >> 1)
    let mask = -Int64(value & 1)
    return shifted ^ mask
}

private func decimalMantissaWords(from magnitude: [UInt8]) throws -> (words: [UInt16], length: Int) {
    let normalized = normalizeDecimalMagnitude(magnitude)
    guard normalized.count <= decimalMaxMagnitudeBytes else {
        throw ForyError.invalidData(
            "decimal magnitude with \(normalized.count) bytes exceeds Foundation.Decimal precision"
        )
    }
    var words = Array(repeating: UInt16(0), count: decimalMaxMantissaWords)
    for (index, byte) in normalized.enumerated() {
        let wordIndex = index / 2
        let shift = (index % 2) * 8
        words[wordIndex] |= UInt16(byte) << shift
    }
    var length = words.count
    while length > 0, words[length - 1] == 0 {
        length -= 1
    }
    return (words, length)
}

private func foundationDecimalExponent(forScale scale: Int32) throws -> Int8 {
    let exponent = 0 - Int64(scale)
    guard exponent >= Int64(Int8.min), exponent <= Int64(Int8.max) else {
        throw ForyError.invalidData(
            "decimal scale \(scale) is out of Foundation.Decimal exponent range"
        )
    }
    return Int8(exponent)
}

private func foundationDecimalWireState(_ value: Decimal) -> FoundationDecimalWireState {
    var compact = value
    NSDecimalCompact(&compact)
    return withUnsafeBytes(of: &compact) { raw in
        let exponent = Int8(bitPattern: raw[0])
        let flags = raw[1]
        let length = min(Int(flags & decimalLengthMask), decimalMaxMantissaWords)
        let isNegative = (flags & decimalNegativeMask) != 0

        var magnitude: [UInt8] = []
        magnitude.reserveCapacity(length * 2)
        for index in 0..<length {
            let offset = decimalHeaderSize + index * 2
            magnitude.append(raw[offset])
            magnitude.append(raw[offset + 1])
        }
        let normalized = normalizeDecimalMagnitude(magnitude)
        return FoundationDecimalWireState(
            scale: Int32(0) - Int32(exponent),
            signum: normalized.isEmpty ? 0 : (isNegative ? -1 : 1),
            magnitude: normalized
        )
    }
}

private func buildFoundationDecimal(
    signum: Int8,
    magnitude: [UInt8],
    scale: Int32
) throws -> Decimal {
    let normalized = normalizeDecimalMagnitude(magnitude)
    let exponent = try foundationDecimalExponent(forScale: scale)
    let mantissa = try decimalMantissaWords(from: normalized)

    var value = Decimal.zero
    withUnsafeMutableBytes(of: &value) { raw in
        raw.initializeMemory(as: UInt8.self, repeating: 0)
        raw[0] = UInt8(bitPattern: exponent)
        var flags = UInt8(truncatingIfNeeded: mantissa.length)
        if signum < 0 && !normalized.isEmpty {
            flags |= decimalNegativeMask
        }
        if mantissa.length > 0 {
            flags |= decimalCompactMask
        }
        raw[1] = flags
        for index in 0..<decimalMaxMantissaWords {
            let offset = decimalHeaderSize + index * 2
            let word = mantissa.words[index]
            raw[offset] = UInt8(truncatingIfNeeded: word)
            raw[offset + 1] = UInt8(truncatingIfNeeded: word >> 8)
        }
    }
    NSDecimalCompact(&value)
    return value
}

private func smallUnscaledValueForWire(_ state: FoundationDecimalWireState) -> Int64? {
    guard let magnitude = decimalUInt64(from: state.magnitude) else {
        return nil
    }
    if state.signum >= 0 {
        guard magnitude <= decimalSmallPositiveMax else {
            return nil
        }
        return Int64(magnitude)
    }
    guard magnitude <= decimalSmallNegativeAbsMax else {
        return nil
    }
    return -Int64(magnitude)
}

extension Decimal {
    internal var foryScale: Int32 {
        foundationDecimalWireState(self).scale
    }

    internal var foryUnscaledString: String {
        let state = foundationDecimalWireState(self)
        return decimalMagnitudeString(signum: state.signum, magnitude: state.magnitude)
    }
}

extension Decimal: Serializer {
    public static func foryDefault() -> Decimal {
        .zero
    }

    public static var staticTypeId: TypeId {
        .decimal
    }

    public func foryWriteData(_ context: WriteContext, hasGenerics: Bool) throws {
        _ = hasGenerics
        let state = foundationDecimalWireState(self)
        context.buffer.writeVarInt32(state.scale)
        if let small = smallUnscaledValueForWire(state) {
            let header = encodeDecimalZigZag64(small) << 1
            context.buffer.writeVarUInt64(header)
            return
        }

        guard !state.magnitude.isEmpty else {
            throw ForyError.invalidData("zero must use the small decimal encoding")
        }
        let sign: UInt64 = state.signum < 0 ? 1 : 0
        let meta = (UInt64(state.magnitude.count) << 1) | sign
        let header = (meta << 1) | 1
        context.buffer.writeVarUInt64(header)
        context.buffer.writeBytes(state.magnitude)
    }

    public static func foryReadData(_ context: ReadContext) throws -> Decimal {
        let scale = try context.buffer.readVarInt32()
        let header = try context.buffer.readVarUInt64()
        if (header & 1) == 0 {
            let unscaled = decodeDecimalZigZag64(header >> 1)
            let signum: Int8 = unscaled == 0 ? 0 : (unscaled < 0 ? -1 : 1)
            return try buildFoundationDecimal(
                signum: signum,
                magnitude: decimalMagnitudeBytes(from: unscaled.magnitude),
                scale: scale
            )
        }

        let meta = header >> 1
        let signum: Int8 = (meta & 1) == 0 ? 1 : -1
        let length = Int(meta >> 1)
        guard length > 0 else {
            throw ForyError.invalidData("invalid decimal magnitude length \(length)")
        }
        let payload = try context.buffer.readBytes(count: length)
        guard payload[length - 1] != 0 else {
            throw ForyError.invalidData("non-canonical decimal payload: trailing zero byte")
        }
        let normalized = normalizeDecimalMagnitude(payload)
        guard !normalized.isEmpty else {
            throw ForyError.invalidData("big decimal encoding must not represent zero")
        }
        return try buildFoundationDecimal(signum: signum, magnitude: normalized, scale: scale)
    }
}
