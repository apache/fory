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

private let maxMetaStringLength = 32_767

public enum MetaStringEncoding: UInt8, CaseIterable, Sendable {
    case utf8 = 0
    case lowerSpecial = 1
    case lowerUpperDigitSpecial = 2
    case firstToLowerSpecial = 3
    case allToLowerSpecial = 4
}

public struct MetaString: Equatable, Hashable, Sendable {
    public let value: String
    public let encoding: MetaStringEncoding
    public let specialChar1: Character
    public let specialChar2: Character
    public let bytes: [UInt8]
    public let stripLastChar: Bool

    public init(
        value: String,
        encoding: MetaStringEncoding,
        specialChar1: Character,
        specialChar2: Character,
        bytes: [UInt8]
    ) throws {
        if value.count >= maxMetaStringLength {
            throw ForyError.encodingError("meta string too long")
        }
        if encoding != .utf8, bytes.isEmpty {
            throw ForyError.encodingError("encoded meta string cannot be empty")
        }
        self.value = value
        self.encoding = encoding
        self.specialChar1 = specialChar1
        self.specialChar2 = specialChar2
        self.bytes = bytes
        self.stripLastChar = encoding != .utf8 && (bytes[0] & 0x80) != 0
    }

    public static func empty(specialChar1: Character, specialChar2: Character) -> MetaString {
        try! MetaString(
            value: "",
            encoding: .utf8,
            specialChar1: specialChar1,
            specialChar2: specialChar2,
            bytes: []
        )
    }
}

public struct MetaStringEncoder: Sendable {
    public let specialChar1: Character
    public let specialChar2: Character

    public init(specialChar1: Character, specialChar2: Character) {
        self.specialChar1 = specialChar1
        self.specialChar2 = specialChar2
    }

    public static let namespace = MetaStringEncoder(specialChar1: ".", specialChar2: "_")
    public static let typeName = MetaStringEncoder(specialChar1: "$", specialChar2: "_")
    public static let fieldName = MetaStringEncoder(specialChar1: "$", specialChar2: "_")

    public func encode(_ input: String) throws -> MetaString {
        try encodeAuto(input, allowedEncodings: nil)
    }

    public func encode(_ input: String, allowedEncodings: [MetaStringEncoding]) throws -> MetaString {
        try encodeAuto(input, allowedEncodings: allowedEncodings)
    }

    public func encode(_ input: String, encoding: MetaStringEncoding) throws -> MetaString {
        if input.count >= maxMetaStringLength {
            throw ForyError.encodingError("meta string too long")
        }
        if input.isEmpty {
            return MetaString.empty(specialChar1: specialChar1, specialChar2: specialChar2)
        }
        if encoding != .utf8, !isLatin(input) {
            throw ForyError.encodingError("non-ASCII characters are not allowed for packed meta string")
        }

        switch encoding {
        case .utf8:
            return try MetaString(
                value: input,
                encoding: .utf8,
                specialChar1: specialChar1,
                specialChar2: specialChar2,
                bytes: Array(input.utf8)
            )
        case .lowerSpecial:
            return try MetaString(
                value: input,
                encoding: .lowerSpecial,
                specialChar1: specialChar1,
                specialChar2: specialChar2,
                bytes: try encodeGeneric(input, bitsPerChar: 5, mapper: mapLowerSpecial)
            )
        case .lowerUpperDigitSpecial:
            return try MetaString(
                value: input,
                encoding: .lowerUpperDigitSpecial,
                specialChar1: specialChar1,
                specialChar2: specialChar2,
                bytes: try encodeGeneric(input, bitsPerChar: 6, mapper: mapLowerUpperDigitSpecial)
            )
        case .firstToLowerSpecial:
            let lower = lowerFirstAscii(input)
            return try MetaString(
                value: input,
                encoding: .firstToLowerSpecial,
                specialChar1: specialChar1,
                specialChar2: specialChar2,
                bytes: try encodeGeneric(lower, bitsPerChar: 5, mapper: mapLowerSpecial)
            )
        case .allToLowerSpecial:
            let lowered = escapeAllUpper(input)
            return try MetaString(
                value: input,
                encoding: .allToLowerSpecial,
                specialChar1: specialChar1,
                specialChar2: specialChar2,
                bytes: try encodeGeneric(lowered, bitsPerChar: 5, mapper: mapLowerSpecial)
            )
        }
    }

