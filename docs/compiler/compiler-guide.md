---
title: Compiler Guide
sidebar_position: 3
id: compiler_guide
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

This guide covers installation, usage, and integration of the FDL compiler.

## Installation

### From Source

```bash
cd compiler
pip install -e .
```

### Verify Installation

```bash
fory compile --help
```

## Command Line Interface

### Basic Usage

```bash
fory compile [OPTIONS] FILES...
```

### Options

| Option                                | Description                                           | Default       |
| ------------------------------------- | ----------------------------------------------------- | ------------- |
| `--lang`                              | Comma-separated target languages                      | `all`         |
| `--output`, `-o`                      | Output directory                                      | `./generated` |
| `--package`                           | Override package name from FDL file                   | (from file)   |
| `-I`, `--proto_path`, `--import_path` | Add directory to import search path (can be repeated) | (none)        |
| `--java_out=DST_DIR`                  | Generate Java code in DST_DIR                         | (none)        |
| `--python_out=DST_DIR`                | Generate Python code in DST_DIR                       | (none)        |
| `--cpp_out=DST_DIR`                   | Generate C++ code in DST_DIR                          | (none)        |
| `--go_out=DST_DIR`                    | Generate Go code in DST_DIR                           | (none)        |
| `--rust_out=DST_DIR`                  | Generate Rust code in DST_DIR                         | (none)        |
| `--go_nested_type_style`              | Go nested type naming: `camelcase` or `underscore`    | (none)        |

### Examples

**Compile for all languages:**

```bash
fory compile schema.fdl
```

**Compile for specific languages:**

```bash
fory compile schema.fdl --lang java,python
```

**Specify output directory:**

```bash
fory compile schema.fdl --output ./src/generated
```

**Override package name:**

```bash
fory compile schema.fdl --package com.myapp.models
```

**Compile multiple files:**

```bash
fory compile user.fdl order.fdl product.fdl --output ./generated
```

**Use import search paths:**

```bash
# Add a single import path
fory compile src/main.fdl -I libs/common

# Add multiple import paths (repeated option)
fory compile src/main.fdl -I libs/common -I libs/types

# Add multiple import paths (comma-separated)
fory compile src/main.fdl -I libs/common,libs/types,third_party/

# Using --proto_path (protoc-compatible alias)
fory compile src/main.fdl --proto_path=libs/common

# Mix all styles
fory compile src/main.fdl -I libs/common,libs/types --proto_path third_party/
```

**Language-specific output directories (protoc-style):**

```bash
# Generate only Java code to a specific directory
fory compile schema.fdl --java_out=./src/main/java

# Generate multiple languages to different directories
fory compile schema.fdl --java_out=./java/gen --python_out=./python/src --go_out=./go/gen

# Combine with import paths
fory compile schema.fdl --java_out=./gen/java -I proto/ -I common/
```

When using `--{lang}_out` options:

- Only the specified languages are generated (not all languages)
- Files are placed directly in the specified directory (not in a `{lang}/` subdirectory)
- This is compatible with protoc-style workflows

## Import Path Resolution

When compiling FDL files with imports, the compiler searches for imported files in this order:

1. **Relative to the importing file (default)** - The directory containing the file with the import statement is always searched first, automatically. No `-I` flag needed for same-directory imports.
2. **Each `-I` path in order** - Additional search paths specified on the command line

**Same-directory imports work automatically:**

```protobuf
// main.fdl
import "common.fdl";  // Found if common.fdl is in the same directory
```

```bash
# No -I needed for same-directory imports
fory compile main.fdl
```

**Example project structure:**

```
project/
├── src/
│   └── main.fdl          # import "common.fdl";
└── libs/
    └── common.fdl
```

**Without `-I` (fails):**

```bash
$ fory compile src/main.fdl
Import error: Import not found: common.fdl
  Searched in: /project/src
```

**With `-I` (succeeds):**

```bash
$ fory compile src/main.fdl -I libs/
Compiling src/main.fdl...
  Resolved 1 import(s)
```

