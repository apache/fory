---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This page covers the Python xlang quickstart. `pyfory.Fory()` defaults to xlang mode with
compatible schema evolution; examples set `xlang=True` explicitly so the mode choice is visible.

## Basic Object Serialization

Serialize and deserialize Python objects with a simple API:

```python
import pyfory

fory = pyfory.Fory(xlang=True)

# Serialize xlang-compatible values
data = fory.dumps({"name": "Alice", "age": 30, "scores": [95, 87, 92]})

# Deserialize back to Python object
obj = fory.loads(data)
print(obj)  # {'name': 'Alice', 'age': 30, 'scores': [95, 87, 92]}
```

**Note**: `dumps()`/`loads()` are aliases for `serialize()`/`deserialize()`. Both APIs are identical, use whichever feels more intuitive.

## Custom Class Serialization

Use dataclasses and type annotations for stable xlang payloads:

```python
import pyfory
from dataclasses import dataclass
from typing import List, Dict

@dataclass
class Person:
    name: str
    age: pyfory.Int32
    scores: List[pyfory.Int32]
    metadata: Dict[str, str]

fory = pyfory.Fory(xlang=True, ref=True)
fory.register(Person, name="example.Person")
person = Person("Bob", 25, [88, 92, 85], {"team": "engineering"})
data = fory.serialize(person)
result = fory.deserialize(data)
print(result)  # Person(name='Bob', age=25, ...)
```

## Reference Tracking & Circular References

Handle repeated references safely when the payload uses xlang-compatible types:

```python
import pyfory

f = pyfory.Fory(xlang=True, ref=True)

shared = ["shared"]
value = [shared, shared]

data = f.serialize(value)
result = f.deserialize(data)
assert result[0] is result[1]
```

For arbitrary Python object graphs, local classes, functions, and methods, use
[Native Serialization](native-serialization.md).

## Performance Tips

1. **Disable `ref=True` if not needed**: Reference tracking has overhead
2. **Use type_id instead of name**: Integer IDs are faster than string names
3. **Reuse Fory instances**: Create once, use many times
4. **Enable Cython**: Make sure `ENABLE_FORY_CYTHON_SERIALIZATION=1`

```python
# Good: Reuse instance
fory = pyfory.Fory(xlang=True)
for obj in objects:
    data = fory.dumps(obj)

# Bad: Create new instance each time
for obj in objects:
    fory = pyfory.Fory(xlang=True)  # Wasteful!
    data = fory.dumps(obj)
```

## Related Topics

- [Configuration](configuration.md) - Fory parameters
- [Type Registration](type-registration.md) - Registration patterns
- [Native Serialization](native-serialization.md) - Functions and lambdas
- [Out-of-Band Serialization](out-of-band.md) - Buffer callback APIs
