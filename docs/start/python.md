---
title: Python Setup
sidebar_position: 2
id: python
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

`pyfory` provides binary Object Serialization and Row Format. Fory IDL can also
generate Python models and gRPC companions. The package supports Python 3.8 and
later on Linux, macOS, and Windows.

## Verify the Toolchain

```bash
python --version
python -m pip --version
```

## Object Serialization

Install the released package:

```bash
python -m pip install pyfory==1.6.1
```

Run an xlang round trip:

```python
from dataclasses import dataclass

import pyfory


@dataclass
class User:
    name: str
    age: pyfory.Int32


fory = pyfory.Fory(xlang=True, ref=True)
fory.register(User, type_id=1)

data = fory.serialize(User("Alice", 30))
decoded = fory.deserialize(data)
print(decoded)
```

Use [xlang mode](../object-serialization/python/basic-serialization.md#cross-language-interoperability) for cross-language
data. Use [native mode](../object-serialization/python/native.md) for Python-only
objects, including Python callables and serialization hooks. Continue with the
[Python guide](../object-serialization/python/index.md),
[configuration](../object-serialization/python/configuration.md), and
[type registration](../object-serialization/python/type-registration.md).

## Other Capabilities

- **Row Format** provides zero-copy field access for trusted analytical data. See [Python Row Format](../row-format/python.md).
- **Fory IDL and Compiler** generates Python models and registration helpers. See [Compiler Getting Started](../compiler/getting-started.md) and the [Python generated-code guide](../compiler/generated-code/python.md).
- **Fory gRPC** uses grpcio transports with Fory-encoded messages. See [Python gRPC](../grpc/python.md).
