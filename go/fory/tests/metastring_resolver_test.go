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

import (
	"bytes"
	"strings"
	"testing"

	"github.com/apache/fory/go/fory"

	"github.com/apache/fory/go/fory/meta"
)

func TestMetaStringResolver(t *testing.T) {
	resolver := fory.NewMetaStringResolver()
	encoder := meta.NewEncoder('$', '_')
	buffer := fory.NewByteBuffer(make([]byte, 512)) // Allocate enough space

	// Test 1: Regular English string
	metaStr1, _ := encoder.Encode("hello, world")
	metaBytes1 := resolver.GetMetaStrBytes(&metaStr1)
	if err := resolver.WriteMetaStringBytes(buffer, metaBytes1); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	got1, _ := resolver.ReadMetaStringBytes(buffer)
	if got1.Hashcode != metaBytes1.Hashcode || !bytes.Equal(got1.Data, metaBytes1.Data) {
		t.Errorf("Mismatch in English string")
	}

	// Test 2: Manually constructed MetaStringBytes
	data2 := []byte{0xBF, 0x05, 0xA4, 0x71, 0xA9, 0x92, 0x53, 0x96, 0xA6, 0x49, 0x4F, 0x72, 0x9C, 0x68, 0x29, 0x80}
	metaBytes2 := fory.NewMetaStringBytes(data2, int64(-5456063526933366015))
	if err := resolver.WriteMetaStringBytes(buffer, metaBytes2); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	got2, _ := resolver.ReadMetaStringBytes(buffer)
	if got2.Hashcode != metaBytes2.Hashcode || !bytes.Equal(got2.Data, metaBytes2.Data) {
		t.Errorf("Mismatch in custom data")
	}

	// Test 3: Empty string
	metaStr3, _ := encoder.Encode("")
	metaBytes3 := resolver.GetMetaStrBytes(&metaStr3)
	if err := resolver.WriteMetaStringBytes(buffer, metaBytes3); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	got3, _ := resolver.ReadMetaStringBytes(buffer)
	if got3.Hashcode != metaBytes3.Hashcode || !bytes.Equal(got3.Data, metaBytes3.Data) {
		t.Errorf("Mismatch in empty string")
	}

	// Test 4: Chinese string
	metaStr4, _ := encoder.Encode("你好，世界")
	metaBytes4 := resolver.GetMetaStrBytes(&metaStr4)
	if err := resolver.WriteMetaStringBytes(buffer, metaBytes4); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	got4, _ := resolver.ReadMetaStringBytes(buffer)
	if got4.Hashcode != metaBytes4.Hashcode || !bytes.Equal(got4.Data, metaBytes4.Data) {
		t.Errorf("Mismatch in Chinese string")
	}

	// Test 5: Japanese string (more than 16 bytes, triggers hash-based encoding)
	metaStr5, _ := encoder.Encode("こんにちは世界")
	metaBytes5 := resolver.GetMetaStrBytes(&metaStr5)
	if err := resolver.WriteMetaStringBytes(buffer, metaBytes5); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	got5, _ := resolver.ReadMetaStringBytes(buffer)
	if got5.Hashcode != metaBytes5.Hashcode || !bytes.Equal(got5.Data, metaBytes5.Data) {
		t.Errorf("Mismatch in Japanese string")
	}

	// Test 6: Long string (more than 16 bytes, triggers hash-based encoding)
	longStr := strings.Repeat("hello, world", 10)
	metaStr6, _ := encoder.Encode(longStr)
	metaBytes6 := resolver.GetMetaStrBytes(&metaStr6)
	if err := resolver.WriteMetaStringBytes(buffer, metaBytes6); err != nil {
		t.Fatalf("write failed: %v", err)
	}
	got6, _ := resolver.ReadMetaStringBytes(buffer)
	if got6.Hashcode != metaBytes6.Hashcode || !bytes.Equal(got6.Data, metaBytes6.Data) {
		t.Errorf("Mismatch in long string")
	}
}
