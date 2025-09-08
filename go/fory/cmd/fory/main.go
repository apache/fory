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

package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/apache/fory/go/fory/codegen"
)

var (
	typeFlag    = flag.String("type", "", "comma-separated list of types to generate code for (optional if using //fory:gen comments)")
	pkgFlag     = flag.String("pkg", ".", "package directory to search for types (legacy mode)")
	fileFlag    = flag.String("file", "", "source file to generate code for (new mode)")
	forceFlag   = flag.Bool("force", false, "force regeneration by removing existing generated files first")
	helpFlag    = flag.Bool("help", false, "show help message")
	versionFlag = flag.Bool("version", false, "show version information")
)

const version = "1.0.0"

func main() {
	flag.Parse()

	if *helpFlag {
		showHelp()
		return
	}

	if *versionFlag {
		fmt.Printf("fory version %s\n", version)
		return
	}

	// Configure generator options
	opts := &codegen.GeneratorOptions{
		TypeList:   *typeFlag,
		PackageDir: *pkgFlag,
		SourceFile: *fileFlag,
		Force:      *forceFlag,
	}

	// Run the code generator with smart error handling
	if err := codegen.Run(opts); err != nil {
		// Check if this looks like a compile-time guard error
		if isCompileGuardError(err.Error()) {
			fmt.Fprintf(os.Stderr, "\n🚨 Compile-time guard detected struct changes!\n\n")
			fmt.Fprintf(os.Stderr, "It looks like you've modified a struct but haven't regenerated the code.\n")
			fmt.Fprintf(os.Stderr, "\nTo fix this, try one of these solutions:\n")
			fmt.Fprintf(os.Stderr, "  1. Run with --force flag: fory --force %s\n", getRunArguments())
			fmt.Fprintf(os.Stderr, "  2. Delete generated files and run again:\n")
			if *fileFlag != "" {
				genFile := getGeneratedFileName(*fileFlag)
				fmt.Fprintf(os.Stderr, "     rm %s && go generate\n", genFile)
			} else {
				fmt.Fprintf(os.Stderr, "     rm *_fory_gen.go && go generate\n")
			}
			fmt.Fprintf(os.Stderr, "\nThis protection ensures your generated code stays in sync with struct definitions.\n")
			os.Exit(1)
		}

		fmt.Fprintf(os.Stderr, "fory: %v\n", err)
		os.Exit(1)
	}
}

func showHelp() {
	fmt.Printf(`fory - Fast serialization code generator for Go

Usage:
  fory [options]

Options:
  -file string
        source file to generate code for (new mode)
  -pkg string
        package directory to search for types (legacy mode) (default ".")
  -type string
        comma-separated list of types to generate code for (optional if using //fory:gen comments)
  -force
        force regeneration by removing existing generated files first
  -help
        show this help message
  -version
        show version information

Examples:
  # Generate for specific file using //fory:gen comments
  fory -file structs.go

  # Generate for specific types in current package
  fory -type "User,Order"

  # Generate for specific types in a directory
  fory -pkg ./models -type "User,Order"

Installation:
  go install github.com/apache/fory/go/fory/cmd/fory

For more information, visit: https://github.com/apache/fory
`)
}

// isCompileGuardError checks if the error is due to compile-time guard conflicts or related issues
func isCompileGuardError(errMsg string) bool {
	// Look for patterns indicating compile-time guard failures
	patterns := []string{
		"cannot convert x (variable of type",
		"to type _", "_expected",
		"_expected struct",
		// Also detect panics that often occur due to stale generated code
		"panic: runtime error: invalid memory address",
		"nil pointer dereference",
		"go/types.(*Checker).handleBailout",
		"go/types.(*StdSizes).Sizeof",
		"exit status 2", // Common exit code for type checker panics
	}

	errMsgLower := strings.ToLower(errMsg)
	for _, pattern := range patterns {
		if strings.Contains(errMsgLower, strings.ToLower(pattern)) {
			return true
		}
	}
	return false
}

// getRunArguments reconstructs command line arguments for error messages
func getRunArguments() string {
	var args []string
	if *fileFlag != "" {
		args = append(args, "-file", *fileFlag)
	}
	if *typeFlag != "" {
		args = append(args, "-type", *typeFlag)
	}
	if *pkgFlag != "." {
		args = append(args, "-pkg", *pkgFlag)
	}
	return strings.Join(args, " ")
}

// getGeneratedFileName returns the expected generated file name for a source file
func getGeneratedFileName(sourceFile string) string {
	if sourceFile == "" {
		return "*_fory_gen.go"
	}

	base := strings.TrimSuffix(filepath.Base(sourceFile), ".go")
	return fmt.Sprintf("%s_fory_gen.go", base)
}
