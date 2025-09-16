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

package codegen

import (
	"bytes"
	"fmt"
	"go/types"
	"strings"
)

// generateCompileGuard generates compile-time checks to ensure struct definitions
// haven't changed since code generation. If a struct is modified, users must
// re-run go generate or compilation will fail.
func generateCompileGuard(structs []StructInfo, currentPkgName string) string {
	if len(structs) == 0 {
		return ""
	}

	var buf bytes.Buffer

	buf.WriteString("\n// Compile-time guards: These ensure struct definitions haven't changed\n")
	buf.WriteString("// since code generation. If you modify structs, re-run go generate.\n\n")

	for _, structInfo := range structs {
		generateStructGuard(&buf, structInfo, currentPkgName)
	}

	return buf.String()
}

func generateStructGuard(buf *bytes.Buffer, structInfo StructInfo, currentPkgName string) {
	typeName := structInfo.Name
	expectedTypeName := fmt.Sprintf("_%s_expected", typeName)

	// Generate the snapshot struct
	buf.WriteString(fmt.Sprintf("// Snapshot of %s's underlying type at generation time.\n", typeName))
	buf.WriteString(fmt.Sprintf("type %s struct {\n", expectedTypeName))

	// Use original field order to match the source struct definition
	// This ensures the guard struct has identical layout to the source
	fields := structInfo.OriginalFields

	for _, field := range fields {
		buf.WriteString(fmt.Sprintf("\t%s %s", field.GoName, formatFieldType(*field, currentPkgName)))

		// Add struct tag if present (we'll extract it from the original struct)
		// For now, skip tags - they would require access to the original AST

		buf.WriteString("\n")
	}

	buf.WriteString("}\n\n")

	// Generate the compile-time check function with better error message
	buf.WriteString(fmt.Sprintf("// Compile-time check: this conversion is legal only if %s's underlying type\n", typeName))
	buf.WriteString(fmt.Sprintf("// is identical to %s (names, order, types, tags).\n", expectedTypeName))
	buf.WriteString(fmt.Sprintf("//\n"))
	buf.WriteString(fmt.Sprintf("// COMPILE ERROR? This means you've modified the %s struct but haven't\n", typeName))
	buf.WriteString(fmt.Sprintf("// regenerated the code. To fix this:\n"))
	buf.WriteString(fmt.Sprintf("//\n"))
	buf.WriteString(fmt.Sprintf("// 1. First try:  go generate\n"))
	buf.WriteString(fmt.Sprintf("// 2. If that fails: fory --force -file structs.go\n"))
	buf.WriteString(fmt.Sprintf("// 3. Alternative: rm %s_fory_gen.go && go generate\n", strings.ToLower(typeName)))
	buf.WriteString(fmt.Sprintf("//\n"))

	buf.WriteString(fmt.Sprintf("var _ = func(x %s) {\n", typeName))
	buf.WriteString(fmt.Sprintf("\t// ERROR: %s struct has changed! See comments above for fix.\n", typeName))
	buf.WriteString(fmt.Sprintf("\t_ = %s(x)\n", expectedTypeName))
	buf.WriteString("}\n\n")
}

func formatFieldType(field FieldInfo, currentPkgName string) string {
	return formatGoType(field.Type, currentPkgName)
}

// formatGoType converts a Go type to its string representation
func formatGoType(t types.Type) string {
	switch type_ := t.(type) {
	case *types.Basic:
		return type_.Name()
	case *types.Pointer:
		return "*" + formatGoType(type_.Elem())
	case *types.Array:
		return fmt.Sprintf("[%d]%s", type_.Len(), formatGoType(type_.Elem()))
	case *types.Slice:
		return "[]" + formatGoType(type_.Elem())
	case *types.Map:
		return fmt.Sprintf("map[%s]%s", formatGoType(type_.Key()), formatGoType(type_.Elem()))
	case *types.Chan:
		dir := ""
		switch type_.Dir() {
		case types.SendOnly:
			dir = "chan<- "
		case types.RecvOnly:
			dir = "<-chan "
		default:
			dir = "chan "
		}
		return dir + formatGoType(type_.Elem())
	case *types.Named:
		// Handle named types like custom structs, interfaces, etc.
		obj := type_.Obj()
		if obj.Pkg() != nil && obj.Pkg().Name() != "" {
			// Only add package prefix if it's from a different package
			if obj.Pkg().Name() != currentPkgName {
				return obj.Pkg().Name() + "." + obj.Name()
			}
		}
		return obj.Name()
	case *types.Interface:
		if type_.Empty() {
			return "interface{}"
		}
		// For non-empty interfaces, we need to format method signatures
		var methods []string
		for i := 0; i < type_.NumMethods(); i++ {
			method := type_.Method(i)
			sig := method.Type().(*types.Signature)
			methods = append(methods, formatMethodSignature(method.Name(), sig, currentPkgName))
		}
		return fmt.Sprintf("interface { %s }", strings.Join(methods, "; "))
	case *types.Struct:
		// This shouldn't happen in field types typically, but handle it
		return "struct{...}"
	default:
		// Fallback to the type's string representation
		return t.String()
	}
}

func formatMethodSignature(name string, sig *types.Signature, currentPkgName string) string {
	var params, results []string

	// Format parameters
	if sig.Params() != nil {
		for i := 0; i < sig.Params().Len(); i++ {
			param := sig.Params().At(i)
			paramStr := formatGoType(param.Type(), currentPkgName)
			if param.Name() != "" {
				paramStr = param.Name() + " " + paramStr
			}
			params = append(params, paramStr)
		}
	}

	// Format results
	if sig.Results() != nil {
		for i := 0; i < sig.Results().Len(); i++ {
			result := sig.Results().At(i)
			resultStr := formatGoType(result.Type(), currentPkgName)
			if result.Name() != "" {
				resultStr = result.Name() + " " + resultStr
			}
			results = append(results, resultStr)
		}
	}

	paramStr := strings.Join(params, ", ")
	resultStr := strings.Join(results, ", ")

	if len(results) > 1 {
		resultStr = "(" + resultStr + ")"
	}

	if resultStr != "" {
		return fmt.Sprintf("%s(%s) %s", name, paramStr, resultStr)
	}
	return fmt.Sprintf("%s(%s)", name, paramStr)
}
