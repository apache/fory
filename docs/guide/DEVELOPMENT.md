---
title: Development
sidebar_position: 8
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

Please checkout the source tree from https://github.com/apache/fory.

### Build Apache Fory™ Java

```bash
cd java
mvn clean compile -DskipTests
```

#### Environment Requirements

- java 1.8+
- maven 3.6.3+

### Build Apache Fory™ Python

```bash
cd python
# Uninstall numpy first so that when we install pyarrow, it will install the correct numpy version automatically.
# For Python versions less than 3.13, numpy 2 is not currently supported.
pip uninstall -y numpy
# Install necessary environment for Python < 3.13.
pip install pyarrow==15.0.0 Cython wheel pytest
# For Python 3.13, pyarrow 18.0.0 is available and requires numpy version greater than 2.
# pip install pyarrow==18.0.0 Cython wheel pytest
pip install -v -e .
```

#### Environment Requirements

- python 3.6+

### Build Apache Fory™ C++

Build fory row format：

```bash
pip install pyarrow==15.0.0
bazel build //cpp/fory/row:fory_row_format
```

Build fory row format encoder:

```bash
pip install pyarrow==15.0.0
bazel build //cpp/fory/encoder:fory_encoder
```

#### Environment Requirements

- compilers with C++17 support
- bazel 6.3.2

### Build Apache Fory™ GoLang

```bash
cd go/fory
# run test
go test -v
# run xlang test
go test -v fory_xlang_test.go
```

#### Environment Requirements

- go 1.13+

### Build Apache Fory™ Rust

```bash
cd rust
# build
cargo build
# run test
cargo test
# run specific test
cargo test -p fory-tests --test $test_file $test_method
```

#### Environment Requirements

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
```

### Build Apache Fory™ JavaScript

```bash
cd javascript
npm install

# run build
npm run build
# run test
npm run test
```

#### Environment Requirements

- node 14+
- npm 8+

### Lint Markdown Docs

```bash
# Install prettier globally
npm install -g prettier

# Fix markdown files
prettier --write "**/*.md"
```

#### Environment Requirements

- node 14+
- npm 8+

## Contributing

For more information, please refer to [How to contribute to Apache Fory™](https://github.com/apache/fory/blob/main/CONTRIBUTING.md).
