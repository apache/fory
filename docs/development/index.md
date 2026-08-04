---
title: Build and Test
sidebar_position: 1
id: index
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

Contributor documentation covers repository setup, builds, tests, debugging, and release-oriented
workflows. It is separate from the user guides for released artifacts.

Clone the source tree from https://github.com/apache/fory.

## Java

```bash
cd java
mvn -T16 package
```

Requirements:

- JDK 17+
- Maven 3.6.3+

## Python

```bash
cd python
pip install -v -e .

# Optional: build Cython extension (replace X.Y with your Python version)
bazel build //:cp_fory_so --@rules_python//python/config_settings:python_version=X.Y
```

Requirements:

- CPython 3.8+
- Bazel 8+ (required when building Cython extensions)

## C++

```bash
cd cpp
bazel build //cpp/...
```

Requirements:

- C++17 compiler
- Bazel 8+

## Go

```bash
cd go/fory
go test -v ./...
```

Run Go xlang tests from the Java test module:

```bash
cd java
mvn -T16 install -DskipTests
cd fory-core
FORY_GO_JAVA_CI=1 ENABLE_FORY_DEBUG_OUTPUT=1 mvn test -Dtest=org.apache.fory.xlang.GoXlangTest
```

Requirement: Go 1.24+.

## Rust

```bash
cd rust
cargo build
cargo test --features tests

# Debug a specific test
RUST_BACKTRACE=1 FORY_PANIC_ON_ERROR=1 ENABLE_FORY_DEBUG_OUTPUT=1 \
  cargo test --test mod $dir$::$test_file::$test_method -- --nocapture
```

Requirements:

- Rust toolchain via rustup
- `cargo-expand` (optional, for macro expansion debugging)

## JavaScript

```bash
cd javascript
npm install

npm run build
node ./node_modules/.bin/jest --ci --reporters=default --reporters=jest-junit
```

Requirements:

- Node.js (LTS)
- npm

## Markdown

```bash
cd docs
npx prettier --write "**/*.md"
```

Requirements:

- Node.js (LTS)
- npm

## Debugging

See [Debugging C++](cpp-debugging.md) for the repository's VS Code, Bazel, LLDB, and GDB setup.

Language-specific contributor instructions remain in each language's source tree and contributor
README when they are not part of the shared repository build.

## Contributing

For contribution details, see
[How to contribute to Apache Fory™](https://github.com/apache/fory/blob/main/CONTRIBUTING.md).
For AI-assisted contributions, follow the
[AI Contribution Policy](https://github.com/apache/fory/blob/main/AI_POLICY.md), including the
required self-review, two-reviewer AI review loop, disclosure, and verification evidence for
substantial AI assistance.
