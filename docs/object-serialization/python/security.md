---
title: Security
sidebar_position: 99
id: security
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

Use this page when a Python reader accepts bytes from outside the application's trust boundary.
Fory reconstructs application values; it does not authenticate the sender, protect transport
integrity, or decide whether a valid value is authorized for a business operation.

## Application boundary

Before deserialization:

- Authenticate the sender and protect message integrity at the transport or storage layer.
- Enforce request or file size, timeout, and concurrency limits outside Fory.
- Register only the application types the endpoint accepts and configure the reader before its
  first root operation.
- Validate the deserialized value against application authorization and domain rules before use.

## Built-in safeguards

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
    max_type_fields=512,
    max_type_meta_bytes=4096,
    max_schema_versions_per_type=10,
    max_average_schema_versions_per_type=3,
    max_graph_memory_bytes=128 * 1024 * 1024,
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

Received remote metadata is also limited:

- `max_type_fields` limits the number of fields accepted in one received struct metadata body.
- `max_type_meta_bytes` limits the encoded body bytes accepted for one received TypeDef body.
- `max_schema_versions_per_type` limits accepted remote metadata versions for one logical type.
- `max_average_schema_versions_per_type` limits the average across accepted remote types.
- `max_graph_memory_bytes` sets an approximate gate for materialized graph memory during one root
  deserialization. The estimate mainly covers lists, tuples, sets, dicts, object arrays, structs,
  and Python objects. It skips leaf values such as strings, binary data, primitive scalars, and
  dense primitive arrays, so actual process memory can be higher than this value. Leaf values remain
  protected by byte-availability checks: if the unread input does not contain enough bytes, Fory
  will not read or create that leaf value. The default is a fixed `128 MiB` for all root input
  forms. Set a positive byte value for trusted payloads that legitimately need a larger or smaller
  gate.
- `max_unbacked_container_items` limits collection elements and map entries whose repeated read
  bodies do not consume proportional input during one root deserialization. The default is `8192`;
  zero is a strict limit.

These limits do not change `strict`, `policy`, dynamic loading, unknown-class handling, or
schema-evolution semantics.

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
- Keep `max_graph_memory_bytes` at the fixed `128 MiB` default for most inputs, or set a positive
  explicit gate for trusted workloads with different legitimate collection/map/struct sizes.
- Do not treat xlang/native mode choice as a security control.

## Verification

Add negative tests for the boundary as well as normal round trips. Verify that the configured reader
rejects unexpected application types, excessive nesting, resource-limit violations, and malformed
input. After a failed read, verify that a valid root can still be read with the same Fory instance.

See [Configuration](configuration.md) for the complete option reference and
[Type Registration](type-registration.md) for the Fory registration API.