## Supported Languages

| Language | Flag     | Output Extension | Description                 |
| -------- | -------- | ---------------- | --------------------------- |
| Java     | `java`   | `.java`          | POJOs with Fory annotations |
| Python   | `python` | `.py`            | Dataclasses with type hints |
| Go       | `go`     | `.go`            | Structs with struct tags    |
| Rust     | `rust`   | `.rs`            | Structs with derive macros  |
| C++      | `cpp`    | `.h`             | Structs with FORY macros    |

## Output Structure

### Java

```
generated/
└── java/
    └── com/
        └── example/
            ├── User.java
            ├── Order.java
            ├── Status.java
            └── ExampleForyRegistration.java
```

- One file per type (enum or message)
- Package structure matches FDL package
- Registration helper class generated

### Python

```
generated/
└── python/
    └── example.py
```

- Single module with all types
- Module name derived from package
- Registration function included

### Go

```
generated/
└── go/
    └── example.go
```

- Single file with all types
- Package name from last component of FDL package
- Registration function included

### Rust

```
generated/
└── rust/
    └── example.rs
```

- Single module with all types
- Module name derived from package
- Registration function included

### C++

```
generated/
└── cpp/
    └── example.h
```

- Single header file
- Namespace matches package (dots to `::`)
- Header guards and forward declarations

## Build Integration

### Maven (Java)

Add to your `pom.xml`:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>exec-maven-plugin</artifactId>
      <version>3.1.0</version>
      <executions>
        <execution>
          <id>generate-fory-types</id>
          <phase>generate-sources</phase>
          <goals>
            <goal>exec</goal>
          </goals>
          <configuration>
            <executable>fory</executable>
            <arguments>
              <argument>compile</argument>
              <argument>${project.basedir}/src/main/fdl/schema.fdl</argument>
              <argument>--lang</argument>
              <argument>java</argument>
              <argument>--output</argument>
              <argument>${project.build.directory}/generated-sources/fdl</argument>
            </arguments>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

Add generated sources:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.codehaus.mojo</groupId>
      <artifactId>build-helper-maven-plugin</artifactId>
      <version>3.4.0</version>
      <executions>
        <execution>
          <phase>generate-sources</phase>
          <goals>
            <goal>add-source</goal>
          </goals>
          <configuration>
            <sources>
              <source>${project.build.directory}/generated-sources/fdl</source>
            </sources>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

### Gradle (Java/Kotlin)

Add to `build.gradle`:

```groovy
task generateForyTypes(type: Exec) {
    commandLine 'fory', 'compile',
        "${projectDir}/src/main/fdl/schema.fdl",
        '--lang', 'java',
        '--output', "${buildDir}/generated/sources/fdl"
}

compileJava.dependsOn generateForyTypes

sourceSets {
    main {
        java {
            srcDir "${buildDir}/generated/sources/fdl/java"
        }
    }
}
```

### Python (setuptools)

Add to `setup.py` or `pyproject.toml`:

```python
# setup.py
from setuptools import setup
from setuptools.command.build_py import build_py
import subprocess

class BuildWithFdl(build_py):
    def run(self):
        subprocess.run([
            'fory', 'compile',
            'schema.fdl',
            '--lang', 'python',
            '--output', 'src/generated'
        ], check=True)
        super().run()

setup(
    cmdclass={'build_py': BuildWithFdl},
    # ...
)
```

### Go (go generate)

Add to your Go file:

```go
//go:generate fory compile ../schema.fdl --lang go --output .
package models
```

Run:

```bash
go generate ./...
```

### Rust (build.rs)

Add to `build.rs`:

```rust
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=schema.fdl");

    let status = Command::new("fory")
        .args(&["compile", "schema.fdl", "--lang", "rust", "--output", "src/generated"])
        .status()
        .expect("Failed to run fory compiler");

    if !status.success() {
        panic!("FDL compilation failed");
    }
}
```

### CMake (C++)

