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
	"fmt"
	"reflect"
	"testing"

	forygo "github.com/apache/fory/go/fory"
	"github.com/stretchr/testify/require"
)

// TestActualCodegenName - 分析codegen实际使用的名称
func TestActualCodegenName(t *testing.T) {
	fmt.Println("=== 分析Codegen实际使用的名称 ===")

	// 从源码分析得出：
	// RegisterSerializerFactory 会计算：
	// typeTag := pkgPath + "." + typeName

	validationDemoType := reflect.TypeOf(ValidationDemo{})
	pkgPath := validationDemoType.PkgPath()
	typeName := validationDemoType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	fmt.Printf("ValidationDemo 类型分析：\n")
	fmt.Printf("  包路径: %s\n", pkgPath)
	fmt.Printf("  类型名: %s\n", typeName)
	fmt.Printf("  实际typeTag: %s\n", expectedTypeTag)

	// 现在使用正确的完整名称进行测试
	fmt.Println("\n=== 使用正确的完整名称测试 ===")

	// 创建测试数据
	codegenInstance := &ValidationDemo{
		A: 100,
		B: "test_data",
		C: 200,
	}

	type ReflectStruct struct {
		A int32  `json:"a"`
		B string `json:"b"`
		C int64  `json:"c"`
	}

	reflectInstance := &ReflectStruct{
		A: 100,
		B: "test_data",
		C: 200,
	}

	// Codegen 模式 (自动使用完整名称)
	foryForCodegen := forygo.NewFory(true)

	// Reflect 模式 (使用完整名称注册)
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectStruct{})
	require.NoError(t, err, "应该能够用完整名称注册ReflectStruct")

	fmt.Printf("✅ 成功使用完整名称注册: %s\n", expectedTypeTag)

	// 序列化测试
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen序列化不应失败")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect序列化不应失败")

	fmt.Printf("\n序列化结果：\n")
	fmt.Printf("  Codegen数据长度: %d bytes\n", len(codegenData))
	fmt.Printf("  Reflect数据长度: %d bytes\n", len(reflectData))
	fmt.Printf("  数据是否相同: %t\n", reflect.DeepEqual(codegenData, reflectData))

	if reflect.DeepEqual(codegenData, reflectData) {
		fmt.Println("🎉 SUCCESS: 使用完整包路径名称后，两个struct成功映射到相同名称！")
	} else {
		fmt.Println("❌ 仍然不同，可能还有其他因素")
		fmt.Printf("  Codegen hex: %x\n", codegenData)
		fmt.Printf("  Reflect hex: %x\n", reflectData)
	}

	// 验证跨序列化
	fmt.Println("\n=== 跨序列化测试 ===")

	// 用reflect fory反序列化codegen数据
	var reflectResult *ReflectStruct
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	if err == nil && reflectResult != nil {
		fmt.Printf("✅ 成功用reflect反序列化codegen数据: %+v\n", reflectResult)
	} else {
		fmt.Printf("❌ 用reflect反序列化codegen数据失败: %v\n", err)
	}

	// 用codegen fory反序列化reflect数据
	var codegenResult *ValidationDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	if err == nil && codegenResult != nil {
		fmt.Printf("✅ 成功用codegen反序列化reflect数据: %+v\n", codegenResult)
	} else {
		fmt.Printf("❌ 用codegen反序列化reflect数据失败: %v\n", err)
	}
}
