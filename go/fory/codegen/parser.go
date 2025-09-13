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
	"fmt"
	"go/ast"
	"go/token"
	"go/types"
	"path/filepath"
	"strings"

	"golang.org/x/tools/go/packages"
)

// discoverTypesFromFile scans the source file for //fory:gen comments
func discoverTypesFromFile(pkg *packages.Package, sourceFile string) ([]string, error) {
	var discoveredTypes []string

	// Find the syntax tree for the specific file
	for _, file := range pkg.Syntax {
		// Get the file's position and convert to absolute path for comparison
		filename := pkg.Fset.Position(file.Pos()).Filename
		absFilename, err := filepath.Abs(filename)
		if err != nil {
			continue
		}

		if absFilename != sourceFile {
			continue
		}

		// Scan for type declarations with //fory:gen comments
		for _, decl := range file.Decls {
			if genDecl, ok := decl.(*ast.GenDecl); ok && genDecl.Tok == token.TYPE {
				for _, spec := range genDecl.Specs {
					if typeSpec, ok := spec.(*ast.TypeSpec); ok {
						// Check if it's a struct type
						if _, ok := typeSpec.Type.(*ast.StructType); ok {
							// Look for //fory:gen comment
							if hasGenerateComment(genDecl.Doc) || hasGenerateComment(typeSpec.Doc) {
								discoveredTypes = append(discoveredTypes, typeSpec.Name.Name)
							}
						}
					}
				}
			}
		}
	}

	return discoveredTypes, nil
}

// hasGenerateComment checks if comment group contains //fory:gen
func hasGenerateComment(commentGroup *ast.CommentGroup) bool {
	if commentGroup == nil {
		return false
	}

	for _, comment := range commentGroup.List {
		if strings.Contains(comment.Text, "fory:gen") {
			return true
		}
	}
	return false
}

// extractStructInfo extracts metadata from a struct type
func extractStructInfo(name string, structType *types.Struct) (*StructInfo, error) {
	var fields []*FieldInfo

	for i := 0; i < structType.NumFields(); i++ {
		field := structType.Field(i)
		if !field.Exported() {
			continue // Skip unexported fields
		}

		fieldInfo, err := analyzeField(field, i)
		if err != nil {
			return nil, fmt.Errorf("analyzing field %s: %w", field.Name(), err)
		}

		if fieldInfo == nil {
			continue // Skip unsupported fields
		}

		fields = append(fields, fieldInfo)
	}

	// Keep a copy of original field order for guard generation
	originalFields := make([]*FieldInfo, len(fields))
	copy(originalFields, fields)

	// Sort fields according to Fory protocol for serialization
	sortFields(fields)

	return &StructInfo{
		Name:           name,
		Fields:         fields,         // Sorted for serialization
		OriginalFields: originalFields, // Original order for guard
	}, nil
}

// parseStructsFromPackage finds and parses structs from a package
func parseStructsFromPackage(pkg *packages.Package, targetTypes []string) ([]*StructInfo, error) {
	var structs []*StructInfo

	// Check if package has types
	if pkg.Types == nil {
		return nil, fmt.Errorf("package %s has no type information", pkg.PkgPath)
	}

	// Iterate through all types in the package
	scope := pkg.Types.Scope()
	allNames := scope.Names()

	for _, name := range allNames {
		obj := scope.Lookup(name)
		if obj == nil {
			continue
		}

		// Check if it's a named type
		named, ok := obj.Type().(*types.Named)
		if !ok {
			continue
		}

		// Check if underlying type is struct
		structType, ok := named.Underlying().(*types.Struct)
		if !ok {
			continue
		}

		// Check if we should generate code for this type
		shouldGenerate := false
		if len(targetTypes) > 0 {
			for _, t := range targetTypes {
				if strings.TrimSpace(t) == name {
					shouldGenerate = true
					break
				}
			}
		}

		if !shouldGenerate {
			continue
		}

		// Extract struct information
		structInfo, err := extractStructInfo(name, structType)
		if err != nil {
			return nil, fmt.Errorf("extracting struct info for %s: %w", name, err)
		}

		structs = append(structs, structInfo)
	}

	return structs, nil
}
