---
title: Go Setup
sidebar_position: 4
id: go
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

Fory Go provides xlang and native Object Serialization, generated models, and
Fory gRPC. It is published as `github.com/apache/fory/go/fory` and requires Go
1.25 or later.

## Verify the Toolchain

```bash
go version
go env GOPROXY
```

## Object Serialization

Create a module and install the released Fory module:

```bash
mkdir fory-example
cd fory-example
go mod init example.com/fory-example
go get github.com/apache/fory/go/fory@v1.6.0
```

If a Go proxy has not picked up a new submodule tag yet, retry later or use
`GOPROXY=direct` temporarily.

```go title="main.go"
package main

import (
    "fmt"

    "github.com/apache/fory/go/fory"
)

type User struct {
    ID   int64
    Name string
}

func main() {
    f := fory.New(fory.WithXlang(true))
    if err := f.RegisterStruct(User{}, 1); err != nil {
        panic(err)
    }

    bytes, err := f.Serialize(&User{ID: 1, Name: "Alice"})
    if err != nil {
        panic(err)
    }

    var decoded User
    if err := f.Deserialize(bytes, &decoded); err != nil {
        panic(err)
    }
    fmt.Println(decoded.Name)
}
```

```bash
go run .
```

Use [xlang mode](../object-serialization/go/basic-serialization.md#cross-language-interoperability) for cross-language data
and [native mode](../object-serialization/go/native.md) for Go-only data.
Continue with [Go Object Serialization](../object-serialization/go/index.md),
[configuration](../object-serialization/go/configuration.md), and
[schema evolution](../object-serialization/go/schema-evolution.md).

## Other Capabilities

- **Fory IDL and Compiler** generates Go models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [Go generated-code guide](../compiler/generated-code/go.md).
- **Fory gRPC** uses grpc-go transports with Fory-encoded messages. See [Go gRPC](../grpc/go.md).
