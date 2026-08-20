---
title: Build Integration
sidebar_position: 2
id: build-integration
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

## Maven (Java)

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
            <executable>foryc</executable>
            <arguments>
              <argument>${project.basedir}/src/main/fdl/schema.fdl</argument>
              <argument>--java_out</argument>
              <argument>${project.build.directory}/generated-sources/fory</argument>
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
              <source>${project.build.directory}/generated-sources/fory</source>
            </sources>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

## Gradle (Java/Kotlin)

Add to `build.gradle`:

```groovy
task generateForyTypes(type: Exec) {
    commandLine 'foryc',
        "${projectDir}/src/main/fdl/schema.fdl",
        '--java_out', "${buildDir}/generated/sources/fory"
}

compileJava.dependsOn generateForyTypes

sourceSets {
    main {
        java {
            srcDir "${buildDir}/generated/sources/fory"
        }
    }
}
```

## Python (setuptools)

Add to `setup.py` or `pyproject.toml`:

```python
# setup.py
from setuptools import setup
from setuptools.command.build_py import build_py
import subprocess

class BuildWithForyIdl(build_py):
    def run(self):
        subprocess.run([
            'foryc',
            'schema.fdl',
            '--python_out', 'src/generated'
        ], check=True)
        super().run()

setup(
    cmdclass={'build_py': BuildWithForyIdl},
    # ...
)
```

## Go (go generate)

Add to your Go file:

```go
//go:generate foryc ../schema.fdl --lang go --output .
package models
```

Run:

```bash
go generate ./...
```

## Rust (build.rs)

Add to `build.rs`:

```rust
use std::process::Command;

fn main() {
    println!("cargo:rerun-if-changed=schema.fdl");

    let status = Command::new("foryc")
        .args(&["schema.fdl", "--rust_out", "src/generated"])
        .status()
        .expect("Failed to run foryc");

    if !status.success() {
        panic!("Fory IDL compilation failed");
    }
}
```

## CMake (C++)

Add to `CMakeLists.txt`:

```cmake
find_program(FORY_COMPILER foryc)

add_custom_command(
    OUTPUT ${CMAKE_CURRENT_SOURCE_DIR}/generated/example.h
    COMMAND ${FORY_COMPILER}
        ${CMAKE_CURRENT_SOURCE_DIR}/schema.fdl
        --cpp_out ${CMAKE_CURRENT_SOURCE_DIR}/generated
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/schema.fdl
    COMMENT "Generating Fory IDL types"
)

add_custom_target(generate_fory_idl DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/generated/example.h)

add_library(mylib ...)
add_dependencies(mylib generate_fory_idl)
target_include_directories(mylib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/generated)
```

## Bazel

Create a rule in `BUILD`:

```python
genrule(
    name = "generate_fdl",
    srcs = ["schema.fdl"],
    outs = ["generated/example.h"],
    cmd = "$(location //:fory_compiler) $(SRCS) --cpp_out $(RULEDIR)/generated",
    tools = ["//:fory_compiler"],
)

cc_library(
    name = "models",
    hdrs = [":generate_fdl"],
    # ...
)
```

## Dart / Flutter

Add the Fory dependency to `pubspec.yaml`:

```yaml
dependencies:
  fory: ^1.6.1

dev_dependencies:
  build_runner: ^2.4.0
```

Generate schema types with the compiler:

```bash
foryc schema.fdl --dart_out=lib/generated
```

Then run `build_runner` to generate the serializers:

```bash
dart run build_runner build
```
