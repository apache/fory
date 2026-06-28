---
title: Type Registration
sidebar_position: 5
id: type_registration
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

This page covers Python type registration APIs. Use [Configuration](configuration.md#security) for
strict-mode policy, max-depth limits, and trusted-data guidance.

## Type Registration

Register xlang classes by type name so other languages can resolve the same
schema identity:

```python
from dataclasses import dataclass
import pyfory

fory = pyfory.Fory(xlang=True, strict=True)

@dataclass
class User:
    name: str
    age: pyfory.Int32

fory.register(User, name="example.User")
```

For Python native mode, numeric type IDs are the compact same-language
registration path:

```python
import pyfory

fory = pyfory.Fory(xlang=False, strict=True)
fory.register(MyClass, type_id=100)
```

## Registration Patterns

Use the registration form that matches the payload contract:

```python
# Xlang: stable name identity
fory.register(MyClass, name="com.example.MyClass")

# Native mode: compact numeric identity
fory.register(MyClass, type_id=100)

# Custom serializer
fory.register(MyClass, type_id=100, serializer=MySerializer(fory.type_resolver, MyClass))

# Batch registration
type_id = 100
for model_class in [User, Order, Product, Invoice]:
    fory.register(model_class, type_id=type_id)
    type_id += 1
```

## Strict Mode Relationship

With `strict=True`, deserialization accepts only registered types. Register all
application classes before serializing or deserializing payloads, and keep the
same registration IDs or names on every peer that shares those payloads.

## Related Topics

- [Configuration](configuration.md) - Fory parameters
- [Configuration](configuration.md#security) - Strict mode, deserialization policies, and maximum read depth
- [Custom Serializers](custom-serializers.md) - Custom serialization
