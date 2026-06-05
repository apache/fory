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

| Parameter         | Type                            | Default | Description                                                                                                                                              |
| ----------------- | ------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `xlang`           | `bool`                          | `True`  | Use xlang mode. Set `False` for Python native mode.                                                                                                      |
| `ref`             | `bool`                          | `False` | Enable reference tracking for shared/circular references. Disable for better performance if your data has no shared references.                          |
| `strict`          | `bool`                          | `True`  | Require type registration for security. Keep this enabled for production unless a policy owns trust decisions.                                           |
| `compatible`      | `bool \| None`                  | `None`  | Schema evolution mode. `None` enables compatible mode in both xlang and native mode. Set `False` only when every reader and writer uses the same schema. |
| `max_depth`       | `int`                           | `50`    | Maximum deserialization depth for security, preventing stack overflow attacks.                                                                           |
| `policy`          | `DeserializationPolicy \| None` | `None`  | Deserialization policy used for security checks. Strongly recommended when `strict=False`.                                                               |
| `field_nullable`  | `bool`                          | `False` | Treat dataclass fields as nullable by default.                                                                                                           |
| `meta_compressor` | `Any`                           | `None`  | Optional metadata compressor used for compatible-mode metadata encoding.                                                                                 |
| `fory_factory`    | `Callable \| None`              | `None`  | `ThreadSafeFory` factory hook. When set, `ThreadSafeFory` creates instances via this callback; otherwise it forwards `**kwargs` to `Fory` construction.  |

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

Treat native-mode bytes from untrusted sources the same way you would treat untrusted pickle bytes.
Native mode can reconstruct Python objects, import modules, invoke reduction hooks, and rebuild
dynamic classes or functions when `strict=False`.

### Production Configuration

Keep `strict=True` for production payloads unless the whole data source is trusted and a
`DeserializationPolicy` owns the remaining trust decisions:

```python
import pyfory

fory = pyfory.Fory(
    xlang=True,
    ref=False,
    strict=True,
    max_depth=50,
)

fory.register(UserModel, name="example.User")
fory.register(OrderModel, name="example.Order")
```

Use dynamic native-mode deserialization (`strict=False`) only for trusted Python-only payloads:

```python
import pyfory

fory = pyfory.Fory(
    xlang=False,
    ref=True,
    strict=False,
    max_depth=100,
)
```

### DeserializationPolicy

When `strict=False` is necessary, use `DeserializationPolicy` to restrict the dynamic types and
hooks accepted during deserialization:

```python
import pyfory
from pyfory import DeserializationPolicy

dangerous_modules = {"subprocess", "os", "__builtin__"}

class SafeDeserializationPolicy(DeserializationPolicy):
    def validate_class(self, cls, is_local, **kwargs):
        if cls.__module__ in dangerous_modules:
            raise ValueError(f"Blocked dangerous class: {cls.__module__}.{cls.__name__}")

    def intercept_reduce_call(self, callable_obj, args, **kwargs):
        if getattr(callable_obj, "__name__", "") == "Popen":
            raise ValueError("Blocked attempt to invoke subprocess.Popen")
        return None

    def intercept_setstate(self, obj, state, **kwargs):
        if isinstance(state, dict) and "password" in state:
            state["password"] = "***REDACTED***"
        return None

policy = SafeDeserializationPolicy()
fory = pyfory.Fory(xlang=False, ref=True, strict=False, policy=policy)
```

Available policy hooks include:

Reference validation hooks reject by raising exceptions and otherwise leave deserialized references
unchanged.

| Hook                                         | Description                                         |
| -------------------------------------------- | --------------------------------------------------- |
| `validate_class(cls, is_local)`              | Validate or block class types                       |
| `validate_module(module_name, is_local)`     | Validate or block module imports                    |
| `validate_function(func, is_local)`          | Validate or block function references               |
| `validate_method(method, is_local)`          | Validate or block method references                 |
| `intercept_reduce_call(callable_obj, args)`  | Intercept `__reduce__` invocations                  |
| `inspect_reduced_object(obj)`                | Inspect or replace objects created via `__reduce__` |
| `intercept_setstate(obj, state)`             | Sanitize state before `__setstate__`                |
| `authorize_instantiation(cls, args, kwargs)` | Control class instantiation                         |

### Security Checklist

- Keep `strict=True` for untrusted data.
- Register all expected application types before deserialization.
- Use `DeserializationPolicy` when `strict=False` is necessary.
- Keep `max_depth` low enough to reject unexpectedly deep payloads.
- Do not treat xlang/native mode choice as a security control.

## Related Topics

- [Basic Serialization](basic-serialization.md) - Using configured Fory
- [Type Registration](type-registration.md) - Registration patterns
- [Native Serialization](native-serialization.md) - Python-only object serialization
