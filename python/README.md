# Apache Fory™ Python

[![Build Status](https://img.shields.io/github/actions/workflow/status/apache/fory/ci.yml?branch=main&style=for-the-badge&label=GITHUB%20ACTIONS&logo=github)](https://github.com/apache/fory/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/pyfory.svg?logo=PyPI&style=for-the-badge)](https://pypi.org/project/pyfory/)
[![Python Versions](https://img.shields.io/pypi/pyversions/pyfory.svg?logo=python&style=for-the-badge)](https://pypi.org/project/pyfory/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=for-the-badge)](https://opensource.org/licenses/Apache-2.0)
[![Slack Channel](https://img.shields.io/badge/slack-join-3f0e40?logo=slack&style=for-the-badge)](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)
[![X](https://img.shields.io/badge/@ApacheFory-follow-blue?logo=x&style=for-the-badge)](https://x.com/ApacheFory)

**Apache Fory™** is a blazing fast multi-language serialization framework powered by **JIT compilation** and **zero-copy** techniques, providing up to **ultra-fast performance** while maintaining ease of use and safety.

`pyfory` provides the Python implementation of Apache Fory™, offering both high-performance object serialization and advanced row-format capabilities for data processing tasks.

## 🚀 Key Features

### 🔧 **Flexible Serialization Modes**

- **Python native Mode**: Full Python compatibility, drop-in replacement for pickle/cloudpickle
- **Cross-Language Mode**: Optimized for multi-language data exchange
- **Row Format**: Zero-copy row format for analytics workloads

### 🎯 Versatile Serialization Features

- **Shared/circular reference support** for complex object graphs in both Python-native and cross-language modes
- **Polymorphism support** for customized types with automatic type dispatching
- **Schema evolution** support for backward/forward compatibility when using dataclasses in cross-language mode
- **Out-of-band buffer support** for zero-copy serialization of large data structures like NumPy arrays and Pandas DataFrames, compatible with pickle protocol 5

### ⚡ **Blazing Fast Performance**

- **Extremely fast performance** compared to other serialization frameworks
- **Runtime code generation** and **Cython-accelerated** core implementation for optimal performance

### 📦 Compact Data Size

- **Compact object graph protocol** with minimal space overhead—up to 3× size reduction compared to pickle/cloudpickle
- **Meta packing and sharing** to minimize type forward/backward compatibility space overhead

### 🛡️ **Security & Safety**

- **Strict mode** prevents deserialization of untrusted types by type registration and checks.
- **Reference tracking** for handling circular references safely

## 📦 Installation

### Basic Installation

Install pyfory using pip:

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

## 🐍 Python Native Serialization

`pyfory` provides a Python-native serialization mode that offers the same functionality as pickle/cloudpickle, but with **significantly better performance, smaller data size, and enhanced security features**.

The binary protocol and API are similar to Fory's xlang mode, but Python-native mode can serialize any Python object—including global functions, local functions, lambdas, local classes and types with customized serialization using `__getstate__/__reduce__/__reduce_ex__`, which are not allowed in xlang mode.

To use Python-native mode, create `Fory` with `xlang=False`. This mode is optimized for pure Python applications:

```python
import pyfory
fory = pyfory.Fory(xlang=False, ref=False, strict=True)
```

### Basic Object Serialization

Serialize and deserialize Python objects with a simple API. This example shows serializing a dictionary with mixed types:

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

### Custom Class Serialization

Fory automatically handles dataclasses and custom types. Register your class once, then serialize instances seamlessly:

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

### Drop-in Replacement for Pickle/Cloudpickle

`pyfory` can serialize any Python object with the following configuration:

- **For circular references**: Set `ref=True` to enable reference tracking
- **For functions/classes**: Set `strict=False` to allow deserialization of dynamic types

**⚠️ Security Warning**: When `strict=False`, Fory will deserialize arbitrary types, which can pose security risks if data comes from untrusted sources. Only use `strict=False` in controlled environments where you trust the data source completely. If you do need to use `strict=False`, please configure a `DeserializationPolicy` when creating fory using `policy=your_policy` to controlling deserialization behavior.

#### Common Usage

Serialize common Python objects including dicts, lists, and custom classes without any registration:

```python
import pyfory

# Create Fory instance
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# serialize common Python objects
data = fory.dumps({"name": "Alice", "age": 30, "scores": [95, 87, 92]})
print(fory.loads(data))

# serialize custom objects
from dataclasses import dataclass

@dataclass
class Person:
    name: str
    age: int

person = Person("Bob", 25)
data = fory.dumps(person)
print(fory.loads(data))  # Person(name='Bob', age=25)
```

#### Serialize Global Functions

Capture and get functions defined at module level. Fory deserialize and return same function object:

```python
import pyfory

# Create Fory instance
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# serialize global functions
def my_global_function(x):
    return 10 * x

data = fory.dumps(my_global_function)
print(fory.loads(data)(10))  # 100
```

#### Serialize Local Functions/Lambdas

Serialize functions with closures and lambda expressions. Fory captures the closure variables automatically:

```python
import pyfory

# Create Fory instance
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# serialize local functions with closures
def my_function():
    local_var = 10
    def local_func(x):
        return x * local_var
    return local_func

data = fory.dumps(my_function())
print(fory.loads(data)(10))  # 100

# serialize lambdas
data = fory.dumps(lambda x: 10 * x)
print(fory.loads(data)(10))  # 100
```

#### Serialize Global Classes/Methods

Serialize class objects, instance methods, class methods, and static methods. All method types are supported:

```python
from dataclasses import dataclass
import pyfory
fory = pyfory.Fory(xlang=False, ref=True, strict=False)

# serialize global class
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

print(fory.loads(fory.dumps(Person))("Bob", 25))  # Person(name='Bob', age=25)
# serialize global class instance method
print(fory.loads(fory.dumps(Person("Bob", 20).f))(10))  # 200
# serialize global class class method
print(fory.loads(fory.dumps(Person.g))(10))  # 100
# serialize global class static method
print(fory.loads(fory.dumps(Person.h))(10))  # 100
```

#### Serialize Local Classes/Methods

Serialize classes defined inside functions along with their methods. Useful for dynamic class creation:

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

# serialize local class
data = fory.dumps(create_local_class())
print(fory.loads(data)().f(10))  # 100

# serialize local class instance method
data = fory.dumps(create_local_class()().f)
print(fory.loads(data)(10))  # 100

# serialize local class method
data = fory.dumps(create_local_class().g)
print(fory.loads(data)(10))  # 100

# serialize local class static method
data = fory.dumps(create_local_class().h)
print(fory.loads(data)(10))  # 100
```

### Out-of-Band Buffer Serialization

Fory supports pickle5-compatible out-of-band buffer serialization for efficient zero-copy handling of large data structures. This is particularly useful for NumPy arrays, Pandas DataFrames, and other objects with large memory footprints.

Out-of-band serialization separates metadata from the actual data buffers, allowing for:

- **Zero-copy transfers** when sending data over networks or IPC using `memoryview`
- **Improved performance** for large datasets
- **Pickle5 compatibility** using `pickle.PickleBuffer`
- **Flexible stream support** - write to any writable object (files, BytesIO, sockets, etc.)

#### Basic Out-of-Band Serialization

```python
import pyfory
import numpy as np

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

# Large numpy array
array = np.arange(10000, dtype=np.float64)

# Serialize with out-of-band buffers
buffer_objects = []
serialized_data = fory.serialize(array, buffer_callback=buffer_objects.append)

# Convert buffer objects to memoryview for zero-copy transmission
# For contiguous buffers (bytes, numpy arrays), this is zero-copy
# For non-contiguous data, a copy may be created to ensure contiguity
buffers = [obj.getbuffer() for obj in buffer_objects]

# Deserialize with out-of-band buffers (accepts memoryview, bytes, or Buffer)
deserialized_array = fory.deserialize(serialized_data, buffers=buffers)

assert np.array_equal(array, deserialized_array)
```

#### Out-of-Band with Pandas DataFrames

```python
import pyfory
import pandas as pd
import numpy as np

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

# Create a DataFrame with numeric columns
df = pd.DataFrame({
    'a': np.arange(1000, dtype=np.float64),
    'b': np.arange(1000, dtype=np.int64),
    'c': ['text'] * 1000
})

# Serialize with out-of-band buffers
buffer_objects = []
serialized_data = fory.serialize(df, buffer_callback=buffer_objects.append)
buffers = [obj.getbuffer() for obj in buffer_objects]

# Deserialize
deserialized_df = fory.deserialize(serialized_data, buffers=buffers)

assert df.equals(deserialized_df)
```

#### Selective Out-of-Band Serialization

You can control which buffers go out-of-band by providing a callback that returns `True` to keep data in-band or `False` (and appending to a list) to send it out-of-band:

```python
import pyfory
import numpy as np

fory = pyfory.Fory(xlang=False, ref=True, strict=False)

arr1 = np.arange(1000, dtype=np.float64)
arr2 = np.arange(2000, dtype=np.float64)
data = [arr1, arr2]

buffer_objects = []
counter = 0

def selective_callback(buffer_object):
    global counter
    counter += 1
    # Only send even-numbered buffers out-of-band
    if counter % 2 == 0:
        buffer_objects.append(buffer_object)
        return False  # Out-of-band
    return True  # In-band

serialized = fory.serialize(data, buffer_callback=selective_callback)
buffers = [obj.getbuffer() for obj in buffer_objects]
deserialized = fory.deserialize(serialized, buffers=buffers)
```

#### Pickle5 Compatibility

Fory's out-of-band serialization is fully compatible with pickle protocol 5. When objects implement `__reduce_ex__(protocol)`, Fory automatically uses protocol 5 to enable `pickle.PickleBuffer` support:

```python
import pyfory
import pickle

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

# PickleBuffer objects are automatically supported
data = b"Large binary data"
pickle_buffer = pickle.PickleBuffer(data)

# Serialize with buffer callback for out-of-band handling
buffer_objects = []
serialized = fory.serialize(pickle_buffer, buffer_callback=buffer_objects.append)
buffers = [obj.getbuffer() for obj in buffer_objects]

# Deserialize with buffers
deserialized = fory.deserialize(serialized, buffers=buffers)
assert bytes(deserialized.raw()) == data
```

#### Writing Buffers to Different Streams

The `BufferObject.write_to()` method accepts any writable stream object, making it flexible for various use cases:

```python
import pyfory
import numpy as np
import io

fory = pyfory.Fory(xlang=False, ref=False, strict=False)

array = np.arange(1000, dtype=np.float64)

# Collect out-of-band buffers
buffer_objects = []
serialized = fory.serialize(array, buffer_callback=buffer_objects.append)

# Write to different stream types
for buffer_obj in buffer_objects:
    # Write to BytesIO (in-memory stream)
    bytes_stream = io.BytesIO()
    buffer_obj.write_to(bytes_stream)

    # Write to file
    with open('/tmp/buffer_data.bin', 'wb') as f:
        buffer_obj.write_to(f)

    # Get zero-copy memoryview (for contiguous buffers)
    mv = buffer_obj.getbuffer()
    assert isinstance(mv, memoryview)
```

**Note**: For contiguous memory buffers (like bytes, numpy arrays), `getbuffer()` returns a zero-copy `memoryview`. For non-contiguous data, a copy may be created to ensure contiguity.

## 🏃‍♂️ Cross-Language Object Graph Serialization

`pyfory` supports cross-language object graph serialization, allowing you to serialize data in Python and deserialize it in Java, Go, Rust, or other supported languages.

The binary protocol and API are similar to `pyfory`'s python-native mode, but Python-native mode can serialize any Python object—including global functions, local functions, lambdas, local classes, and types with customized serialization using `__getstate__/__reduce__/__reduce_ex__`, which are not allowed in xlang mode.

To use xlang mode, create `Fory` with `xlang=True`. This mode is for xlang serialization applications:

```python
import pyfory
fory = pyfory.Fory(xlang=True, ref=False, strict=True)
```

### Cross-Language Sserialization

Serialize data in Python and deserialize it in Java, Go, Rust, or other supported languages. Both sides must register the same type with matching names:

**Python (Serializer)**

```python
import pyfory

# Cross-language mode for interoperability
f = pyfory.Fory(xlang=True, ref=True)

# Register type for cross-language compatibility
@dataclass
class Person:
    name: str
    age: pyfory.int32

f.register(Person, typename="example.Person")

person = Person("Charlie", 35)
binary_data = f.serialize(person)
# binary_data can now be sent to Java, Go, etc.
```

**Java (Deserializer)**

```java
import org.apache.fory.*;

public class Person {
    public String name;
    public int age;
}

Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)
    .build();

fory.register(Person.class, "example.Person");
Person person = (Person) fory.deserialize(binaryData);
```

### BFloat16 Support

`pyfory` supports `bfloat16` scalar values and `bfloat16` arrays in xlang mode:

- Scalar type: `pyfory.BFloat16` (type id `18`)
- Array type: `pyfory.BFloat16Array` (type id `54`)

```python
import pyfory
from pyfory import BFloat16, BFloat16Array

fory = pyfory.Fory(xlang=True, ref=False, strict=True)

# Scalar bfloat16
v = BFloat16(3.1415926)
data = fory.serialize(v)
out = fory.deserialize(data)
print(float(out))

# bfloat16 array
arr = BFloat16Array([1.0, 2.5, -3.25])
data = fory.serialize(arr)
out = fory.deserialize(data)
print(out)
```

`BFloat16Array` stores values in a packed `array('H')` representation and writes bytes in little-endian order for cross-language compatibility.

## 📊 Row Format - Zero-Copy Processing

Apache Fury™ provides a random-access row format that enables reading nested fields from binary data without full deserialization. This drastically reduces overhead when working with large objects where only partial data access is needed. The format also supports memory-mapped files for ultra-low memory footprint.

### Basic Row Format Usage

Encode objects to row format for random access without full deserialization. Ideal for large datasets:

**Python**

```python
import pyfory
import pyarrow as pa
from dataclasses import dataclass
from typing import List, Dict

@dataclass
class Bar:
    f1: str
    f2: List[pa.int64]

@dataclass
class Foo:
    f1: pa.int32
    f2: List[pa.int32]
    f3: Dict[str, pa.int32]
    f4: List[Bar]

# Create encoder for row format
encoder = pyfory.encoder(Foo)

# Create large dataset
foo = Foo(
    f1=10,
    f2=list(range(1_000_000)),
    f3={f"k{i}": i for i in range(1_000_000)},
    f4=[Bar(f1=f"s{i}", f2=list(range(10))) for i in range(1_000_000)]
)

# Encode to row format
binary: bytes = encoder.to_row(foo).to_bytes()

# Zero-copy access - no full deserialization needed!
foo_row = pyfory.RowData(encoder.schema, binary)
print(foo_row.f2[100000])              # Access 100,000th element directly
print(foo_row.f4[100000].f1)           # Access nested field directly
print(foo_row.f4[200000].f2[5])        # Access deeply nested field directly
```

### Cross-Language Compatibility

Row format works across languages. Here's the same data structure accessed in Java:

**Java**

```java
public class Bar {
  String f1;
  List<Long> f2;
}

public class Foo {
  int f1;
  List<Integer> f2;
  Map<String, Integer> f3;
  List<Bar> f4;
}

RowEncoder<Foo> encoder = Encoders.bean(Foo.class);

// Create large dataset
Foo foo = new Foo();
foo.f1 = 10;
foo.f2 = IntStream.range(0, 1_000_000).boxed().collect(Collectors.toList());
foo.f3 = IntStream.range(0, 1_000_000).boxed().collect(Collectors.toMap(i -> "k" + i, i -> i));
List<Bar> bars = new ArrayList<>(1_000_000);
for (int i = 0; i < 1_000_000; i++) {
  Bar bar = new Bar();
  bar.f1 = "s" + i;
  bar.f2 = LongStream.range(0, 10).boxed().collect(Collectors.toList());
  bars.add(bar);
}
foo.f4 = bars;

// Encode to row format (cross-language compatible with Python)
BinaryRow binaryRow = encoder.toRow(foo);

// Zero-copy random access without full deserialization
BinaryArray f2Array = binaryRow.getArray(1);              // Access f2 list
BinaryArray f4Array = binaryRow.getArray(3);              // Access f4 list
BinaryRow bar10 = f4Array.getStruct(10);                  // Access 11th Bar
long value = bar10.getArray(1).getInt64(5);               // Access 6th element of bar.f2

// Partial deserialization - only deserialize what you need
RowEncoder<Bar> barEncoder = Encoders.bean(Bar.class);
Bar bar1 = barEncoder.fromRow(f4Array.getStruct(10));     // Deserialize 11th Bar only
Bar bar2 = barEncoder.fromRow(f4Array.getStruct(20));     // Deserialize 21st Bar only

// Full deserialization when needed
Foo newFoo = encoder.fromRow(binaryRow);
```

**C++**

And in C++ with compile-time type information:

```cpp
#include "fory/encoder/row_encoder.h"
#include "fory/row/writer.h"

struct Bar {
  std::string f1;
  std::vector<int64_t> f2;
  FORY_STRUCT(Bar, f1, f2);
};

struct Foo {
  int32_t f1;
  std::vector<int32_t> f2;
  std::map<std::string, int32_t> f3;
  std::vector<Bar> f4;
  FORY_STRUCT(Foo, f1, f2, f3, f4);
};

// Create large dataset
Foo foo;
foo.f1 = 10;
for (int i = 0; i < 1000000; i++) {
  foo.f2.push_back(i);
  foo.f3["k" + std::to_string(i)] = i;
}
for (int i = 0; i < 1000000; i++) {
  Bar bar;
  bar.f1 = "s" + std::to_string(i);
  for (int j = 0; j < 10; j++) {
    bar.f2.push_back(j);
  }
  foo.f4.push_back(bar);
}

// Encode to row format (cross-language compatible with Python/Java)
fory::encoder::RowEncoder<Foo> encoder;
encoder.Encode(foo);
auto row = encoder.GetWriter().ToRow();

// Zero-copy random access without full deserialization
auto f2_array = row->GetArray(1);                    // Access f2 list
auto f4_array = row->GetArray(3);                    // Access f4 list
auto bar10 = f4_array->GetStruct(10);                // Access 11th Bar
int64_t value = bar10->GetArray(1)->GetInt64(5);    // Access 6th element of bar.f2
std::string str = bar10->GetString(0);               // Access bar.f1
```

### Key Benefits

- **Zero-Copy Access**: Read nested fields without deserializing the entire object
- **Memory Efficiency**: Memory-map large datasets directly from disk
- **Cross-Language**: Binary format is compatible between Python, Java, and other Fury implementations
- **Partial Deserialization**: Deserialize only the specific elements you need
- **High Performance**: Skip unnecessary data parsing for analytics and big data workloads

## 🏗️ Core API Reference

### Fory Class

The main serialization interface:

```python
class Fory:
    def __init__(
        self,
        xlang: bool = False,
        ref: bool = False,
        strict: bool = True,
        compatible: bool = False,
        max_depth: int = 50
    )
```

### ThreadSafeFory Class

Thread-safe serialization interface using thread-local storage:

```python
class ThreadSafeFory:
    def __init__(
        self,
        xlang: bool = False,
        ref: bool = False,
        strict: bool = True,
        compatible: bool = False,
        max_depth: int = 50
    )
```

`ThreadSafeFory` provides thread-safe serialization by maintaining a pool of `Fory` instances protected by a lock. When a thread needs to serialize/deserialize, it gets an instance from the pool, uses it, and returns it. All type registrations must be done before any serialization to ensure consistency across all instances.

**Thread Safety Example:**

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

**Parameters:**

- **`xlang`** (`bool`, default=`False`): Enable cross-language serialization. When `False`, enables Python-native mode supporting all Python objects. When `True`, enables cross-language mode compatible with Java, Go, Rust, etc.
- **`ref`** (`bool`, default=`False`): Enable reference tracking for shared/circular references. Disable for better performance if your data has no shared references.
- **`strict`** (`bool`, default=`True`): Require type registration for security. **Highly recommended** for production. Only disable in trusted environments.
- **`compatible`** (`bool`, default=`False`): Enable schema evolution in cross-language mode, allowing fields to be added/removed while maintaining compatibility.
- **`max_depth`** (`int`, default=`50`): Maximum deserialization depth for security, preventing stack overflow attacks.

**Key Methods:**

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

### Language Modes Comparison

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

#### Python Mode (`xlang=False`)

Python mode supports all Python types including functions, classes, and closures. Perfect for pure Python applications:

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

# Significantly faster and more compact than pickle
import timeit
obj = {f"key{i}": f"value{i}" for i in range(10000)}
print(f"Fory: {timeit.timeit(lambda: fory.dumps(obj), number=1000):.3f}s")
print(f"Pickle: {timeit.timeit(lambda: pickle.dumps(obj), number=1000):.3f}s")
```

#### Cross-Language Mode (`xlang=True`)

Cross-language mode restricts types to those compatible across all Fory implementations. Use for multi-language systems:

```python
import pyfory

# Cross-language compatibility mode
f = pyfory.Fory(xlang=True, ref=True)

# Only supports cross-language compatible types
f.register(MyDataClass, typename="com.example.MyDataClass")

# Data can be read by Java, Go, Rust, etc.
data = f.serialize(MyDataClass(field1="value", field2=42))
```

## 🔧 Advanced Features

### Reference Tracking & Circular References

Handle shared references and circular dependencies safely. Set `ref=True` to deduplicate objects:

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

### Type Registration & Security

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

### Custom Serializers

Implement custom serialization logic for specialized types with a single `write/read` API:

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

### Numpy & Scientific Computing

Fory natively supports numpy arrays with optimized serialization. Large arrays use zero-copy when possible:

```python
import pyfory
import numpy as np

f = pyfory.Fory()

# Numpy arrays are supported natively
arrays = {
    'matrix': np.random.rand(1000, 1000),
    'vector': np.arange(10000),
    'bool_mask': np.random.choice([True, False], size=5000)
}

data = f.serialize(arrays)
result = f.deserialize(data)

# Zero-copy for compatible array types
assert np.array_equal(arrays['matrix'], result['matrix'])
```

## 💡 Best Practices

### Production Configuration

Use these recommended settings to balance security, performance, and functionality in production:

```python
import pyfory

# Recommended settings for production
fory = pyfory.Fory(
    xlang=False,        # Use True if you need cross-language support
    ref=False,           # Enable if you have shared/circular references
    strict=True,        # CRITICAL: Always True in production
    compatible=False,   # Enable only if you need schema evolution
    max_depth=20       # Adjust based on your data structure depth
)

# Register all types upfront
fory.register(UserModel, type_id=100)
fory.register(OrderModel, type_id=101)
fory.register(ProductModel, type_id=102)
```

### Performance Tips

Optimize serialization speed and memory usage with these guidelines:

1. **Disable `ref=True` if not needed**: Reference tracking has overhead
2. **Use type_id instead of typename**: Integer IDs are faster than string names
3. **Reuse Fory instances**: Create once, use many times
4. **Enable Cython**: Make sure `ENABLE_FORY_CYTHON_SERIALIZATION=1`, should be enabled by default
5. **Use row format for large arrays**: Zero-copy access for analytics

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

### Type Registration Patterns

Choose the right registration approach for your use case:

```python
# Pattern 1: Simple registration
fory.register(MyClass, type_id=100)

# Pattern 2: Cross-language with typename
fory.register(MyClass, typename="com.example.MyClass")

# Pattern 3: With custom serializer
fory.register(MyClass, type_id=100, serializer=MySerializer(fory, MyClass))

# Pattern 4: Batch registration
type_id = 100
for model_class in [User, Order, Product, Invoice]:
    fory.register(model_class, type_id=type_id)
    type_id += 1
```

### Error Handling

Handle common serialization errors gracefully. Catch specific exceptions for better error recovery:

```python
import pyfory
from pyfory.error import TypeUnregisteredError, TypeNotCompatibleError

fory = pyfory.Fory(strict=True)

try:
    data = fory.dumps(my_object)
except TypeUnregisteredError as e:
    print(f"Type not registered: {e}")
    # Register the type and retry
    fory.register(type(my_object), type_id=100)
    data = fory.dumps(my_object)
except Exception as e:
    print(f"Serialization failed: {e}")

try:
    obj = fory.loads(data)
except TypeNotCompatibleError as e:
    print(f"Schema mismatch: {e}")
    # Handle version mismatch
except Exception as e:
    print(f"Deserialization failed: {e}")
```

## 🛠️ Migration Guide

### From Pickle

Replace pickle with Fory for better performance while keeping the same API:

```python
# Before (pickle)
import pickle
data = pickle.dumps(obj)
result = pickle.loads(data)

# After (Fory - drop-in replacement with better performance)
import pyfory
f = pyfory.Fory(xlang=False, ref=True, strict=False)
data = f.dumps(obj)      # Faster and more compact
result = f.loads(data)   # Faster deserialization

# Benefits:
# - 2-10x faster serialization
# - 2-5x faster deserialization
# - Up to 3x smaller data size
# - Same API, better performance
```

### From JSON

Unlike JSON, Fory supports arbitrary Python types including functions:

```python
# Before (JSON - limited types)
import json
data = json.dumps({"name": "Alice", "age": 30})
result = json.loads(data)

# After (Fory - all Python types)
import pyfory
f = pyfory.Fory()
data = f.dumps({"name": "Alice", "age": 30, "func": lambda x: x})
result = f.loads(data)
```

## 🚨 Security Best Practices

### Production Configuration

Never disable `strict=True` in production unless your environment is completely trusted:

```python
import pyfory

# Recommended production settings
f = pyfory.Fory(
    xlang=False,   # or True for cross-language
    ref=True,      # Handle circular references
    strict=True,   # IMPORTANT: Prevent malicious data
    max_depth=100  # Prevent deep recursion attacks
)

# Explicitly register allowed types
f.register(UserModel, type_id=100)
f.register(OrderModel, type_id=101)
# Never set strict=False in production with untrusted data!
```

### Development vs Production

Use environment variables to switch between development and production configurations:

```python
import pyfory
import os

# Development configuration
if os.getenv('ENV') == 'development':
    fory = pyfory.Fory(
        xlang=False,
        ref=True,
        strict=False,    # Allow any type for development
        max_depth=1000   # Higher limit for development
    )
else:
    # Production configuration (security hardened)
    fory = pyfory.Fory(
        xlang=False,
        ref=True,
        strict=True,     # CRITICAL: Require registration
        max_depth=100    # Reasonable limit
    )
    # Register only known safe types
    for idx, model_class in enumerate([UserModel, ProductModel, OrderModel]):
        fory.register(model_class, type_id=100 + idx)
```

### DeserializationPolicy

When `strict=False` is necessary (e.g., deserializing functions/lambdas), use `DeserializationPolicy` to implement fine-grained security controls during deserialization. This provides protection similar to `pickle.Unpickler.find_class()` but with more comprehensive hooks.

**Why use DeserializationPolicy?**

- Block dangerous classes/modules (e.g., `subprocess.Popen`)
- Intercept and validate `__reduce__` callables before invocation
- Sanitize sensitive data during `__setstate__`
- Replace or reject deserialized objects based on custom rules

**Example: Blocking Dangerous Classes**

```python
import pyfory
from pyfory import DeserializationPolicy

dangerous_modules = {'subprocess', 'os', '__builtin__'}

class SafeDeserializationPolicy(DeserializationPolicy):
    """Block potentially dangerous classes during deserialization."""

    def validate_class(self, cls, is_local, **kwargs):
        # Block dangerous modules
        if cls.__module__ in dangerous_modules:
            raise ValueError(f"Blocked dangerous class: {cls.__module__}.{cls.__name__}")
        return None

    def intercept_reduce_call(self, callable_obj, args, **kwargs):
        # Block specific callable invocations during __reduce__
        if getattr(callable_obj, '__name__', "") == 'Popen':
            raise ValueError("Blocked attempt to invoke subprocess.Popen")
        return None

    def intercept_setstate(self, obj, state, **kwargs):
        # Sanitize sensitive data
        if isinstance(state, dict) and 'password' in state:
            state['password'] = '***REDACTED***'
        return None

# Create Fory with custom security policy
policy = SafeDeserializationPolicy()
fory = pyfory.Fory(xlang=False, ref=True, strict=False, policy=policy)

# Now deserialization is protected by your custom policy
data = fory.serialize(my_object)
result = fory.deserialize(data)  # Policy hooks will be invoked
```

**Available Policy Hooks:**

- `validate_class(cls, is_local)` - Validate/block class types during deserialization
- `validate_module(module, is_local)` - Validate/block module imports
- `validate_function(func, is_local)` - Validate/block function references
- `intercept_reduce_call(callable_obj, args)` - Intercept `__reduce__` invocations
- `inspect_reduced_object(obj)` - Inspect/replace objects created via `__reduce__`
- `intercept_setstate(obj, state)` - Sanitize state before `__setstate__`
- `authorize_instantiation(cls, args, kwargs)` - Control class instantiation

**See also:** `pyfory/policy.py` contains detailed documentation and examples for each hook.

## 🐛 Troubleshooting

### Common Issues

**Q: ImportError with format features**

```python
# A: Install Row format support
pip install pyfory[format]

# Or install from source with format support
pip install -e ".[format]"
```

**Q: Slow serialization performance**

```python
# A: Check if Cython acceleration is enabled
import pyfory
print(pyfory.ENABLE_FORY_CYTHON_SERIALIZATION)  # Should be True

# If False, Cython extension may not be compiled correctly
# Reinstall with: pip install --force-reinstall --no-cache-dir pyfory

# For debugging, you can disable Cython mode before importing
import os
os.environ['ENABLE_FORY_CYTHON_SERIALIZATION'] = '0'
import pyfory  # Now uses pure Python mode
```

**Q: Cross-language compatibility issues**

```python
# A: Use explicit type registration with consistent naming
f = pyfory.Fory(xlang=True)
f.register(MyClass, typename="com.package.MyClass")  # Use same name in all languages
```

**Q: Circular reference errors or duplicate data**

```python
# A: Enable reference tracking
f = pyfory.Fory(ref=True)  # Required for circular references

# Example with circular reference
class Node:
    def __init__(self, value):
        self.value = value
        self.next = None

node1 = Node(1)
node2 = Node(2)
node1.next = node2
node2.next = node1  # Circular reference

data = f.dumps(node1)
result = f.loads(data)
assert result.next.next is result  # Circular reference preserved
```

### Debug Mode

```python
# Set environment variable BEFORE importing pyfory to disable Cython for debugging
import os
os.environ['ENABLE_FORY_CYTHON_SERIALIZATION'] = '0'
import pyfory  # Now uses pure Python implementation

# This is useful for:
# 1. Debugging protocol issues
# 2. Understanding serialization behavior
# 3. Development without recompiling Cython
```

**Q: Schema evolution not working**

```python
# A: Enable compatible mode for schema evolution
f = pyfory.Fory(xlang=True, compatible=True)

# Version 1: Original class
@dataclass
class User:
    name: str
    age: int

f.register(User, typename="User")
data = f.dumps(User("Alice", 30))

# Version 2: Add new field (backward compatible)
@dataclass
class User:
    name: str
    age: int
    email: str = "unknown@example.com"  # New field with default

# Can still deserialize old data
user = f.loads(data)
print(user.email)  # "unknown@example.com"
```

**Q: Type registration errors in strict mode**

```python
# A: Register all custom types before serialization
f = pyfory.Fory(strict=True)

# Must register before use
f.register(MyClass, type_id=100)
f.register(AnotherClass, type_id=101)

# Or disable strict mode (NOT recommended for production)
f = pyfory.Fory(strict=False)  # Use only in trusted environments
```

## 🤝 Contributing

Apache Fory™ is an open-source project under the Apache Software Foundation. We welcome all forms of contributions:

### How to Contribute

1. **Report Issues**: Found a bug? [Open an issue](https://github.com/apache/fory/issues)
2. **Suggest Features**: Have an idea? Start a discussion
3. **Improve Docs**: Documentation improvements are always welcome
4. **Submit Code**: See our [Contributing Guide](https://github.com/apache/fory/blob/main/CONTRIBUTING.md)

> **For Contributors**: See [CONTRIBUTING.md](CONTRIBUTING.md) for comprehensive development setup instructions

## 📄 License

Apache License 2.0. See [LICENSE](https://github.com/apache/fory/blob/main/LICENSE) for details.

---

**Apache Fory™** - Blazing fast, secure, and versatile serialization for modern applications.

## 🔗 Links

- **Documentation**: https://fory.apache.org/docs/latest/python_guide/
- **GitHub**: https://github.com/apache/fory
- **PyPI**: https://pypi.org/project/pyfory/
- **Issues**: https://github.com/apache/fory/issues
- **Slack**: https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw
- **Benchmarks**: https://fory.apache.org/docs/latest/benchmarks/

## 🌟 Community

We welcome contributions! Whether it's bug reports, feature requests, documentation improvements, or code contributions, we appreciate your help.

- Star the project on [GitHub](https://github.com/apache/fory) ⭐
- Join our [Slack community](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw) 💬
- Follow us on [X/Twitter](https://x.com/ApacheFory) 🐦
