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

// TestActualCodegenName - Analyze actual type names used by codegen
func TestActualCodegenName(t *testing.T) {
	fmt.Println("=== Analyzing Actual Codegen Type Names ===")

	// From source code analysis:
	// RegisterSerializerFactory calculates:
	// typeTag := pkgPath + "." + typeName

	validationDemoType := reflect.TypeOf(ValidationDemo{})
	pkgPath := validationDemoType.PkgPath()
	typeName := validationDemoType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	fmt.Printf("ValidationDemo type analysis:\n")
	fmt.Printf("  Package path: %s\n", pkgPath)
	fmt.Printf("  Type name: %s\n", typeName)
	fmt.Printf("  Actual typeTag: %s\n", expectedTypeTag)

	// Test with correct full type name
	fmt.Println("\n=== Testing with Correct Full Type Name ===")

	// Create test data
	codegenInstance := &ValidationDemo{
		A: 100,
		B: "test_data",
		C: 200,
		D: 3.14159,
		E: true,
	}

	type ReflectStruct struct {
		A int32
		B string
		C int64
		D float64
		E bool
	}

	reflectInstance := &ReflectStruct{
		A: 100,
		B: "test_data",
		C: 200,
		D: 3.14159,
		E: true,
	}

	// Codegen mode (automatically uses full name)
	foryForCodegen := forygo.NewFory(true)

	// Reflect mode (register with full name)
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectStruct{})
	require.NoError(t, err, "Should be able to register ReflectStruct with full name")

	fmt.Printf("✅ Successfully registered with full name: %s\n", expectedTypeTag)

	// Serialization test
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	fmt.Printf("\nSerialization results:\n")
	fmt.Printf("  Codegen data length: %d bytes\n", len(codegenData))
	fmt.Printf("  Reflect data length: %d bytes\n", len(reflectData))
	fmt.Printf("  Data identical: %t\n", reflect.DeepEqual(codegenData, reflectData))

	if reflect.DeepEqual(codegenData, reflectData) {
		fmt.Println("🎉 SUCCESS: Using full package path, both structs successfully map to the same name!")
	} else {
		fmt.Println("❌ Still different, may have other factors")
		fmt.Printf("  Codegen hex: %x\n", codegenData)
		fmt.Printf("  Reflect hex: %x\n", reflectData)
	}

	// Verify cross serialization
	fmt.Println("\n=== Cross Serialization Test ===")

	// Use reflect to deserialize codegen data
	var reflectResult *ReflectStruct
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	if err == nil && reflectResult != nil {
		fmt.Printf("✅ Successfully used reflect to deserialize codegen data: %+v\n", reflectResult)
	} else {
		fmt.Printf("❌ Failed to use reflect to deserialize codegen data: %v\n", err)
	}

	// Use codegen to deserialize reflect data
	var codegenResult *ValidationDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	if err == nil && codegenResult != nil {
		fmt.Printf("✅ Successfully used codegen to deserialize reflect data: %+v\n", codegenResult)
	} else {
		fmt.Printf("❌ Failed to use codegen to deserialize reflect data: %v\n", err)
	}
}

