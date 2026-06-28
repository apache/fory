---
title: Native Serialization
sidebar_position: 3
id: native_serialization
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

Python native serialization is the Python-only wire mode selected with `xlang=False`. Use it when
every writer and reader is Python and the payload should follow Python's object model instead of
the portable xlang type system.

Use [Xlang Serialization](xlang-serialization.md), the default Python mode, when bytes must be read
by Java, C++, Go, Rust, JavaScript/TypeScript, C#, Swift, Dart, Scala, Kotlin,
or another non-Python Fory implementation.

## When To Use Native Serialization

Use native serialization when:

- A payload is produced and consumed only by Python applications.
- You are replacing `pickle` or `cloudpickle` for Python-only object graphs.
- The data model includes functions, lambdas, local classes, methods, or Python reduction hooks.
- The graph can contain shared objects or cycles that need Python reference tracking.
- You need pickle protocol 5-style out-of-band buffers for large Python data objects.

Native mode can serialize Python-specific values such as global functions, local functions, lambdas,
local classes, methods, and objects customized with `__getstate__`, `__setstate__`, `__reduce__`,
or `__reduce_ex__`. Those values are not valid xlang payloads.

## Create a Native-Mode Fory Instance

Create `Fory` with `xlang=False`:

```python
import pyfory
fory = pyfory.Fory(xlang=False, ref=False, strict=True)
```

Keep `strict=True` for registered, trusted type surfaces. Use `strict=False` only when native-mode
payloads need dynamic Python types such as functions, local classes, or objects reconstructed by
reduction hooks.

## Common Usage

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

data = fory.dumps({"name": "Alice", "age": 30, "scores": [95, 87, 92]})
print(fory.loads(data))

from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: int

person = Person("Bob", 25)
data = fory.dumps(person)
print(fory.loads(data))  # Person(name='Bob', age=25)
```

Use `dumps`/`loads` for pickle-style APIs, or `serialize`/`deserialize` when matching the xlang
API shape in code that switches modes explicitly.

## Security And Dynamic Types

Native mode can reconstruct Python objects that execute import and construction logic during
deserialization. Treat untrusted native-mode bytes the same way you would treat untrusted pickle
bytes.

- Keep `strict=True` when deserializing data that should contain only registered or built-in types.
- Use `strict=False` only for trusted payloads that require dynamic Python classes or functions.
- Provide a `policy=` deserialization policy when dynamic types are required but the accepted type
  surface should still be restricted.
- Do not use xlang/native mode choice as a security control. Apply strict mode, policies,
  registration, and resource limits based on the payload source.

## References And Cycles

Enable `ref=True` when object identity, shared references, or cycles must round-trip:

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=True)

node = {}
node["self"] = node
data = fory.dumps(node)
decoded = fory.loads(data)
assert decoded["self"] is decoded
```

Disable reference tracking for value-shaped payloads that do not need identity preservation. It
keeps the payload smaller and the hot path simpler.

## Pickle And Cloudpickle Replacement

Native mode is the Python mode to choose when the existing boundary uses `pickle` or
`cloudpickle`. It supports richer Python values than JSON and xlang mode, including Python
functions, local classes, closures, and reduction hooks.

Use xlang mode instead when the payload crosses language boundaries or the data model should be a
portable schema shared with other Fory implementations.

## Serialize Global Functions

Capture and serialize functions defined at module level. Fory deserializes and returns the same
function object:

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

def my_global_function(x):
    return 10 * x

data = fory.dumps(my_global_function)
print(fory.loads(data)(10))  # 100
```

## Serialize Local Functions/Lambdas

Serialize functions with closures and lambda expressions. Fory captures the closure variables
automatically:

```python
import pyfory

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# Local functions with closures
def my_function():
    local_var = 10
    def local_func(x):
        return x * local_var
    return local_func

data = fory.dumps(my_function())
print(fory.loads(data)(10))  # 100

# Lambdas
data = fory.dumps(lambda x: 10 * x)
print(fory.loads(data)(10))  # 100
```

## Serialize Global Classes/Methods

Serialize class objects, instance methods, class methods, and static methods:

```python
from dataclasses import dataclass
import pyfory
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

@dataclass
class Person:
    name: str
    age: int

    def f(self, x):
        return self.age * x

    @classmethod
    def g(cls, x):
        return 10 * x

    @staticmethod
    def h(x):
        return 10 * x

