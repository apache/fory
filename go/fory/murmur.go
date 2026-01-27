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

package fory

import "encoding/binary"

const (
	murmurC1_128 = 0x87c37b91114253d5
	murmurC2_128 = 0x4cf5ad432745937f
)

func Murmur3Sum64WithSeed(data []byte, seed uint32) uint64 {
	h1, _ := Murmur3Sum128WithSeed(data, seed)
	return h1
}

func MurmurHash3_x64_128(data []byte, seed int64) (uint64, uint64) {
	return Murmur3Sum128WithSeed(data, uint32(seed))
}

func Murmur3Sum128WithSeed(data []byte, seed uint32) (uint64, uint64) {
	h1 := uint64(seed)
	h2 := uint64(seed)

	nblocks := len(data) / 16
	for i := 0; i < nblocks; i++ {
		block := data[i*16:]
		k1 := binary.LittleEndian.Uint64(block)
		k2 := binary.LittleEndian.Uint64(block[8:])

		k1 *= murmurC1_128
		k1 = (k1 << 31) | (k1 >> 33)
		k1 *= murmurC2_128
		h1 ^= k1

		h1 = (h1 << 27) | (h1 >> 37)
		h1 += h2
		h1 = h1*5 + 0x52dce729

		k2 *= murmurC2_128
		k2 = (k2 << 33) | (k2 >> 31)
		k2 *= murmurC1_128
		h2 ^= k2

		h2 = (h2 << 31) | (h2 >> 33)
		h2 += h1
		h2 = h2*5 + 0x38495ab5
	}

	tail := data[nblocks*16:]
	var k1, k2 uint64
	switch len(tail) & 15 {
	case 15:
		k2 ^= uint64(tail[14]) << 48
		fallthrough
	case 14:
		k2 ^= uint64(tail[13]) << 40
		fallthrough
	case 13:
		k2 ^= uint64(tail[12]) << 32
		fallthrough
	case 12:
		k2 ^= uint64(tail[11]) << 24
		fallthrough
	case 11:
		k2 ^= uint64(tail[10]) << 16
		fallthrough
	case 10:
		k2 ^= uint64(tail[9]) << 8
		fallthrough
	case 9:
		k2 ^= uint64(tail[8])
		k2 *= murmurC2_128
		k2 = (k2 << 33) | (k2 >> 31)
		k2 *= murmurC1_128
		h2 ^= k2
		fallthrough
	case 8:
		k1 ^= uint64(tail[7]) << 56
		fallthrough
	case 7:
		k1 ^= uint64(tail[6]) << 48
		fallthrough
	case 6:
		k1 ^= uint64(tail[5]) << 40
		fallthrough
	case 5:
		k1 ^= uint64(tail[4]) << 32
		fallthrough
	case 4:
		k1 ^= uint64(tail[3]) << 24
		fallthrough
	case 3:
		k1 ^= uint64(tail[2]) << 16
		fallthrough
	case 2:
		k1 ^= uint64(tail[1]) << 8
		fallthrough
	case 1:
		k1 ^= uint64(tail[0])
		k1 *= murmurC1_128
		k1 = (k1 << 31) | (k1 >> 33)
		k1 *= murmurC2_128
		h1 ^= k1
	}

	h1 ^= uint64(len(data))
	h2 ^= uint64(len(data))

	h1 += h2
	h2 += h1

	h1 = murmurFmix64(h1)
	h2 = murmurFmix64(h2)

	h1 += h2
	h2 += h1

	return h1, h2
}

func murmurFmix64(k uint64) uint64 {
	k ^= k >> 33
	k *= 0xff51afd7ed558ccd
	k ^= k >> 33
	k *= 0xc4ceb9fe1a85ec53
	k ^= k >> 33
	return k
}
