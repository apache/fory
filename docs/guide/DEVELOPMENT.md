---
title: Development
sidebar_position: 20
id: development
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

## How to build Apache Fory™

Clone the source tree from https://github.com/apache/fory.

### Build Apache Fory™ Java

```bash
cd java
mvn -T16 package
```

#### Environment Requirements

- JDK 17+
- Maven 3.6.3+

### Build Apache Fory™ Python

```bash
cd python
pip install -v -e .

# Optional: build Cython extension (replace X.Y with your Python version)
bazel build //:cp_fory_so --@rules_python//python/config_settings:python_version=X.Y
```

#### Environment Requirements

- CPython 3.8+
- Bazel 8+ (required when building Cython extensions)

### Build Apache Fory™ C++

```bash
cd cpp
bazel build //cpp/...
```

#### Environment Requirements

- C++17 compiler
- Bazel 8+

### Build Apache Fory™ Go

```bash
cd go/fory
go test -v ./...
```

Run Go xlang tests from Java test module:

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_GO_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.GoXlangTest
```

#### Environment Requirements

- Go 1.24+

### Build Apache Fory™ Rust

```bash
cd rust
cargo build
cargo test --features tests

# Debug a specific test
RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 ENABLE_FORY_DEBUG_OUTPUT=1 \
  cargo test --test mod $dir$::$test_file::$test_method -- --nocapture
```

#### Environment Requirements

- Rust toolchain via rustup
- `cargo-expand` (optional, for macro expansion debugging)

### Build Apache Fory™ JavaScript

```bash
cd javascript
npm install

npm run build
node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit
```

#### Environment Requirements

- Node.js (LTS)
- npm

### Lint Markdown Docs

```bash
cd docs
npx prettier --write "**/*.md"
```

#### Environment Requirements

- Node.js (LTS)
- npm

## Contributing

For contribution details, see [How to contribute to Apache Fory™](https://github.com/apache/fory/blob/main/CONTRIBUTING.md).
