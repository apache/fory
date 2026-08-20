---
title: Configuration
sidebar_position: 4
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

This page covers Python Fory instance configuration. `pyfory.Fory()` defaults to xlang mode with
compatible schema evolution. Native mode is selected explicitly with `xlang=False` and also defaults
to compatible schema evolution.

## Fory Class

The main serialization interface:

```python
class Fory:
    def __init__(
        self,
        xlang: bool = True,
        ref: bool = False,
        strict: bool = True,
        compatible: Optional[bool] = None,
        max_depth: int = 50,
        max_type_fields: int = 512,
        max_type_meta_bytes: int = 4096,
        max_schema_versions_per_type: int = 10,
        max_average_schema_versions_per_type: int = 3,
        max_graph_memory_bytes: int = 128 * 1024 * 1024,
        max_unbacked_container_items: int = 8192,
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

| Parameter                              | Type                            | Default     | Description                                                                                                                                              |
| -------------------------------------- | ------------------------------- | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `xlang`                                | `bool`                          | `True`      | Use xlang mode. Set `False` for Python native mode.                                                                                                      |
| `ref`                                  | `bool`                          | `False`     | Enable reference tracking for shared/circular references. Disable for better performance if your data has no shared references.                          |
| `strict`                               | `bool`                          | `True`      | Require registration before loading application classes. Compatible unknown Structs use the data-only `UnknownStruct` carrier.                           |
| `compatible`                           | `bool \| None`                  | `None`      | Schema evolution mode. `None` enables compatible mode in both xlang and native mode. Set `False` only when every reader and writer uses the same schema. |
| `max_depth`                            | `int`                           | `50`        | Maximum deserialization depth for security, preventing stack overflow attacks.                                                                           |
| `max_type_fields`                      | `int`                           | `512`       | Maximum fields accepted in one received remote struct metadata body.                                                                                     |
| `max_type_meta_bytes`                  | `int`                           | `4096`      | Maximum encoded body bytes accepted for one received TypeDef body, excluding the 8-byte header and any extended-size varint.                             |
| `max_schema_versions_per_type`         | `int`                           | `10`        | Maximum accepted remote metadata versions for one logical type.                                                                                          |
| `max_average_schema_versions_per_type` | `int`                           | `3`         | Average accepted remote metadata versions across accepted remote types. The effective global floor is `8192` schemas.                                    |
| `max_graph_memory_bytes`               | `int`                           | `134217728` | Approximate graph-memory gate for one root deserialization. Explicit non-positive values are rejected.                                                   |
| `max_unbacked_container_items`         | `int`                           | `8192`      | Maximum collection elements and map entries whose repeated reads are not backed by input progress. Zero is strict.                                       |
| `policy`                               | `DeserializationPolicy \| None` | `None`      | Deserialization policy used for security checks. Strongly recommended when `strict=False`.                                                               |
| `field_nullable`                       | `bool`                          | `False`     | Treat dataclass fields as nullable by default.                                                                                                           |
| `meta_compressor`                      | `Any`                           | `None`      | Optional metadata compressor used for compatible-mode metadata encoding.                                                                                 |
| `fory_factory`                         | `Callable \| None`              | `None`      | `ThreadSafeFory` factory hook. When set, `ThreadSafeFory` creates instances via this callback; otherwise it forwards `**kwargs` to `Fory` construction.  |

## Key Methods

```python
# Serialization (serialize/deserialize are identical to dumps/loads)
data: bytes = fory.serialize(obj)
obj = fory.deserialize(data)

# Alternative API (aliases)
data: bytes = fory.dumps(obj)
obj = fory.loads(data)

# Type registration by id
fory.register(MyClass, type_id=123)
fory.register(MyClass, type_id=123, serializer=custom_serializer)

# Type registration by name
fory.register(MyClass, name="my.package.MyClass")
fory.register(MyClass, name="my.package.MyClass", serializer=custom_serializer)
```

## Xlang And Native Mode Comparison

| Feature             | Native mode (`xlang=False`)                    | Xlang mode (default)                                                             |
| ------------------- | ---------------------------------------------- | -------------------------------------------------------------------------------- |
| Use case            | Python-only applications                       | Multi-language systems                                                           |
| Compatibility       | Python only                                    | Java, C++, Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin, etc. |
| Supported types     | Python object surface                          | Cross-language compatible types                                                  |
| Functions/lambdas   | Supported with trusted dynamic deserialization | Not allowed                                                                      |
| Local classes       | Supported with trusted dynamic deserialization | Not allowed                                                                      |
| Dynamic classes     | Supported with trusted dynamic deserialization | Not allowed                                                                      |
| Schema mode default | Compatible                                     | Compatible                                                                       |

## Xlang Mode

Xlang mode is the default and restricts payloads to types compatible across Fory implementations:

```python
import pyfory

fory = pyfory.Fory(xlang=True, ref=True)
fory.register(MyDataClass, name="com.example.MyDataClass")
data = fory.serialize(MyDataClass(field1="value", field2=42))
```

Use `compatible=False` for xlang payloads only when every reader and writer always uses the same schema and you want faster serialization and smaller size. Use it only after verifying that every language uses that schema, or when native types are generated from Fory schema IDL.

## Native Mode

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=False)
```

Native mode supports Python-specific object features such as functions, local classes, methods,
`__reduce__`, and `__getstate__`. Compatible mode is still enabled by default. Set
`compatible=False` only when every reader and writer always uses the same Python
class schema and you want faster serialization and smaller size.

## Compatible Mode

Compatible mode is enabled by default for both xlang and native mode. Keep this default when Python
classes may evolve independently, when services deploy separately, or when xlang schemas are written
by hand in different languages.

For xlang payloads, set `compatible=False` only after verifying that every language uses the same schema, or when native types are generated from Fory schema IDL.

## Example Configurations

### Xlang Service

```python
import pyfory

fory = pyfory.Fory(
    xlang=True,
    ref=False,
    strict=True,
    max_depth=20,
)

fory.register(UserModel, name="example.User")
```

### Native Mode With Dynamic Types

```python
import pyfory

fory = pyfory.Fory(
    xlang=False,
    ref=True,
    strict=False,
    max_depth=1000,
)
```

Use `strict=False` only for trusted data, preferably with a `policy=` deserialization policy.

## Security

See [Python Security](security.md) for trust boundaries, safe reader configuration, and verification.

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Type Registration](type-registration.md) - Registration patterns
- [Native Serialization](native.md) - Python-only object serialization
