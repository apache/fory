---
title: Type Registration & Security
sidebar_position: 3
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

This page covers type registration mechanisms and security configurations.

## Type Registration

In strict mode, only registered types can be deserialized. This prevents arbitrary code execution:

```python
import pyfory

# Strict mode (recommended for production)
f = pyfory.Fory(strict=True)

class SafeClass:
    def __init__(self, data):
        self.data = data

# Must register types in strict mode
f.register(SafeClass, typename="com.example.SafeClass")

# Now serialization works
obj = SafeClass("safe data")
data = f.serialize(obj)
result = f.deserialize(data)

# Unregistered types will raise an exception
class UnsafeClass:
    pass

# This will fail in strict mode
try:
    f.serialize(UnsafeClass())
except Exception as e:
    print("Security protection activated!")
```

## Registration Patterns

### Pattern 1: Simple Registration

```python
fory.register(MyClass, type_id=100)
```

### Pattern 2: Cross-Language with Typename

```python
fory.register(MyClass, typename="com.example.MyClass")
```

### Pattern 3: With Custom Serializer

```python
fory.register(MyClass, type_id=100, serializer=MySerializer(fory.type_resolver, MyClass))
```

### Pattern 4: Batch Registration

```python
type_id = 100
for model_class in [User, Order, Product, Invoice]:
    fory.register(model_class, type_id=type_id)
    type_id += 1
```

## Security Modes

### Strict Mode (Recommended)

```python
# Always use strict=True in production
fory = pyfory.Fory(strict=True)

# Explicitly register allowed types
fory.register(UserModel, type_id=100)
fory.register(OrderModel, type_id=101)
```

### Non-Strict Mode

**⚠️ Security Warning**: When `strict=False`, Fory will deserialize arbitrary types, which can pose security risks if data comes from untrusted sources. Only use `strict=False` in controlled environments where you trust the data source completely.

```python
# Only in trusted environments
fory = pyfory.Fory(xlang=False, ref=True, strict=False)
```

If you do need to use `strict=False`, configure a `DeserializationPolicy`:

```python
from pyfory import DeserializationPolicy

class SafePolicy(DeserializationPolicy):
    def validate_class(self, cls, is_local, **kwargs):
        # Block dangerous modules
        if cls.__module__ in {'subprocess', 'os', '__builtin__'}:
            raise ValueError(f"Blocked: {cls}")
        return None

fory = pyfory.Fory(xlang=False, strict=False, policy=SafePolicy())
```

## Max Depth Protection

Limit deserialization depth to prevent stack overflow attacks:

```python
fory = pyfory.Fory(
    strict=True,
    max_depth=100  # Adjust based on your data structure depth
)
```

## Best Practices

1. **Always use `strict=True` in production**
2. **Use `type_id` for performance**, `typename` for flexibility
3. **Register all types upfront** before any serialization
4. **Set appropriate `max_depth`** based on your data structures
5. **Use `DeserializationPolicy`** when `strict=False` is necessary

## Related Topics

- [Configuration](configuration.md) - Fory parameters
- [Security](security.md) - DeserializationPolicy details
- [Custom Serializers](custom-serializers.md) - Custom serialization
