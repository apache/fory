---
title: Python Serialization Guide
sidebar_position: 0
id: serialization_index
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

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

`pyfory` provides the Python implementation of Apache Fory™, offering both high-performance object serialization and advanced row-format capabilities for data processing tasks.

## Key Features

### Flexible Serialization Modes

- **Python native Mode**: Full Python compatibility, drop-in replacement for pickle/cloudpickle
- **Cross-Language Mode**: Optimized for multi-language data exchange
- **Row Format**: Zero-copy row format for analytics workloads

### Versatile Serialization Features

- **Shared/circular reference support** for complex object graphs in both Python-native and cross-language modes
- **Polymorphism support** for customized types with automatic type dispatching
- **Schema evolution** support for backward/forward compatibility when using dataclasses in cross-language mode
- **Out-of-band buffer support** for zero-copy serialization of large data structures like NumPy arrays and Pandas DataFrames, compatible with pickle protocol 5

### Blazing Fast Performance

- **Extremely fast performance** compared to other serialization frameworks
- **Runtime code generation** and **Cython-accelerated** core implementation for optimal performance

### Compact Data Size

- **Compact object graph protocol** with minimal space overhead—up to 3× size reduction compared to pickle/cloudpickle
- **Meta packing and sharing** to minimize type forward/backward compatibility space overhead

### Security & Safety

- **Strict mode** prevents deserialization of untrusted types by type registration and checks.
- **Reference tracking** for handling circular references safely

## Installation

### Basic Installation

```bash
pip install pyfory
```

### Optional Dependencies

```bash
# Install with row format support (requires Apache Arrow)
pip install pyfory[format]

# Install from source for development
git clone https://github.com/apache/fory.git
cd fory/python
pip install -e ".[dev,format]"
```

### Requirements

- **Python**: 3.8 or higher
- **OS**: Linux, macOS, Windows

## Thread Safety

`pyfory` provides `ThreadSafeFory` for thread-safe serialization using a pooled wrapper:

```python
import pyfory
import threading
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: int

# Create thread-safe Fory instance
fory = pyfory.ThreadSafeFory(xlang=False, ref=True)
fory.register(Person)

# Use in multiple threads safely
def serialize_in_thread(thread_id):
    person = Person(name=f"User{thread_id}", age=25 + thread_id)
    data = fory.serialize(person)
    result = fory.deserialize(data)
    print(f"Thread {thread_id}: {result}")

threads = [threading.Thread(target=serialize_in_thread, args=(i,)) for i in range(10)]
for t in threads: t.start()
for t in threads: t.join()
```

**Key Features:**

- **Instance Pool**: Maintains a pool of `Fory` instances protected by a lock for thread safety
- **Shared Configuration**: All registrations must be done upfront and are applied to all instances
- **Same API**: Drop-in replacement for `Fory` class with identical methods
- **Registration Safety**: Prevents registration after first use to ensure consistency

**When to Use:**

- **Multi-threaded Applications**: Web servers, concurrent workers, parallel processing
- **Shared Fory Instances**: When multiple threads need to serialize/deserialize data
- **Thread Pools**: Applications using thread pools or concurrent.futures

## Quick Start

```python
import pyfory
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: int

# Create Fory instance
fory = pyfory.Fory(xlang=False, ref=True)
fory.register(Person)

person = Person("Alice", 30)
data = fory.serialize(person)
result = fory.deserialize(data)
print(result)  # Person(name='Alice', age=30)
```

## Next Steps

- [Configuration](configuration.md) - Fory parameters and modes
- [Basic Serialization](basic-serialization.md) - Basic usage patterns
- [Python Native Mode](python-native.md) - Functions, lambdas, classes
- [Cross-Language](cross-language.md) - XLANG mode
- [Row Format](row-format.md) - Zero-copy row format
- [Security](security.md) - Security best practices

## Links

- **Documentation**: https://fory.apache.org/docs/latest/python_guide/
- **GitHub**: https://github.com/apache/fory
- **PyPI**: https://pypi.org/project/pyfory/
- **Issues**: https://github.com/apache/fory/issues
- **Slack**: https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw
