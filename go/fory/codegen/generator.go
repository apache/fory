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
	"go/format"
	"io/ioutil"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/tools/go/packages"
)

// GeneratorOptions contains configuration for the code generator
type GeneratorOptions struct {
	TypeList   string // comma-separated list of types to generate code for
	PackageDir string // package directory to search for types
	SourceFile string // source file to generate code for (new mode)
	Force      bool   // force regeneration by removing existing files first
}

// Run executes the code generator with the given options
func Run(opts *GeneratorOptions) error {
	// If force flag is set, clean up existing files first
	if opts.Force {
		log.Printf("Force flag detected, cleaning up existing generated files...")
		if cleanupErr := cleanupGeneratedFiles(opts); cleanupErr != nil {
			log.Printf("Warning: Failed to cleanup generated files: %v", cleanupErr)
		}
	}

	err := run(opts)

	// Check if the error is due to compile-time guard conflicts
	if err != nil && isCompileGuardError(err.Error()) {
		log.Printf("Detected compile-time guard conflict. Attempting to regenerate...")

		// Try to clean up and regenerate
		if cleanupErr := cleanupGeneratedFiles(opts); cleanupErr != nil {
			log.Printf("Warning: Failed to cleanup generated files: %v", cleanupErr)
		}

		// Retry generation
		log.Printf("Retrying code generation...")
		return run(opts)
	}

	return err
}

func run(opts *GeneratorOptions) error {
	// Determine mode: file-based or package-based
	if opts.SourceFile != "" {
		return runFileMode(opts)
	}
	return runPackageMode(opts)
}

func runFileMode(opts *GeneratorOptions) error {
	// Load packages including the specific file
	cfg := &packages.Config{
		Mode: packages.NeedTypes | packages.NeedSyntax | packages.NeedName | packages.NeedFiles | packages.NeedTypesInfo,
	}

	// Load the directory containing the file
	dir := filepath.Dir(opts.SourceFile)
	if dir == "" {
		dir = "."
	}

	pkgs, err := packages.Load(cfg, dir)
	if err != nil {
		return fmt.Errorf("loading packages: %w", err)
	}

	if len(pkgs) == 0 {
		return fmt.Errorf("no packages found")
	}

	if packages.PrintErrors(pkgs) > 0 {
		// Check if any errors are compile-time guard related
		var allErrors []string
		for _, pkg := range pkgs {
			for _, err := range pkg.Errors {
				allErrors = append(allErrors, err.Error())
			}
		}
		errorMsg := strings.Join(allErrors, "; ")

		// If this looks like a compile-time guard error, provide better context
		if isCompileGuardError(errorMsg) {
			return fmt.Errorf("compile-time guard detected struct changes: %s", errorMsg)
		}

		return fmt.Errorf("errors in packages")
	}

	// Process only the specified file
	for _, pkg := range pkgs {
		if err := processPackageFile(pkg, opts.SourceFile, opts.TypeList); err != nil {
			return fmt.Errorf("processing file %s: %w", opts.SourceFile, err)
		}
	}

	return nil
}

func runPackageMode(opts *GeneratorOptions) error {
	// Legacy package-based mode
	cfg := &packages.Config{
		Mode: packages.NeedTypes | packages.NeedSyntax | packages.NeedName | packages.NeedFiles | packages.NeedTypesInfo,
	}

	pkgs, err := packages.Load(cfg, opts.PackageDir)
	if err != nil {
		return fmt.Errorf("loading packages: %w", err)
	}

	if len(pkgs) == 0 {
		return fmt.Errorf("no packages found")
	}

	if packages.PrintErrors(pkgs) > 0 {
		// Check if any errors are compile-time guard related
		var allErrors []string
		for _, pkg := range pkgs {
			for _, err := range pkg.Errors {
				allErrors = append(allErrors, err.Error())
			}
		}
		errorMsg := strings.Join(allErrors, "; ")

		// If this looks like a compile-time guard error, provide better context
		if isCompileGuardError(errorMsg) {
			return fmt.Errorf("compile-time guard detected struct changes: %s", errorMsg)
		}

		return fmt.Errorf("errors in packages")
	}

	// Process each package (legacy behavior)
	for _, pkg := range pkgs {
		if err := processPackage(pkg, opts.TypeList); err != nil {
			return fmt.Errorf("processing package %s: %w", pkg.PkgPath, err)
		}
	}

	return nil
}

func processPackageFile(pkg *packages.Package, sourceFile string, typeList string) error {
	// Convert to absolute path for comparison
	absSourceFile, err := filepath.Abs(sourceFile)
	if err != nil {
		return fmt.Errorf("getting absolute path for %s: %w", sourceFile, err)
	}

	// Find target types from the specific file
	var targetTypes []string

	// If type list is provided, use it
	if typeList != "" {
		targetTypes = strings.Split(typeList, ",")
	} else {
		// Auto-discover types with //fory:gen comments
		discoveredTypes, err := discoverTypesFromFile(pkg, absSourceFile)
		if err != nil {
			return fmt.Errorf("discovering types from file: %w", err)
		}
		targetTypes = discoveredTypes
	}

	if len(targetTypes) == 0 {
		fmt.Printf("No types found to generate in %s\n", sourceFile)
		return nil
	}

	// Also check if there are any compilation errors
	if len(pkg.Errors) > 0 {
		for _, err := range pkg.Errors {
			log.Printf("package error: %s", err)
		}
	}

	// Parse structs from package
	structs, err := parseStructsFromPackage(pkg, targetTypes)
	if err != nil {
		return fmt.Errorf("parsing structs from package: %w", err)
	}

	if len(structs) == 0 {
		if len(targetTypes) > 0 {
			scope := pkg.Types.Scope()
			allNames := scope.Names()
			log.Printf("Warning: No matching structs found for target types: %v", targetTypes)
			log.Printf("Available types in package: %v", allNames)
			return fmt.Errorf("no matching structs found for target types: %v", targetTypes)
		}
		log.Printf("No structs to generate (no target types specified)")
		return nil
	}

	// Generate code with file-based naming
	log.Printf("Generating code for %d struct(s) from %s: %v", len(structs), sourceFile, getStructNames(structs))
	if err := generateCodeForFile(pkg, structs, sourceFile); err != nil {
		return err
	}
	log.Printf("Successfully generated code for %s", sourceFile)
	return nil
}

func processPackage(pkg *packages.Package, typeList string) error {
	// Find structs to generate code for
	var targetTypes []string
	if typeList != "" {
		targetTypes = strings.Split(typeList, ",")
	}

	// Also check if there are any compilation errors
	if len(pkg.Errors) > 0 {
		for _, err := range pkg.Errors {
			log.Printf("package error: %s", err)
		}
	}

	// Parse structs from package
	structs, err := parseStructsFromPackage(pkg, targetTypes)
	if err != nil {
		return fmt.Errorf("parsing structs from package: %w", err)
	}

	if len(structs) == 0 {
		if len(targetTypes) > 0 {
			scope := pkg.Types.Scope()
			allNames := scope.Names()
			log.Printf("Warning: No matching structs found for target types: %v", targetTypes)
			log.Printf("Available types in package: %v", allNames)
			return fmt.Errorf("no matching structs found for target types: %v", targetTypes)
		}
		log.Printf("No structs to generate (no target types specified)")
		return nil
	}

	// Generate code (legacy package mode)
	log.Printf("Generating code for %d struct(s): %v", len(structs), getStructNames(structs))
	if err := generateCode(pkg, structs); err != nil {
		return err
	}
	log.Printf("Successfully generated code for package %s", pkg.Name)
	return nil
}