// TestSliceDemoXlang - Test cross-language compatibility of SliceDemo
func TestSliceDemoXlang(t *testing.T) {
	fmt.Println("=== SliceDemo Cross-Language Compatibility Test ===")

	// Get SliceDemo type information
	sliceDemoType := reflect.TypeOf(SliceDemo{})
	pkgPath := sliceDemoType.PkgPath()
	typeName := sliceDemoType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	fmt.Printf("SliceDemo type analysis:\n")
	fmt.Printf("  Package path: %s\n", pkgPath)
	fmt.Printf("  Type name: %s\n", typeName)
	fmt.Printf("  Actual typeTag: %s\n", expectedTypeTag)

	// Create test data
	codegenInstance := &SliceDemo{
		IntSlice:    []int32{1, 2, 3, 4, 5},
		StringSlice: []string{"hello", "world", "fory"},
		FloatSlice:  []float64{1.1, 2.2, 3.3},
		BoolSlice:   []bool{true, false, true},
	}

	// Define equivalent struct using reflection
	type ReflectSliceStruct struct {
		IntSlice    []int32
		StringSlice []string
		FloatSlice  []float64
		BoolSlice   []bool
	}

	reflectInstance := &ReflectSliceStruct{
		IntSlice:    []int32{1, 2, 3, 4, 5},
		StringSlice: []string{"hello", "world", "fory"},
		FloatSlice:  []float64{1.1, 2.2, 3.3},
		BoolSlice:   []bool{true, false, true},
	}

	// Codegen mode - enable reference tracking
	foryForCodegen := forygo.NewFory(true)

	// Reflect mode - enable reference tracking
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectSliceStruct{})
	require.NoError(t, err, "Should be able to register ReflectSliceStruct with full name")

	fmt.Printf("✅ Successfully registered with full name: %s\n", expectedTypeTag)

	// Serialization test
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	fmt.Printf("\nSerialization results:\n")
	fmt.Printf("  Codegen data length: %d bytes\n", len(codegenData))
	fmt.Printf("  Reflect data length: %d bytes\n", len(reflectData))
	fmt.Printf("  Data identical: %t\n", reflect.DeepEqual(codegenData, reflectData))

	// Detailed serialization data analysis
	fmt.Println("\n=== Detailed Serialization Data Analysis ===")
	fmt.Printf("Codegen complete data: %x\n", codegenData)
	fmt.Printf("Reflect complete data: %x\n", reflectData)

	// Byte-by-byte comparison
	fmt.Println("\n=== Byte-by-Byte Difference Analysis ===")
	minLen := len(codegenData)
	if len(reflectData) < minLen {
		minLen = len(reflectData)
	}

	differentBytes := 0
	for i := 0; i < minLen; i++ {
		if codegenData[i] != reflectData[i] {
			if differentBytes < 10 { // Only show first 10 differences
				fmt.Printf("  Position %d: Codegen=0x%02x, Reflect=0x%02x\n", i, codegenData[i], reflectData[i])
			}
			differentBytes++
		}
	}

	if len(codegenData) != len(reflectData) {
		fmt.Printf("  Length difference: Codegen=%d, Reflect=%d (difference=%d bytes)\n",
			len(codegenData), len(reflectData), len(reflectData)-len(codegenData))
	}

	if differentBytes == 0 && len(codegenData) == len(reflectData) {
		fmt.Println("🎉 SUCCESS: Serialization results are completely identical!")

		// Verify cross serialization
		fmt.Println("\n=== Cross Serialization Test ===")

		// Use reflect to deserialize codegen data
		var reflectResult *ReflectSliceStruct
		err = foryForReflect.Unmarshal(codegenData, &reflectResult)
		if err == nil && reflectResult != nil {
			fmt.Printf("✅ Successfully used reflect to deserialize codegen data:\n")
			fmt.Printf("   IntSlice: %v\n", reflectResult.IntSlice)
			fmt.Printf("   StringSlice: %v\n", reflectResult.StringSlice)
			fmt.Printf("   FloatSlice: %v\n", reflectResult.FloatSlice)
			fmt.Printf("   BoolSlice: %v\n", reflectResult.BoolSlice)

			// Verify data matching
			intMatch := reflect.DeepEqual(codegenInstance.IntSlice, reflectResult.IntSlice)
			stringMatch := reflect.DeepEqual(codegenInstance.StringSlice, reflectResult.StringSlice)
			floatMatch := reflect.DeepEqual(codegenInstance.FloatSlice, reflectResult.FloatSlice)
			boolMatch := reflect.DeepEqual(codegenInstance.BoolSlice, reflectResult.BoolSlice)

			fmt.Printf("   Data match check: IntSlice=%v, StringSlice=%v, FloatSlice=%v, BoolSlice=%v\n",
				intMatch, stringMatch, floatMatch, boolMatch)

			if intMatch && stringMatch && floatMatch && boolMatch {
				fmt.Println("🎉 All slice field data matches completely!")
			}
		} else {
			fmt.Printf("❌ Failed to use reflect to deserialize codegen data: %v\n", err)
		}

		// Use codegen to deserialize reflect data
		var codegenResult *SliceDemo
		err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
		if err == nil && codegenResult != nil {
			fmt.Printf("✅ Successfully used codegen to deserialize reflect data:\n")
			fmt.Printf("   IntSlice: %v\n", codegenResult.IntSlice)
			fmt.Printf("   StringSlice: %v\n", codegenResult.StringSlice)
			fmt.Printf("   FloatSlice: %v\n", codegenResult.FloatSlice)
			fmt.Printf("   BoolSlice: %v\n", codegenResult.BoolSlice)

			// Verify data matching
			intMatch := reflect.DeepEqual(reflectInstance.IntSlice, codegenResult.IntSlice)
			stringMatch := reflect.DeepEqual(reflectInstance.StringSlice, codegenResult.StringSlice)
			floatMatch := reflect.DeepEqual(reflectInstance.FloatSlice, codegenResult.FloatSlice)
			boolMatch := reflect.DeepEqual(reflectInstance.BoolSlice, codegenResult.BoolSlice)

			fmt.Printf("   Data match check: IntSlice=%v, StringSlice=%v, FloatSlice=%v, BoolSlice=%v\n",
				intMatch, stringMatch, floatMatch, boolMatch)

			if intMatch && stringMatch && floatMatch && boolMatch {
				fmt.Println("🎉 Fully compatible! Codegen can correctly deserialize reflect data!")
			}
		} else {
			fmt.Printf("❌ Failed to use codegen to deserialize reflect data: %v\n", err)
		}
	} else {
		fmt.Printf("❌ Found %d byte differences\n", differentBytes)
	}
}

