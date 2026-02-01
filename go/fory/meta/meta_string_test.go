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
	"testing"

	"github.com/stretchr/testify/require"
)

func TestEncodeAndDecodeMetaString(t *testing.T) {
	var data MetaString
	var dst string
	var err error

	str2bits := map[string]int{
		// "abc_def" should be encoded as 0|00000|00, 001|00010|, 11011|000, 11|00100|0, 0101
		"abc_def":                        5,
		"org.apache.fory.benchmark.data": 5,
		"HelloWorld__123.2024":           6,
		"MediaContent":                   5,
		"Apple_banana":                   5,
		"你好，世界":                          0, // not used
	}
	str2encoding := map[string]Encoding{
		"abc_def":                        LOWER_SPECIAL,
		"org.apache.fory.benchmark.data": LOWER_SPECIAL,
		"MediaContent":                   ALL_TO_LOWER_SPECIAL,
		"HelloWorld__123.2024":           LOWER_UPPER_DIGIT_SPECIAL,
		"Apple_banana":                   FIRST_TO_LOWER_SPECIAL,
		"你好，世界":                          UTF_8,
	}
	encoder := NewEncoder('.', '_')
	decoder := NewDecoder('.', '_')

	for src, bitsPerChar := range str2bits {
		data, err = encoder.Encode(src)
		require.Equal(t, nil, err)
		require.Equal(t, str2encoding[src], data.GetEncoding())
		require.Equal(t, calcTotalBytes(src, bitsPerChar, data.GetEncoding()), len(data.GetEncodedBytes()))
		dst, err = decoder.Decode(data.GetEncodedBytes(), data.GetEncoding())
		require.Equal(t, nil, err)
		require.Equal(t, src, dst)
	}

	// error situation
	dst, err = decoder.Decode([]byte{0xFF, 0x31}, LOWER_SPECIAL)
	require.NotEqual(t, nil, err)

	// empty string
	data, err = encoder.Encode("")
	require.Equal(t, nil, err)
	require.Equal(t, 0, len(data.GetEncodedBytes()))
	dst, err = decoder.Decode(data.GetEncodedBytes(), data.GetEncoding())
	require.Equal(t, nil, err)
	require.Equal(t, "", dst)
}

func calcTotalBytes(src string, bitsPerChar int, encoding Encoding) int {
	if encoding == UTF_8 {
		return len(src)
	}
	ret := len(src)*bitsPerChar + 1
	if encoding == ALL_TO_LOWER_SPECIAL {
		ret += countUppers(src) * bitsPerChar
	}
	return (ret + 7) / 8
}

func TestAsciiEncoding(t *testing.T) {
	encoder := NewEncoder('.', '_')

	data, err := encoder.Encode("asciiOnly")
	require.NoError(t, err)
	require.NotEqual(t, UTF_8, data.GetEncoding(), "Encoding should not be UTF-8 for ASCII strings")
}

func TestNonAsciiEncoding(t *testing.T) {
	encoder := NewEncoder('.', '_')

	data, err := encoder.Encode("こんにちは") // Non-ASCII String
	require.NoError(t, err)
	require.Equal(t, UTF_8, data.GetEncoding(), "Encoding should be UTF-8 for non-ASCII strings")
}

func TestEncodeWithEncodingNonAscii(t *testing.T) {
	encoder := NewEncoder('.', '_')

	_, err := encoder.EncodeWithEncoding("こんにちは", LOWER_SPECIAL)
	require.Error(t, err, "Expected error for non-ASCII characters in non-UTF-8 encoding")
	require.Equal(t, "non-ASCII characters in meta string are not allowed", err.Error())
}
