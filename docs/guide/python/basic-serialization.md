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

This page covers basic serialization patterns in pyfory.

## Basic Object Serialization

Serialize and deserialize Python objects with a simple API:

```python
import pyfory

# Create Fory instance
fory = pyfory.Fory(xlang=True)

# Serialize any Python object
data = fory.dumps({"name": "Alice", "age": 30, "scores": [95, 87, 92]})

# Deserialize back to Python object
obj = fory.loads(data)
print(obj)  # {'name': 'Alice', 'age': 30, 'scores': [95, 87, 92]}
```

**Note**: `dumps()`/`loads()` are aliases for `serialize()`/`deserialize()`. Both APIs are identical, use whichever feels more intuitive.

## Custom Class Serialization

Fory automatically handles dataclasses and custom types:

```python
import pyfory
from dataclasses import dataclass
from typing import List, Dict

@dataclass
class Person:
    name: str
    age: int
    scores: List[int]
    metadata: Dict[str, str]

# Python mode - supports all Python types including dataclasses
fory = pyfory.Fory(xlang=False, ref=True)
fory.register(Person)
person = Person("Bob", 25, [88, 92, 85], {"team": "engineering"})
data = fory.serialize(person)
result = fory.deserialize(data)
print(result)  # Person(name='Bob', age=25, ...)
```

## Reference Tracking & Circular References

Handle shared references and circular dependencies safely:

```python
import pyfory

f = pyfory.Fory(ref=True)  # Enable reference tracking

# Handle circular references safely
class Node:
    def __init__(self, value):
        self.value = value
        self.children = []
        self.parent = None

root = Node("root")
child = Node("child")
child.parent = root  # Circular reference
root.children.append(child)

# Serializes without infinite recursion
data = f.serialize(root)
result = f.deserialize(data)
assert result.children[0].parent is result  # Reference preserved
```

## API Reference

### Serialization Methods

```python
# Serialize to bytes
data: bytes = fory.serialize(obj)
data: bytes = fory.dumps(obj)  # Alias

# Deserialize from bytes
obj = fory.deserialize(data)
obj = fory.loads(data)  # Alias
```

### With Out-of-Band Buffers

```python
# Serialize with buffer callback
buffer_objects = []
data = fory.serialize(obj, buffer_callback=buffer_objects.append)

# Deserialize with buffers
buffers = [obj.getbuffer() for obj in buffer_objects]
obj = fory.deserialize(data, buffers=buffers)
```

## Performance Tips

1. **Disable `ref=True` if not needed**: Reference tracking has overhead
2. **Use type_id instead of typename**: Integer IDs are faster than string names
3. **Reuse Fory instances**: Create once, use many times
4. **Enable Cython**: Make sure `ENABLE_FORY_CYTHON_SERIALIZATION=1`

```python
# Good: Reuse instance
fory = pyfory.Fory()
for obj in objects:
    data = fory.dumps(obj)

# Bad: Create new instance each time
for obj in objects:
    fory = pyfory.Fory()  # Wasteful!
    data = fory.dumps(obj)
```

## Related Topics

- [Configuration](configuration.md) - Fory parameters
- [Type Registration](type-registration.md) - Registration patterns
- [Python Native Mode](python-native.md) - Functions and lambdas
