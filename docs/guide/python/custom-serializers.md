---
title: Custom Serializers
sidebar_position: 4
id: custom_serializers
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

Implement custom serialization logic for specialized types.

## Implementing Custom Serializers

Implement `write/read` once for both Python and cross-language modes:

```python
import pyfory
from pyfory.serializer import Serializer
from dataclasses import dataclass

@dataclass
class Foo:
    f1: int
    f2: str

class FooSerializer(Serializer):
    def __init__(self, fory, cls):
        super().__init__(fory, cls)

    def write(self, buffer, obj: Foo):
        # Custom serialization logic
        buffer.write_varint32(obj.f1)
        buffer.write_string(obj.f2)

    def read(self, buffer):
        # Custom deserialization logic
        f1 = buffer.read_varint32()
        f2 = buffer.read_string()
        return Foo(f1, f2)

f = pyfory.Fory()
f.register(Foo, type_id=100, serializer=FooSerializer(f, Foo))

# Now Foo uses your custom serializer
data = f.dumps(Foo(42, "hello"))
result = f.loads(data)
print(result)  # Foo(f1=42, f2='hello')
```

## Buffer API

### Write Methods

```python
# Integers
buffer.write_int8(value)
buffer.write_int16(value)
buffer.write_int32(value)
buffer.write_int64(value)

# Variable-length integers
buffer.write_varint32(value)
buffer.write_varint64(value)

# Floating point
buffer.write_float32(value)
buffer.write_float64(value)

# Strings and bytes
buffer.write_string(value)
buffer.write_bytes(value)

# Boolean
buffer.write_bool(value)
```

### Read Methods

```python
# Integers
value = buffer.read_int8()
value = buffer.read_int16()
value = buffer.read_int32()
value = buffer.read_int64()

# Variable-length integers
value = buffer.read_varint32()
value = buffer.read_varint64()

# Floating point
value = buffer.read_float32()
value = buffer.read_float64()

# Strings and bytes
value = buffer.read_string()
value = buffer.read_bytes(length)

# Boolean
value = buffer.read_bool()
```

## When to Use Custom Serializers

- External types from other packages
- Types with special serialization requirements
- Legacy data format compatibility
- Performance-critical custom encoding
- Types that don't work well with automatic serialization

## Registering Custom Serializers

```python
fory = pyfory.Fory()

# Register with type_id
fory.register(MyClass, type_id=100, serializer=MySerializer(fory, MyClass))

# Register with typename (for xlang)
fory.register(MyClass, typename="com.example.MyClass", serializer=MySerializer(fory, MyClass))
```

## Related Topics

- [Type Registration](type-registration.md) - Registration patterns
- [Configuration](configuration.md) - Fory parameters
- [Cross-Language](cross-language.md) - type registration and schema rules for xlang
