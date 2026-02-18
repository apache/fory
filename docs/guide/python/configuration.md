---
title: Configuration
sidebar_position: 1
id: configuration
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

This page covers Fory configuration parameters and language modes.

## Fory Class

The main serialization interface:

```python
class Fory:
    def __init__(
        self,
        xlang: bool = False,
        ref: bool = False,
        strict: bool = True,
        compatible: bool = False,
        max_depth: int = 50,
        policy: DeserializationPolicy = None,
        field_nullable: bool = False,
        meta_compressor=None,
    )
```

## ThreadSafeFory Class

Thread-safe serialization interface using a pooled wrapper:

```python
class ThreadSafeFory:
    def __init__(
        self, fory_factory=None, **kwargs
    )
```

## Parameters

| Parameter         | Type                            | Default | Description                                                                                                                                                                                |
| ----------------- | ------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `xlang`           | `bool`                          | `False` | Enable cross-language serialization. When `False`, enables Python-native mode supporting all Python objects. When `True`, enables cross-language mode compatible with Java, Go, Rust, etc. |
| `ref`             | `bool`                          | `False` | Enable reference tracking for shared/circular references. Disable for better performance if your data has no shared references.                                                            |
| `strict`          | `bool`                          | `True`  | Require type registration for security. **Highly recommended** for production. Only disable in trusted environments.                                                                       |
| `compatible`      | `bool`                          | `False` | Enable schema evolution in cross-language mode, allowing fields to be added/removed while maintaining compatibility.                                                                       |
| `max_depth`       | `int`                           | `50`    | Maximum deserialization depth for security, preventing stack overflow attacks.                                                                                                             |
| `policy`          | `DeserializationPolicy \| None` | `None`  | Deserialization policy used for security checks. Strongly recommended when `strict=False`.                                                                                                 |
| `field_nullable`  | `bool`                          | `False` | Treat dataclass fields as nullable by default (regardless of `xlang`).                                                                                                                     |
| `meta_compressor` | `Any`                           | `None`  | Optional metadata compressor used for compatible-mode metadata encoding.                                                                                                                   |
| `fory_factory`    | `Callable \| None`              | `None`  | `ThreadSafeFory` factory hook. When set, `ThreadSafeFory` creates instances via this callback; otherwise it forwards `**kwargs` to `Fory` construction.                                    |

## Key Methods

```python
# Serialization (serialize/deserialize are identical to dumps/loads)
data: bytes = fory.serialize(obj)
obj = fory.deserialize(data)

# Alternative API (aliases)
data: bytes = fory.dumps(obj)
obj = fory.loads(data)

# Type registration by id (for Python mode)
fory.register(MyClass, type_id=123)
fory.register(MyClass, type_id=123, serializer=custom_serializer)

# Type registration by name (for cross-language mode)
fory.register(MyClass, typename="my.package.MyClass")
fory.register(MyClass, typename="my.package.MyClass", serializer=custom_serializer)
```

## Language Modes Comparison

| Feature               | Python Mode (`xlang=False`)          | Cross-Language Mode (`xlang=True`)    |
| --------------------- | ------------------------------------ | ------------------------------------- |
| **Use Case**          | Pure Python applications             | Multi-language systems                |
| **Compatibility**     | Python only                          | Java, Go, Rust, C++, JavaScript, etc. |
| **Supported Types**   | All Python types                     | Cross-language compatible types only  |
| **Functions/Lambdas** | ✓ Supported                          | ✗ Not allowed                         |
| **Local Classes**     | ✓ Supported                          | ✗ Not allowed                         |
| **Dynamic Classes**   | ✓ Supported                          | ✗ Not allowed                         |
| **Schema Evolution**  | ✓ Supported (with `compatible=True`) | ✓ Supported (with `compatible=True`)  |
| **Performance**       | Extremely fast                       | Very fast                             |
| **Data Size**         | Compact                              | Compact with type metadata            |

## Python Mode (`xlang=False`)

Python mode supports all Python types including functions, classes, and closures:

```python
import pyfory

# Full Python compatibility mode
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# Supports ALL Python objects:
data = fory.dumps({
    'function': lambda x: x * 2,        # Functions and lambdas
    'class': type('Dynamic', (), {}),    # Dynamic classes
    'method': str.upper,                # Methods
    'nested': {'circular_ref': None}    # Circular references (when ref=True)
})

# Drop-in replacement for pickle/cloudpickle
import pickle
obj = [1, 2, {"nested": [3, 4]}]
assert fory.loads(fory.dumps(obj)) == pickle.loads(pickle.dumps(obj))
```

## Cross-Language Mode (`xlang=True`)

Cross-language mode restricts types to those compatible across all Fory implementations:

```python
import pyfory

# Cross-language compatibility mode
f = pyfory.Fory(xlang=True, ref=True)

# Only supports cross-language compatible types
f.register(MyDataClass, typename="com.example.MyDataClass")

# Data can be read by Java, Go, Rust, etc.
data = f.serialize(MyDataClass(field1="value", field2=42))
```

## Example Configurations

### Production Configuration

```python
import pyfory

# Recommended settings for production
fory = pyfory.Fory(
    xlang=False,        # Use True if you need cross-language support
    ref=False,          # Enable if you have shared/circular references
    strict=True,        # CRITICAL: Always True in production
    compatible=False,   # Enable only if you need schema evolution
    max_depth=20        # Adjust based on your data structure depth
)

# Register all types upfront
fory.register(UserModel, type_id=100)
fory.register(OrderModel, type_id=101)
fory.register(ProductModel, type_id=102)
```

### Development Configuration

```python
import pyfory

# Development settings (more permissive)
fory = pyfory.Fory(
    xlang=False,
    ref=True,
    strict=False,    # Allow any type for development
    max_depth=1000   # Higher limit for development
)
```

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Type Registration](type-registration.md) - Registration patterns
- [Security](security.md) - Security best practices
