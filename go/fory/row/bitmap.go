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

// Package row implements the Fory standard row format.
package row

// The null bitmap tracks one bit per field or array element.

// bitmapWidthInBytes returns the null bitmap size for n fields or
// elements, rounded up to a whole 8-byte word.
func bitmapWidthInBytes(n int) int {
	return ((n + 63) / 64) * 8
}

// setBit marks index i as null.
func setBit(bitmap []byte, i int) {
	bitmap[i>>3] |= 1 << (uint(i) & 7)
}

// clearBit marks index i as not null.
func clearBit(bitmap []byte, i int) {
	bitmap[i>>3] &^= 1 << (uint(i) & 7)
}

// getBit reports whether index i is null.
func getBit(bitmap []byte, i int) bool {
	return bitmap[i>>3]&(1<<(uint(i)&7)) != 0
}

// roundToWord rounds n up to the nearest multiple of 8. Row Format requires
// every variable-length value and data region to be zero-padded to an
// 8-byte boundary.
func roundToWord(n int) int {
	return (n + 7) &^ 7
}
