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

package codegen_tests

import (
	"fmt"
	"testing"

	"github.com/apache/fory/go/fory"
)

//go:generate go run ../codegen/main.go -pkg . -type "ValidationDemo"

func TestValidationDemo(t *testing.T) {
	fmt.Println("=== 代码生成验证演示 ===")

	// 1. 创建实例
	original := &ValidationDemo{
		A: 12345,         // int32
		B: "Hello Fory!", // string
		C: 98765,         // int64
	}

	fmt.Printf("1. 原始数据:\n")
	fmt.Printf("   A (int32):  %d\n", original.A)
	fmt.Printf("   B (string): %s\n", original.B)
	fmt.Printf("   C (int64):  %d\n", original.C)

	// 2. 序列化 (生成的代码)
	f := fory.NewFory(true)
	data, err := f.Marshal(original)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}

	fmt.Printf("\n2. 序列化结果:\n")
	fmt.Printf("   数据长度: %d 字节\n", len(data))
	fmt.Printf("   二进制数据: %x\n", data)

	// 3. 反序列化 (生成的代码)
	var result *ValidationDemo
	err = f.Unmarshal(data, &result)
	if err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}

	fmt.Printf("\n3. 反序列化结果:\n")
	fmt.Printf("   A (int32):  %d\n", result.A)
	fmt.Printf("   B (string): %s\n", result.B)
	fmt.Printf("   C (int64):  %d\n", result.C)

	// 4. 验证
	fmt.Printf("\n4. 验证结果:\n")
	aMatch := result.A == original.A
	bMatch := result.B == original.B
	cMatch := result.C == original.C

	fmt.Printf("   A 匹配: %t (%d == %d)\n", aMatch, result.A, original.A)
	fmt.Printf("   B 匹配: %t (%s == %s)\n", bMatch, result.B, original.B)
	fmt.Printf("   C 匹配: %t (%d == %d)\n", cMatch, result.C, original.C)

	if aMatch && bMatch && cMatch {
		fmt.Printf("\n✅ 成功: 所有数据完全一致! 代码生成工作正常!\n")
	} else {
		fmt.Printf("\n❌ 失败: 数据不匹配!\n")
		t.Fail()
	}
}