Add to `CMakeLists.txt`:

```cmake
find_program(FORY_COMPILER fory)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_SOURCE_DIR}/generated/example.h
    COMMAND ${FORY_COMPILER} compile
        ${CMAKE_CURRENT_SOURCE_DIR}/schema.fdl
        --lang cpp
        --output ${CMAKE_CURRENT_SOURCE_DIR}/generated
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/schema.fdl
    COMMENT "Generating FDL types"
)

add_custom_target(generate_fdl DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/generated/example.h)

add_library(mylib ...)
add_dependencies(mylib generate_fdl)
target_include_directories(mylib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/generated)
```

### Bazel

Create a rule in `BUILD`:

```python
genrule(
    name = "generate_fdl",
    srcs = ["schema.fdl"],
    outs = ["generated/example.h"],
    cmd = "$(location //:fory_compiler) compile $(SRCS) --lang cpp --output $(RULEDIR)/generated",
    tools = ["//:fory_compiler"],
)

cc_library(
    name = "models",
    hdrs = [":generate_fdl"],
    # ...
)
```

## Error Handling

### Syntax Errors

```
Error: Line 5, Column 12: Expected ';' after field declaration
```

Fix: Check the indicated line for missing semicolons or syntax issues.

### Duplicate Type Names

```
Error: Duplicate type name: User
```

Fix: Ensure each enum and message has a unique name within the file.

### Duplicate Type IDs

```
Error: Duplicate type ID 100: User and Order
```

Fix: Assign unique type IDs to each type.

### Unknown Type References

```
Error: Unknown type 'Address' in Customer.address
```

Fix: Define the referenced type before using it, or check for typos.

### Duplicate Field Numbers

```
Error: Duplicate field number 1 in User: name and id
```

Fix: Assign unique field numbers within each message.

## Best Practices

### Project Structure

```
project/
├── fdl/
│   ├── common.fdl       # Shared types
│   ├── user.fdl         # User domain
│   └── order.fdl        # Order domain
├── src/
│   └── generated/       # Generated code (git-ignored)
└── build.gradle
```

### Version Control

- **Track**: FDL schema files
- **Ignore**: Generated code (can be regenerated)

Add to `.gitignore`:

```
# Generated FDL code
src/generated/
generated/
```

### CI/CD Integration

Always regenerate during builds:

```yaml
# GitHub Actions example
steps:
  - name: Install FDL Compiler
    run: pip install ./compiler

  - name: Generate Types
    run: fory compile fdl/*.fdl --output src/generated

  - name: Build
    run: ./gradlew build
```

### Schema Evolution

When modifying schemas:

1. **Never reuse field numbers** - Mark as reserved instead
2. **Never change type IDs** - They're part of the binary format
3. **Add new fields** - Use new field numbers
4. **Use `optional`** - For backward compatibility

```protobuf
message User [id=100] {
    string id = 1;
    string name = 2;
    // Field 3 was removed, don't reuse
    optional string email = 4;  // New field
}
```

## Troubleshooting

### Command Not Found

```
fory: command not found
```

**Solution:** Ensure the compiler is installed and in your PATH:

```bash
pip install -e ./compiler
# Or add to PATH
export PATH=$PATH:~/.local/bin
```

### Permission Denied

```
Permission denied: ./generated
```

**Solution:** Ensure write permissions on the output directory:

```bash
chmod -R u+w ./generated
```

### Import Errors in Generated Code

**Java:** Ensure Fory dependency is in your project:

```xml
<dependency>
  <groupId>org.apache.fory</groupId>
  <artifactId>fory-core</artifactId>
  <version>0.14.1</version>
</dependency>
```

**Python:** Ensure pyfory is installed:

```bash
pip install pyfory
```

**Go:** Ensure fory module is available:

```bash
go get github.com/apache/fory/go/fory
```

**Rust:** Ensure fory crate is in `Cargo.toml`:

```toml
[dependencies]
fory = "0.13"
```

**C++:** Ensure Fory headers are in include path.