// generateCodeForFile generates code with file-based naming
func generateCodeForFile(pkg *packages.Package, structs []*StructInfo, sourceFile string) error {
	var buf bytes.Buffer

	// Generate file header
	fmt.Fprintf(&buf, "// Code generated by forygen. DO NOT EDIT.\n")
	fmt.Fprintf(&buf, "// source: %s\n", sourceFile)
	fmt.Fprintf(&buf, "// generated at: %s\n\n", time.Now().Format(time.RFC3339))
	fmt.Fprintf(&buf, "package %s\n\n", pkg.Name)

	// Determine which imports are needed
	needsTime := false
	needsReflect := false

	for _, s := range structs {
		for _, field := range s.Fields {
			typeStr := field.Type.String()
			if typeStr == "time.Time" || typeStr == "github.com/apache/fory/go/fory.Date" {
				needsTime = true
			}
			// We need reflect for the interface compatibility methods
			needsReflect = true
		}
	}

	// Generate imports
	fmt.Fprintf(&buf, "import (\n")
	fmt.Fprintf(&buf, "\t\"fmt\"\n")
	if needsReflect {
		fmt.Fprintf(&buf, "\t\"reflect\"\n")
	}
	if needsTime {
		fmt.Fprintf(&buf, "\t\"time\"\n")
	}
	fmt.Fprintf(&buf, "\t\"github.com/apache/fory/go/fory\"\n")
	fmt.Fprintf(&buf, ")\n\n")

	// Generate init function to register serializer factories
	fmt.Fprintf(&buf, "func init() {\n")
	for _, s := range structs {
		fmt.Fprintf(&buf, "\tfory.RegisterSerializerFactory((*%s)(nil), NewSerializerFor_%s)\n", s.Name, s.Name)
	}
	fmt.Fprintf(&buf, "}\n\n")

	// Generate serializers for each struct
	for _, s := range structs {
		if err := generateStructSerializer(&buf, s); err != nil {
			return fmt.Errorf("generating serializer for %s: %w", s.Name, err)
		}
	}

	// Generate compile-time guards to ensure struct definitions haven't changed
	structInfos := convertStructInfos(structs)
	guardCode := generateCompileGuard(structInfos, pkg.Name)
	if guardCode != "" {
		buf.WriteString(guardCode)
	}

	// Format the generated code
	formatted, err := format.Source(buf.Bytes())
	if err != nil {
		return fmt.Errorf("formatting generated code: %w", err)
	}

	// Create output filename based on source file: filename_fory_gen.go
	base := strings.TrimSuffix(filepath.Base(sourceFile), ".go")
	outputFile := filepath.Join(filepath.Dir(sourceFile), fmt.Sprintf("%s_fory_gen.go", base))

	return ioutil.WriteFile(outputFile, formatted, 0644)
}

// convertStructInfos converts []*StructInfo to []StructInfo
func convertStructInfos(structs []*StructInfo) []StructInfo {
	result := make([]StructInfo, len(structs))
	for i, s := range structs {
		result[i] = *s
	}
	return result
}

// isCompileGuardError checks if the error is due to compile-time guard conflicts
func isCompileGuardError(errMsg string) bool {
	// Look for patterns indicating compile-time guard failures
	patterns := []string{
		"cannot convert x (variable of type",
		"to type _", "_expected",
		"_expected struct",
	}

	errMsgLower := strings.ToLower(errMsg)
	for _, pattern := range patterns {
		if strings.Contains(errMsgLower, strings.ToLower(pattern)) {
			return true
		}
	}
	return false
}

// cleanupGeneratedFiles removes generated files to allow regeneration
func cleanupGeneratedFiles(opts *GeneratorOptions) error {
	if opts.SourceFile != "" {
		// File-based mode: remove filename_fory_gen.go
		base := strings.TrimSuffix(filepath.Base(opts.SourceFile), ".go")
		genFile := filepath.Join(filepath.Dir(opts.SourceFile), fmt.Sprintf("%s_fory_gen.go", base))

		if _, err := os.Stat(genFile); err == nil {
			log.Printf("Removing generated file: %s", genFile)
			return os.Remove(genFile)
		}
	} else {
		// Package-based mode: need to load package to find generated file
		// This is more complex and might need package analysis
		log.Printf("Package-based cleanup not yet implemented")
	}

	return nil
}

