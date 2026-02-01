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

package meta

import (
	"errors"
	"fmt"
	"strconv"
)

type Encoder struct {
	specialChar1 byte
	specialChar2 byte
}

func NewEncoder(specialCh1 byte, specialCh2 byte) *Encoder {
	return &Encoder{
		specialChar1: specialCh1,
		specialChar2: specialCh2,
	}
}

// Encode the input string to MetaString using adaptive encoding
func (e *Encoder) Encode(input string) (MetaString, error) {
	if isNumberString(input) {
		if encoded, ok := tryEncodeNumberString(input); ok {
			return MetaString{
				inputString:  input,
				encoding:     EXTENDED,
				specialChar1: e.specialChar1,
				specialChar2: e.specialChar2,
				encodedBytes: encoded,
			}, nil
		}
		return MetaString{
			inputString:  input,
			encoding:     EXTENDED,
			specialChar1: e.specialChar1,
			specialChar2: e.specialChar2,
			encodedBytes: encodeExtendedUtf8(input),
		}, nil
	}
	if !isASCII(input) {
		return MetaString{
			inputString:  input,
			encoding:     EXTENDED,
			specialChar1: e.specialChar1,
			specialChar2: e.specialChar2,
			encodedBytes: encodeExtendedUtf8(input),
		}, nil
	}
	encoding := e.ComputeEncoding(input)
	return e.EncodeWithEncoding(input, encoding)
}

// EncodeWithEncoding Encodes the input string to MetaString using specified encoding.
func (e *Encoder) EncodeWithEncoding(input string, encoding Encoding) (MetaString, error) {
	if encoding != EXTENDED && !isASCII(input) {
		return MetaString{}, errors.New("non-ASCII characters in meta string are not allowed")
	}
	if len(input) > 32767 {
		return MetaString{}, errors.New("long meta string than 32767 is not allowed")
	}
	if len(input) == 0 {
		// we prepend one bit at the start to indicate whether strip last char
		// so checking empty here will be convenient for encoding procedure
		return MetaString{
			inputString:  input,
			encoding:     EXTENDED,
			specialChar1: e.specialChar1,
			specialChar2: e.specialChar2,
			encodedBytes: nil,
		}, nil
	}
	// execute encoding algorithm according to the encoding mode
	var encodedBytes []byte
	var err error
	resultEncoding := encoding
	switch encoding {
	case LOWER_SPECIAL:
		encodedBytes, err = e.EncodeLowerSpecial(input)
	case LOWER_UPPER_DIGIT_SPECIAL:
		encodedBytes, err = e.EncodeLowerUpperDigitSpecial(input)
	case FIRST_TO_LOWER_SPECIAL:
		encodedBytes, err = e.EncodeFirstToLowerSpecial(input)
	case ALL_TO_LOWER_SPECIAL:
		encodedBytes, err = e.EncodeAllToLowerSpecial(input)
	default:
		encodedBytes = encodeExtended(input)
		resultEncoding = EXTENDED
	}
	return MetaString{
		inputString:  input,
		encoding:     resultEncoding,
		specialChar1: e.specialChar1,
		specialChar2: e.specialChar2,
		encodedBytes: encodedBytes,
	}, err
}

func (e *Encoder) EncodeLowerSpecial(input string) ([]byte, error) {
	return e.EncodeGeneric([]byte(input), 5)
}

func (e *Encoder) EncodeLowerUpperDigitSpecial(input string) ([]byte, error) {
	return e.EncodeGeneric([]byte(input), 6)
}

func (e *Encoder) EncodeFirstToLowerSpecial(input string) ([]byte, error) {
	// all chars in string are ASCII, so we can modify input[0] directly
	chars := []byte(input)
	chars[0] = chars[0] - 'A' + 'a' // chars[0] is sure to exist and is upper letter
	return e.EncodeGeneric(chars, 5)
}

func (e *Encoder) EncodeAllToLowerSpecial(input string) ([]byte, error) {
	chars := make([]byte, len(input)+countUppers(input))
	idx := 0
	for i := 0; i < len(input); i++ {
		if input[i] >= 'A' && input[i] <= 'Z' {
			chars[idx] = '|'
			chars[idx+1] = input[i] - 'A' + 'a'
			idx += 2
		} else {
			chars[idx] = input[i]
			idx += 1
		}
	}
	return e.EncodeGeneric(chars, 5)
}