# Serialize global class
print(fory.loads(fory.dumps(Person))("Bob", 25))  # Person(name='Bob', age=25)

# Serialize instance method
print(fory.loads(fory.dumps(Person("Bob", 20).f))(10))  # 200

# Serialize class method
print(fory.loads(fory.dumps(Person.g))(10))  # 100

# Serialize static method
print(fory.loads(fory.dumps(Person.h))(10))  # 100
```

## Serialize Local Classes/Methods

Serialize classes defined inside functions along with their methods:

```python
from dataclasses import dataclass
import pyfory
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

def create_local_class():
    class LocalClass:
        def f(self, x):
            return 10 * x

        @classmethod
        def g(cls, x):
            return 10 * x

        @staticmethod
        def h(x):
            return 10 * x
    return LocalClass

# Serialize local class
data = fory.dumps(create_local_class())
print(fory.loads(data)().f(10))  # 100

# Serialize local class instance method
data = fory.dumps(create_local_class()().f)
print(fory.loads(data)(10))  # 100

# Serialize local class method
data = fory.dumps(create_local_class().g)
print(fory.loads(data)(10))  # 100

# Serialize local class static method
data = fory.dumps(create_local_class().h)
print(fory.loads(data)(10))  # 100
```

## Custom Python Object Hooks

Native mode respects common Python customization hooks:

```python
import pyfory

class SessionToken:
    def __init__(self, value):
        self.value = value

    def __getstate__(self):
        return {"value": self.value}

    def __setstate__(self, state):
        self.value = state["value"]

fory = pyfory.Fory(xlang=False, strict=False)
token = fory.loads(fory.dumps(SessionToken("abc")))
print(token.value)  # abc
```

Use these hooks for Python-only payloads. For xlang payloads, model the data as dataclasses with
portable field annotations instead.

## Out-of-Band Buffers

Python native mode can use pickle protocol 5-style out-of-band buffers for large binary payloads
and data structures backed by external memory:

```python
import pickle
import pyfory

data = b"Large binary data"
pickle_buffer = pickle.PickleBuffer(data)

buffer_objects = []
fory = pyfory.Fory(xlang=False, ref=True, strict=False)
serialized = fory.dumps(pickle_buffer, buffer_callback=buffer_objects.append)
buffers = [obj.getbuffer() for obj in buffer_objects]
decoded = fory.loads(serialized, buffers=buffers)
assert bytes(decoded.raw()) == data
```

Use this when the payload stays in Python and large buffers should avoid extra copies. See
[Out-of-Band Serialization](out-of-band.md).

## Native And Xlang Comparison

| Requirement                                | Use native serialization | Use xlang serialization |
| ------------------------------------------ | ------------------------ | ----------------------- |
| Python-only payloads                       | Yes                      | Optional                |
| Non-Python readers or writers              | No                       | Yes                     |
| Functions, lambdas, local classes          | Yes                      | No                      |
| `__reduce__` / `__getstate__` object hooks | Yes                      | No                      |
| Pickle/cloudpickle replacement             | Yes                      | No                      |
| Portable type mapping across languages     | No                       | Yes                     |

## Performance Comparison

```python
import pyfory
import pickle
import timeit

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

obj = {f"key{i}": f"value{i}" for i in range(10000)}
print(f"Fory: {timeit.timeit(lambda: fory.dumps(obj), number=1000):.3f}s")
print(f"Pickle: {timeit.timeit(lambda: pickle.dumps(obj), number=1000):.3f}s")
```

## Troubleshooting

### Another language cannot read the payload

The writer is using native serialization. Rebuild it with `xlang=True`, register portable schemas
on every peer, and avoid Python-only values such as lambdas or local classes.

### A dynamic class or function fails to deserialize

Use `strict=False` for trusted payloads and provide a deserialization `policy=` when only selected
dynamic types should be accepted.

### A cycle does not round-trip

Create the `Fory` instance with `ref=True`.

### A value depends on pickle hooks

Keep the payload in native mode. Xlang mode does not execute Python `__reduce__`,
`__reduce_ex__`, `__getstate__`, or `__setstate__` object reconstruction hooks.

## Related Topics

- [Xlang Serialization](xlang-serialization.md) - Cross-language Python payloads
- [Configuration](configuration.md) - Python `Fory` options
- [Out-of-Band Serialization](out-of-band.md) - Zero-copy buffer support
- [Configuration](configuration.md#security) - Deserialization policies