    private func encodeAuto(_ input: String, allowedEncodings: [MetaStringEncoding]?) throws -> MetaString {
        if input.count >= maxMetaStringLength {
            throw ForyError.encodingError("meta string too long")
        }
        if input.isEmpty {
            return MetaString.empty(specialChar1: specialChar1, specialChar2: specialChar2)
        }
        if !isLatin(input) {
            return try MetaString(
                value: input,
                encoding: .utf8,
                specialChar1: specialChar1,
                specialChar2: specialChar2,
                bytes: Array(input.utf8)
            )
        }
        let encoding = chooseEncoding(input, allowedEncodings: allowedEncodings)
        return try encode(input, encoding: encoding)
    }

    private func chooseEncoding(_ input: String, allowedEncodings: [MetaStringEncoding]?) -> MetaStringEncoding {
        let allow: (MetaStringEncoding) -> Bool = { encoding in
            allowedEncodings?.contains(encoding) ?? true
        }

        var digitCount = 0
        var upperCount = 0
        var canLowerSpecial = true
        var canLowerUpperDigitSpecial = true

        for scalar in input.unicodeScalars {
            let c = Character(scalar)
            if canLowerSpecial {
                let isValid =
                    (scalar.value >= 97 && scalar.value <= 122) ||
                    c == "." || c == "_" || c == "$" || c == "|"
                if !isValid {
                    canLowerSpecial = false
                }
            }
            if canLowerUpperDigitSpecial {
                let isLower = scalar.value >= 97 && scalar.value <= 122
                let isUpper = scalar.value >= 65 && scalar.value <= 90
                let isDigit = scalar.value >= 48 && scalar.value <= 57
                let isSpecial = c == specialChar1 || c == specialChar2
                if !(isLower || isUpper || isDigit || isSpecial) {
                    canLowerUpperDigitSpecial = false
                }
            }
            if scalar.value >= 48 && scalar.value <= 57 {
                digitCount += 1
            }
            if scalar.value >= 65 && scalar.value <= 90 {
                upperCount += 1
            }
        }

        if canLowerSpecial, allow(.lowerSpecial) {
            return .lowerSpecial
        }
        if canLowerUpperDigitSpecial {
            if digitCount != 0, allow(.lowerUpperDigitSpecial) {
                return .lowerUpperDigitSpecial
            }
            if upperCount == 1,
               input.first?.isUppercase == true,
               allow(.firstToLowerSpecial)
            {
                return .firstToLowerSpecial
            }
            if ((input.count + upperCount) * 5) < (input.count * 6), allow(.allToLowerSpecial) {
                return .allToLowerSpecial
            }
            if allow(.lowerUpperDigitSpecial) {
                return .lowerUpperDigitSpecial
            }
        }
        return .utf8
    }

    private func encodeGeneric(
        _ input: String,
        bitsPerChar: Int,
        mapper: (Character) throws -> UInt8
    ) throws -> [UInt8] {
        let chars = Array(input)
        let totalBits = chars.count * bitsPerChar + 1
        let byteLength = (totalBits + 7) / 8
        var bytes = Array(repeating: UInt8(0), count: byteLength)
        var currentBit = 1

        for c in chars {
            let value = try mapper(c)
            for i in stride(from: bitsPerChar - 1, through: 0, by: -1) {
                if ((value >> UInt8(i)) & 0x01) != 0 {
                    let bytePos = currentBit / 8
                    let bitPos = currentBit % 8
                    bytes[bytePos] |= UInt8(1 << (7 - bitPos))
                }
                currentBit += 1
            }
        }

        if byteLength * 8 >= totalBits + bitsPerChar {
            bytes[0] |= 0x80
        }
        return bytes
    }

    private func mapLowerSpecial(_ c: Character) throws -> UInt8 {
        guard let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1 else {
            throw ForyError.encodingError("unsupported character in LOWER_SPECIAL")
        }
        if scalar.value >= 97 && scalar.value <= 122 {
            return UInt8(scalar.value - 97)
        }
        switch c {
        case ".": return 26
        case "_": return 27
        case "$": return 28
        case "|": return 29
        default:
            throw ForyError.encodingError("unsupported character in LOWER_SPECIAL")
        }
    }

    private func mapLowerUpperDigitSpecial(_ c: Character) throws -> UInt8 {
        guard let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1 else {
            throw ForyError.encodingError("unsupported character in LOWER_UPPER_DIGIT_SPECIAL")
        }
        if scalar.value >= 97 && scalar.value <= 122 {
            return UInt8(scalar.value - 97)
        }
        if scalar.value >= 65 && scalar.value <= 90 {
            return UInt8(26 + scalar.value - 65)
        }
        if scalar.value >= 48 && scalar.value <= 57 {
            return UInt8(52 + scalar.value - 48)
        }
        if c == specialChar1 {
            return 62
        }
        if c == specialChar2 {
            return 63
        }
        throw ForyError.encodingError("unsupported character in LOWER_UPPER_DIGIT_SPECIAL")
    }

