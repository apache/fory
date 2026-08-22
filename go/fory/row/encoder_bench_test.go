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

package row

import (
	"fmt"
	"testing"
)

// Framed encoding must cost one output allocation beyond the row work.
func BenchmarkEncodeFramed(b *testing.B) {
	enc, err := NewEncoder[person]()
	if err != nil {
		b.Fatal(err)
	}
	value := samplePerson()
	b.ReportAllocs()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		if _, err := enc.Encode(&value); err != nil {
			b.Fatal(err)
		}
	}
}

type mapHolder struct {
	Entries map[string]int64
}

// Map codecs must stay linear without per-entry reflection allocations.
func BenchmarkMapRoundTrip(b *testing.B) {
	enc, err := NewEncoder[mapHolder]()
	if err != nil {
		b.Fatal(err)
	}
	value := mapHolder{Entries: make(map[string]int64, 256)}
	for i := 0; i < 256; i++ {
		value.Entries[fmt.Sprintf("key-%d", i)] = int64(i)
	}
	b.Run("encode", func(b *testing.B) {
		b.ReportAllocs()
		for i := 0; i < b.N; i++ {
			if _, err := enc.ToRow(&value); err != nil {
				b.Fatal(err)
			}
		}
	})
	rowBytes, err := enc.ToRow(&value)
	if err != nil {
		b.Fatal(err)
	}
	b.Run("decode", func(b *testing.B) {
		b.ReportAllocs()
		var out mapHolder
		for i := 0; i < b.N; i++ {
			if err := enc.FromRowInto(rowBytes, &out); err != nil {
				b.Fatal(err)
			}
		}
	})
}