func (e *Encoder) EncodeGeneric(chars []byte, bitsPerChar int) (result []byte, err error) {
	totBits := len(chars)*bitsPerChar + 1
	result = make([]byte, (totBits+7)/8)
	currentBit := 1
	for _, c := range chars {
		var value byte
		if bitsPerChar == 5 {
			value, err = e.charToValueLowerSpecial(c)
		} else if bitsPerChar == 6 {
			value, err = e.charToValueLowerUpperDigitSpecial(c)
		}
		if err != nil {
			return nil, err
		}
		// Use currentBit to figure out where the result should be filled
		// abc encodedBytes as [00000] [000,01] [00010] [0, corresponding to three bytes, which are 0, 68, 0 (68 = 64 + 4)
		// In order, put the highest bit first, then the lower
		for i := bitsPerChar - 1; i >= 0; i-- {
			if (value & (1 << i)) > 0 {
				bytePos := currentBit / 8
				bitPos := currentBit % 8
				result[bytePos] |= 1 << (7 - bitPos)
			}
			currentBit++
		}
	}
	if totBits+bitsPerChar <= len(result)*8 {
		result[0] |= byte(0x80)
	}
	return
}

func (e *Encoder) ComputeEncoding(input string) Encoding {
	allEncodings := []Encoding{LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL, FIRST_TO_LOWER_SPECIAL, ALL_TO_LOWER_SPECIAL, EXTENDED}
	return e.ComputeEncodingWith(input, allEncodings)
}

// EncodePackage encodes a package/namespace string using Java-compatible encodings.
// Java uses: EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL for packages.
func (e *Encoder) EncodePackage(input string) (MetaString, error) {
	// Java pkgEncodings = {EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL}
	pkgEncodings := []Encoding{ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL, EXTENDED}
	encoding := e.ComputeEncodingWith(input, pkgEncodings)
	return e.EncodeWithEncoding(input, encoding)
}

// EncodeTypeName encodes a typename string using Java-compatible encodings.
// Java uses: EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL, FIRST_TO_LOWER_SPECIAL for typenames.
func (e *Encoder) EncodeTypeName(input string) (MetaString, error) {
	// Java typeNameEncodings = {EXTENDED, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL, FIRST_TO_LOWER_SPECIAL}
	typeNameEncodings := []Encoding{ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL, FIRST_TO_LOWER_SPECIAL, EXTENDED}
	encoding := e.ComputeEncodingWith(input, typeNameEncodings)
	return e.EncodeWithEncoding(input, encoding)
}

func (e *Encoder) ComputeEncodingWith(input string, encodings []Encoding) Encoding {
	encodingFlags := make(map[Encoding]bool)
	for _, enc := range encodings {
		encodingFlags[enc] = true
	}
	// Special case for empty string: default to EXTENDED encoding
	if len(input) == 0 {
		return EXTENDED
	}
	if isNumberString(input) {
		return EXTENDED
	}
	statistics := e.computeStringStatistics(input)
	if statistics.canLowerSpecialEncoded && encodingFlags[LOWER_SPECIAL] {
		return LOWER_SPECIAL
	}
	if statistics.canLowerUpperDigitSpecialEncoded {
		// Here, the string contains only letters, numbers, and two special symbols
		if statistics.digitCount != 0 && encodingFlags[LOWER_UPPER_DIGIT_SPECIAL] {
			return LOWER_UPPER_DIGIT_SPECIAL
		}
		upperCount := statistics.upperCount
		chars := []byte(input)
		if upperCount == 1 && chars[0] >= 'A' && chars[0] <= 'Z' && encodingFlags[FIRST_TO_LOWER_SPECIAL] {
			return FIRST_TO_LOWER_SPECIAL
		}
		if (len(chars)+upperCount)*5 < len(chars)*6 && encodingFlags[ALL_TO_LOWER_SPECIAL] {
			return ALL_TO_LOWER_SPECIAL
		}
		if encodingFlags[LOWER_UPPER_DIGIT_SPECIAL] {
			return LOWER_UPPER_DIGIT_SPECIAL
		}
	}
	return EXTENDED
}