    private func lowerFirstAscii(_ input: String) -> String {
        guard let first = input.first else {
            return input
        }
        let lowered = String(first).lowercased()
        return lowered + input.dropFirst()
    }

    private func escapeAllUpper(_ input: String) -> String {
        var out = String()
        out.reserveCapacity(input.count * 2)
        for c in input {
            if c.isUppercase {
                out.append("|")
                out.append(String(c).lowercased())
            } else {
                out.append(c)
            }
        }
        return out
    }

    private func isLatin(_ input: String) -> Bool {
        for scalar in input.unicodeScalars where scalar.value > 255 {
            return false
        }
        return true
    }
}

public struct MetaStringDecoder: Sendable {
    public let specialChar1: Character
    public let specialChar2: Character

    public init(specialChar1: Character, specialChar2: Character) {
        self.specialChar1 = specialChar1
        self.specialChar2 = specialChar2
    }

    public static let namespace = MetaStringDecoder(specialChar1: ".", specialChar2: "_")
    public static let typeName = MetaStringDecoder(specialChar1: "$", specialChar2: "_")
    public static let fieldName = MetaStringDecoder(specialChar1: "$", specialChar2: "_")

    public func decode(bytes: [UInt8], encoding: MetaStringEncoding) throws -> MetaString {
        let value: String
        switch encoding {
        case .utf8:
            value = String(decoding: bytes, as: UTF8.self)
        case .lowerSpecial:
            value = try decodeGeneric(bytes: bytes, bitsPerChar: 5, mapper: unmapLowerSpecial)
        case .lowerUpperDigitSpecial:
            value = try decodeGeneric(bytes: bytes, bitsPerChar: 6, mapper: unmapLowerUpperDigitSpecial)
        case .firstToLowerSpecial:
            let decoded = try decodeGeneric(bytes: bytes, bitsPerChar: 5, mapper: unmapLowerSpecial)
            if let first = decoded.first {
                value = String(first).uppercased() + decoded.dropFirst()
            } else {
                value = decoded
            }
        case .allToLowerSpecial:
            let decoded = try decodeGeneric(bytes: bytes, bitsPerChar: 5, mapper: unmapLowerSpecial)
            value = unescapeAllUpper(decoded)
        }
        return try MetaString(
            value: value,
            encoding: encoding,
            specialChar1: specialChar1,
            specialChar2: specialChar2,
            bytes: bytes
        )
    }

    private func decodeGeneric(
        bytes: [UInt8],
        bitsPerChar: Int,
        mapper: (UInt8) throws -> Character
    ) throws -> String {
        if bytes.isEmpty {
            return ""
        }
        let stripLast = (bytes[0] & 0x80) != 0
        let totalBits = bytes.count * 8
        var bitIndex = 1
        var result = String()
        result.reserveCapacity(bytes.count)

        while bitIndex + bitsPerChar <= totalBits,
              !(stripLast && (bitIndex + 2 * bitsPerChar > totalBits)) {
            var value: UInt8 = 0
            for _ in 0..<bitsPerChar {
                let byteIndex = bitIndex / 8
                let intra = bitIndex % 8
                let bit = (bytes[byteIndex] >> UInt8(7 - intra)) & 0x01
                value = (value << 1) | bit
                bitIndex += 1
            }
            result.append(try mapper(value))
        }
        return result
    }

    private func unmapLowerSpecial(_ value: UInt8) throws -> Character {
        switch value {
        case 0 ... 25:
            return Character(UnicodeScalar(UInt32(97 + value))!)
        case 26:
            return "."
        case 27:
            return "_"
        case 28:
            return "$"
        case 29:
            return "|"
        default:
            throw ForyError.encodingError("invalid LOWER_SPECIAL value")
        }
    }

    private func unmapLowerUpperDigitSpecial(_ value: UInt8) throws -> Character {
        switch value {
        case 0 ... 25:
            return Character(UnicodeScalar(UInt32(97 + value))!)
        case 26 ... 51:
            return Character(UnicodeScalar(UInt32(65 + value - 26))!)
        case 52 ... 61:
            return Character(UnicodeScalar(UInt32(48 + value - 52))!)
        case 62:
            return specialChar1
        case 63:
            return specialChar2
        default:
            throw ForyError.encodingError("invalid LOWER_UPPER_DIGIT_SPECIAL value")
        }
    }

    private func unescapeAllUpper(_ input: String) -> String {
        var out = String()
        out.reserveCapacity(input.count)
        var it = input.makeIterator()
        while let c = it.next() {
            if c == "|", let next = it.next() {
                out.append(String(next).uppercased())
            } else {
                out.append(c)
            }
        }
        return out
    }
}