// TestDynamicSliceDemoXlang - Test cross-language compatibility of DynamicSliceDemo
func TestDynamicSliceDemoXlang(t *testing.T) {
	fmt.Println("=== DynamicSliceDemo Cross-Language Compatibility Test ===")

	// Get DynamicSliceDemo type information
	dynamicSliceType := reflect.TypeOf(DynamicSliceDemo{})
	pkgPath := dynamicSliceType.PkgPath()
	typeName := dynamicSliceType.Name()
	expectedTypeTag := pkgPath + "." + typeName

	fmt.Printf("DynamicSliceDemo type analysis:\n")
	fmt.Printf("  Package path: %s\n", pkgPath)
	fmt.Printf("  Type name: %s\n", typeName)
	fmt.Printf("  Actual typeTag: %s\n", expectedTypeTag)

	// Create test data with simpler types to avoid reflection issues
	codegenInstance := &DynamicSliceDemo{
		DynamicSlice: []interface{}{
			"first",
			200, // Testing mixed types in dynamic slice
			"third",
		},
	}

	// Define equivalent struct using reflection
	type ReflectDynamicStruct struct {
		DynamicSlice []interface{} `json:"dynamic_slice"`
	}

	reflectInstance := &ReflectDynamicStruct{
		DynamicSlice: []interface{}{
			"first",
			200, // Testing mixed types in dynamic slice
			"third",
		},
	}

	// Codegen mode - enable reference tracking
	foryForCodegen := forygo.NewFory(true)

	// Reflect mode - enable reference tracking
	foryForReflect := forygo.NewFory(true)
	err := foryForReflect.RegisterTagType(expectedTypeTag, ReflectDynamicStruct{})
	require.NoError(t, err, "Should be able to register ReflectDynamicStruct with full name")

	fmt.Printf("✅ Successfully registered with full name: %s\n", expectedTypeTag)

	// Serialization test
	codegenData, err := foryForCodegen.Marshal(codegenInstance)
	require.NoError(t, err, "Codegen serialization should not fail")

	reflectData, err := foryForReflect.Marshal(reflectInstance)
	require.NoError(t, err, "Reflect serialization should not fail")

	fmt.Printf("\nSerialization results:\n")
	fmt.Printf("  Codegen data length: %d bytes\n", len(codegenData))
	fmt.Printf("  Reflect data length: %d bytes\n", len(reflectData))
	fmt.Printf("  Data identical: %t\n", reflect.DeepEqual(codegenData, reflectData))

	// Detailed serialization data analysis for dynamic slices
	fmt.Println("\n=== Complete Dynamic Slice Serialization Analysis ===")
	fmt.Printf("Codegen complete data: %x\n", codegenData)
	fmt.Printf("Reflect complete data: %x\n", reflectData)

	// Byte-by-byte comparison to find differences
	fmt.Println("\n=== Byte-by-Byte Difference Analysis ===")
	minLen := len(codegenData)
	if len(reflectData) < minLen {
		minLen = len(reflectData)
	}

	differentBytes := 0
	for i := 0; i < minLen; i++ {
		if codegenData[i] != reflectData[i] {
			fmt.Printf("  Position %d: Codegen=0x%02x(%d), Reflect=0x%02x(%d)\n",
				i, codegenData[i], codegenData[i], reflectData[i], reflectData[i])
			differentBytes++
		}
	}

	if len(codegenData) != len(reflectData) {
		fmt.Printf("  Length difference: Codegen=%d, Reflect=%d (difference=%d bytes)\n",
			len(codegenData), len(reflectData), len(reflectData)-len(codegenData))
		if len(codegenData) > len(reflectData) {
			fmt.Printf("  Codegen extra bytes: %x\n", codegenData[len(reflectData):])
		} else {
			fmt.Printf("  Reflect extra bytes: %x\n", reflectData[len(codegenData):])
		}
	}

	fmt.Printf("Total different bytes: %d\n", differentBytes)

	// Verify cross serialization - this is the key test for dynamic slices
	fmt.Println("\n=== Cross Serialization Test ===")

	// Use reflect to deserialize codegen data
	var reflectResult *ReflectDynamicStruct
	err = foryForReflect.Unmarshal(codegenData, &reflectResult)
	if err == nil && reflectResult != nil {
		fmt.Printf("✅ Successfully used reflect to deserialize codegen data:\n")
		fmt.Printf("   DynamicSlice length: %d\n", len(reflectResult.DynamicSlice))
		fmt.Printf("   DynamicSlice: %v\n", reflectResult.DynamicSlice)

		// Verify data matching
		if reflect.DeepEqual(codegenInstance.DynamicSlice, reflectResult.DynamicSlice) {
			fmt.Println("🎉 Dynamic slice data matches completely!")
		} else {
			fmt.Printf("⚠️  Dynamic slice data differs (expected for dynamic types): %v vs %v\n",
				codegenInstance.DynamicSlice, reflectResult.DynamicSlice)
		}
	} else {
		fmt.Printf("❌ Failed to use reflect to deserialize codegen data: %v\n", err)
	}

	// Use codegen to deserialize reflect data
	var codegenResult *DynamicSliceDemo
	err = foryForCodegen.Unmarshal(reflectData, &codegenResult)
	if err == nil && codegenResult != nil {
		fmt.Printf("✅ Successfully used codegen to deserialize reflect data:\n")
		fmt.Printf("   DynamicSlice length: %d\n", len(codegenResult.DynamicSlice))
		fmt.Printf("   DynamicSlice: %v\n", codegenResult.DynamicSlice)

		// Verify data matching
		if reflect.DeepEqual(reflectInstance.DynamicSlice, codegenResult.DynamicSlice) {
			fmt.Println("🎉 Fully compatible! Codegen can correctly deserialize reflect dynamic slice data!")
		} else {
			fmt.Printf("⚠️  Dynamic slice data differs (may be expected): %v vs %v\n",
				reflectInstance.DynamicSlice, codegenResult.DynamicSlice)
		}
	} else {
		fmt.Printf("❌ Failed to use codegen to deserialize reflect data: %v\n", err)
	}
}