func isNumberString(input string) bool {
	if input == "" {
		return false
	}
	start := 0
	if input[0] == '-' {
		if len(input) == 1 {
			return false
		}
		start = 1
	}
	for i := start; i < len(input); i++ {
		if input[i] < '0' || input[i] > '9' {
			return false
		}
	}
	return true
}

func encodeExtended(input string) []byte {
	if isNumberString(input) {
		if encoded, ok := tryEncodeNumberString(input); ok {
			return encoded
		}
	}
	return encodeExtendedUtf8(input)
}

func encodeExtendedUtf8(input string) []byte {
	payload := []byte(input)
	encoded := make([]byte, len(payload)+1)
	encoded[0] = ExtendedEncodingUTF8
	copy(encoded[1:], payload)
	return encoded
}

func tryEncodeNumberString(input string) ([]byte, bool) {
	negative := input[0] == '-'
	digits := input
	if negative {
		digits = input[1:]
	}
	if digits == "" {
		return nil, false
	}
	value, err := strconv.ParseUint(digits, 10, 64)
	if err != nil {
		return nil, false
	}
	payload := encodeUint64LE(value)
	encoding := ExtendedEncodingNumberString
	if negative {
		encoding = ExtendedEncodingNegativeNumberString
	}
	encoded := make([]byte, len(payload)+1)
	encoded[0] = encoding
	copy(encoded[1:], payload)
	return encoded, true
}

func encodeUint64LE(value uint64) []byte {
	if value == 0 {
		return []byte{0}
	}
	payload := make([]byte, 0, 8)
	for value != 0 {
		payload = append(payload, byte(value&0xFF))
		value >>= 8
	}
	return payload
}

func isASCII(input string) bool {
	for _, r := range input {
		if r > 127 {
			return false
		}
	}
	return true
}

type stringStatistics struct {
	digitCount                       int
	upperCount                       int
	canLowerSpecialEncoded           bool
	canLowerUpperDigitSpecialEncoded bool
}

func (e *Encoder) computeStringStatistics(input string) *stringStatistics {
	digitCount, upperCount := 0, 0
	canLowerSpecialEncoded := true
	canLowerUpperDigitSpecialEncoded := true
	for _, c := range []byte(input) {
		if canLowerUpperDigitSpecialEncoded {
			if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' ||
				c >= '0' && c <= '9' || c == e.specialChar1 || c == e.specialChar2) {
				canLowerUpperDigitSpecialEncoded = false
			}
		}

		if canLowerSpecialEncoded {
			if !(c >= 'a' && c <= 'z' || c == '.' || c == '_' || c == '$' || c == '|') {
				canLowerSpecialEncoded = false
			}
		}

		if c >= '0' && c <= '9' {
			digitCount++
		}

		if c >= 'A' && c <= 'Z' {
			upperCount++
		}
	}
	return &stringStatistics{
		digitCount:                       digitCount,
		upperCount:                       upperCount,
		canLowerSpecialEncoded:           canLowerSpecialEncoded,
		canLowerUpperDigitSpecialEncoded: canLowerUpperDigitSpecialEncoded,
	}
}

func countUppers(str string) int {
	cnt := 0
	for i := 0; i < len(str); i++ {
		if str[i] >= 'A' && str[i] <= 'Z' {
			cnt++
		}
	}
	return cnt
}

func (e *Encoder) charToValueLowerSpecial(c byte) (val byte, err error) {
	if c >= 'a' && c <= 'z' {
		val = c - 'a'
	} else if c == '.' {
		val = 26
	} else if c == '_' {
		val = 27
	} else if c == '$' {
		val = 28
	} else if c == '|' {
		val = 29
	} else {
		err = fmt.Errorf("Unsupported character for LOWER_SPECIAL encoding: %v\n", c)
	}
	return
}

func (e *Encoder) charToValueLowerUpperDigitSpecial(c byte) (val byte, err error) {
	if c >= 'a' && c <= 'z' {
		val = c - 'a'
	} else if c >= 'A' && c <= 'Z' {
		val = 26 + (c - 'A')
	} else if c >= '0' && c <= '9' {
		val = 52 + (c - '0')
	} else if c == e.specialChar1 {
		val = 62
	} else if c == e.specialChar2 {
		val = 63
	} else {
		err = fmt.Errorf("Unsupported character for LOWER_UPPER_DIGIT_SPECIAL encoding: %v\n", c)
	}
	return
}