// generateStructSerializer generates a complete serializer for a struct
func generateStructSerializer(buf *bytes.Buffer, s *StructInfo) error {
	// Generate struct serializer type
	fmt.Fprintf(buf, "type %s_ForyGenSerializer struct {}\n\n", s.Name)

	// Generate factory function
	fmt.Fprintf(buf, "func NewSerializerFor_%s() fory.Serializer {\n", s.Name)
	fmt.Fprintf(buf, "\treturn %s_ForyGenSerializer{}\n", s.Name)
	fmt.Fprintf(buf, "}\n\n")

	// Generate TypeId method
	fmt.Fprintf(buf, "func (%s_ForyGenSerializer) TypeId() fory.TypeId {\n", s.Name)
	fmt.Fprintf(buf, "\treturn fory.NAMED_STRUCT\n")
	fmt.Fprintf(buf, "}\n\n")

	// Generate NeedWriteRef method
	fmt.Fprintf(buf, "func (%s_ForyGenSerializer) NeedWriteRef() bool {\n", s.Name)
	fmt.Fprintf(buf, "\treturn true\n")
	fmt.Fprintf(buf, "}\n\n")

	// Generate strongly-typed Write method (delegate to encoder)
	if err := generateWriteTyped(buf, s); err != nil {
		return err
	}

	// Generate strongly-typed Read method (delegate to decoder)
	if err := generateReadTyped(buf, s); err != nil {
		return err
	}

	// Generate interface compatibility methods (delegate to encoder/decoder)
	if err := generateWriteInterface(buf, s); err != nil {
		return err
	}

	if err := generateReadInterface(buf, s); err != nil {
		return err
	}

	return nil
}

// generateCode generates code with package-based naming (legacy mode)
func generateCode(pkg *packages.Package, structs []*StructInfo) error {
	var buf bytes.Buffer

	// Generate file header
	fmt.Fprintf(&buf, "// Code generated by forygen. DO NOT EDIT.\n")
	fmt.Fprintf(&buf, "// source: %s\n", pkg.PkgPath)
	fmt.Fprintf(&buf, "// generated at: %s\n\n", time.Now().Format(time.RFC3339))
	fmt.Fprintf(&buf, "package %s\n\n", pkg.Name)

	// Determine which imports are needed
	needsTime := false
	needsReflect := false

	for _, s := range structs {
		for _, field := range s.Fields {
			typeStr := field.Type.String()
			if typeStr == "time.Time" || typeStr == "github.com/apache/fory/go/fory.Date" {
				needsTime = true
			}
			// We need reflect for the interface compatibility methods
			needsReflect = true
		}
	}

	// Generate imports
	fmt.Fprintf(&buf, "import (\n")
	fmt.Fprintf(&buf, "\t\"fmt\"\n")
	if needsReflect {
		fmt.Fprintf(&buf, "\t\"reflect\"\n")
	}
	if needsTime {
		fmt.Fprintf(&buf, "\t\"time\"\n")
	}
	fmt.Fprintf(&buf, "\t\"github.com/apache/fory/go/fory\"\n")
	fmt.Fprintf(&buf, ")\n\n")

	// Generate init function to register serializer factories
	fmt.Fprintf(&buf, "func init() {\n")
	for _, s := range structs {
		fmt.Fprintf(&buf, "\tfory.RegisterSerializerFactory((*%s)(nil), NewSerializerFor_%s)\n", s.Name, s.Name)
	}
	fmt.Fprintf(&buf, "}\n\n")

	// Generate serializers for each struct
	for _, s := range structs {
		if err := generateStructSerializer(&buf, s); err != nil {
			return fmt.Errorf("generating serializer for %s: %w", s.Name, err)
		}
	}

	// Generate compile-time guards to ensure struct definitions haven't changed
	structInfos := convertStructInfos(structs)
	guardCode := generateCompileGuard(structInfos, pkg.Name)
	if guardCode != "" {
		buf.WriteString(guardCode)
	}

	// Format the generated code
	formatted, err := format.Source(buf.Bytes())
	if err != nil {
		return fmt.Errorf("formatting generated code: %w", err)
	}

	// Write to output file (legacy package-based naming)
	outputFile := filepath.Join(filepath.Dir(pkg.GoFiles[0]), fmt.Sprintf("%s_fory_gen.go", pkg.Name))
	return ioutil.WriteFile(outputFile, formatted, 0644)
}
