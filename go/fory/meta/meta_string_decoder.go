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
	"fmt"
)

type Decoder struct {
	specialChar1 byte
	specialChar2 byte
}

func NewDecoder(specialCh1 byte, specialCh2 byte) *Decoder {
	return &Decoder{
		specialChar1: specialCh1,
		specialChar2: specialCh2,
	}
}

// Decode
// Accept an encodedBytes byte array, and the encoding method
func (d *Decoder) Decode(data []byte, encoding Encoding) (result string, err error) {
	// we prepend one bit at the start to indicate whether strip last char
	// so checking empty here will be convenient for decoding procedure
	if data == nil {
		return "", err
	}
	var chars []byte
	switch encoding {
	case LOWER_SPECIAL:
		chars, err = d.decodeGeneric(data, encoding)
	case LOWER_UPPER_DIGIT_SPECIAL:
		chars, err = d.decodeGeneric(data, encoding)
	case FIRST_TO_LOWER_SPECIAL:
		chars, err = d.decodeGeneric(data, LOWER_SPECIAL)
		if err == nil {
			chars[0] = chars[0] - 'a' + 'A'
		}
	case ALL_TO_LOWER_SPECIAL:
		chars, err = d.decodeRepAllToLowerSpecial(data, LOWER_SPECIAL)
	case UTF_8:
		chars = data
	default:
		err = fmt.Errorf("Unexpected encoding flag: %v\n", encoding)
	}
	if err != nil {
		return "", err
	}
	return string(chars), err
}

// DecodeGeneric
// algorithm is LowerSpecial or LowerUpperDigit
func (d *Decoder) decodeGeneric(data []byte, algorithm Encoding) ([]byte, error) {
	bitsPerChar := 5
	if algorithm == LOWER_UPPER_DIGIT_SPECIAL {
		bitsPerChar = 6
	}
	// Retrieve 5 bits every iteration from data, convert them to characters, and save them to chars
	// "abc" encodedBytes as [00000] [000,01] [00010] [0, corresponding to three bytes, which are 0, 68, 0
	// Take the highest digit first, then the lower, in order

	// here access data[0] before entering the loop, so we had to deal with empty data in Decode method
	// totChars * bitsPerChar <= totBits < (totChars + 1) * bitsPerChar
	stripLastChar := (data[0] & 0x80) >> 7
	totBits := len(data)*8 - 1 - int(stripLastChar)*bitsPerChar
	totChars := totBits / bitsPerChar
	chars := make([]byte, totChars)
	bitPos, bitCount := 6, 1 // first highest bit indicates whether strip last char
	for i := 0; i < totChars; i++ {
		var val byte = 0
		for i := 0; i < bitsPerChar; i++ {
			if data[bitCount/8]&(1<<bitPos) > 0 {
				val |= 1 << (bitsPerChar - i - 1)
			}
			bitPos = (bitPos - 1 + 8) % 8
			bitCount++
		}
		ch, err := d.decodeChar(val, algorithm)
		if err != nil {
			return nil, err
		}
		chars[i] = ch
	}
	return chars, nil
}

func (d *Decoder) decodeRepAllToLowerSpecial(data []byte, algorithm Encoding) ([]byte, error) {
	// Decode the data to the lowercase letters, then convert
	str, err := d.decodeGeneric(data, algorithm)
	if err != nil {
		return nil, err
	}
	chars := make([]byte, len(str))
	j := 0
	for i := 0; i < len(str); i++ {
		if str[i] == '|' {
			chars[j] = str[i+1] - 'a' + 'A'
			i++
		} else {
			chars[j] = str[i]
		}
		j++
	}
	return chars[0:j], nil
}

/** Decoding char for two encoding algorithms */
func (d *Decoder) decodeChar(val byte, encoding Encoding) (byte, error) {
	switch encoding {
	case LOWER_SPECIAL:
		return d.decodeLowerSpecialChar(val)
	case LOWER_UPPER_DIGIT_SPECIAL:
		return d.decodeLowerUpperDigitSpecialChar(val)
	}
	return 0, fmt.Errorf("Illegal encoding flag: %v\n", encoding)
}

/** Decoding char for LOWER_SPECIAL Encoding Algorithm */
func (d *Decoder) decodeLowerSpecialChar(charValue byte) (val byte, err error) {
	if charValue <= 25 {
		val = 'a' + charValue
	} else if charValue == 26 {
		val = '.'
	} else if charValue == 27 {
		val = '_'
	} else if charValue == 28 {
		val = '$'
	} else if charValue == 29 {
		val = '|'
	} else {
		err = fmt.Errorf("Invalid character value for LOWER_SPECIAL: %v\n", charValue)
	}
	return
}

/** Decoding char for LOWER_UPPER_DIGIT_SPECIAL Encoding Algorithm. */
func (d *Decoder) decodeLowerUpperDigitSpecialChar(charValue byte) (val byte, err error) {
	if charValue <= 25 {
		val = 'a' + charValue
	} else if charValue >= 26 && charValue <= 51 {
		val = 'A' + (charValue - 26)
	} else if charValue >= 52 && charValue <= 61 {
		val = '0' + (charValue - 52)
	} else if charValue == 62 {
		val = d.specialChar1
	} else if charValue == 63 {
		val = d.specialChar2
	} else {
		err = fmt.Errorf("invalid character value for LOWER_UPPER_DIGIT_SPECIAL: %v", charValue)
	}
	return
}
