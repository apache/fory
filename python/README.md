# Apache Fory™ Python

[![Build Status](https://img.shields.io/github/actions/workflow/status/apache/fory/ci.yml?branch=main&style=for-the-badge&label=GITHUB%20ACTIONS&logo=github)](https://github.com/apache/fory/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/pyfory.svg?logo=PyPI)](https://pypi.org/project/pyfory/)
[![Slack Channel](https://img.shields.io/badge/slack-join-3f0e40?logo=slack&style=for-the-badge)](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)
[![X](https://img.shields.io/badge/@ApacheFory-follow-blue?logo=x&style=for-the-badge)](https://x.com/ApacheFory)

**Apache Fory™** (formerly _Fury_) is a blazing fast multi-language serialization framework powered by **JIT** (just-in-time compilation) and **zero-copy**, providing up to 170x performance and ease of use.

This package provides the Python bindings for Apache Fory™.

## Installation

You can install `pyfory` using pip:

```bash
pip install pyfory
```

## Quickstart

Here are a few examples of how to use `pyfory` for serialization.

### Basic Serialization

This example shows how to serialize and deserialize a simple Python object.

```python
from typing import Dict
import pyfory

class SomeClass:
    f1: "SomeClass"
    f2: Dict[str, str]
    f3: Dict[str, str]

fory = pyfory.Fory(ref_tracking=True)
fory.register_type(SomeClass, typename="example.SomeClass")
obj = SomeClass()
obj.f2 = {"k1": "v1", "k2": "v2"}
obj.f1, obj.f3 = obj, obj.f2
data = fory.serialize(obj)
# bytes can be data serialized by other languages.
print(fory.deserialize(data))
```

### Cross-language Serialization

Apache Fory™ excels at cross-language serialization. You can serialize data in Python and deserialize it in another language like Java or Go, and vice-versa.

Here's an example of how to serialize an object in Python and deserialize it in Java:

**Python**

```python
from typing import Dict
import pyfory

class SomeClass:
    f1: "SomeClass"
    f2: Dict[str, str]
    f3: Dict[str, str]

fory = pyfory.Fory(ref_tracking=True)
fory.register_type(SomeClass, typename="example.SomeClass")
obj = SomeClass()
obj.f2 = {"k1": "v1", "k2": "v2"}
obj.f1, obj.f3 = obj, obj.f2
data = fory.serialize(obj)
# `data` can now be sent to a Java application
```

**Java**

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import java.util.*;

public class ReferenceExample {
  public static class SomeClass {
    SomeClass f1;
    Map<String, String> f2;
    Map<String, String> f3;
  }

  public static void main(String[] args) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG)
      .withRefTracking(true).build();
    fory.register(SomeClass.class, "example.SomeClass");
    // `bytes` would be the data received from the Python application
    byte[] bytes = ...
    System.out.println(fory.deserialize(bytes));
  }
}
```

### Row Format Zero-Copy Partial Serialzation

Apache Fory™ provide a random-access row format, which supports map a typed nested struct into a binary and read its nested element without deserializing the whole binary. This can be used to minimize teh deserialization overhead for huge objects in the case where you only needs to access part of the data. You can even encode huge objects into binary and write to file, then mmap that file into memory to reduce memory overhead too.

**Python**

```python
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

encoder = pyfory.encoder(Foo)
foo = Foo(f1=10, f2=list(range(1000_000)),
         f3={f"k{i}": i for i in range(1000_000)},
         f4=[Bar(f1=f"s{i}", f2=list(range(10))) for i in range(1000_000)])
binary: bytes = encoder.to_row(foo).to_bytes()
foo_row = pyfory.RowData(encoder.schema, binary)
print(foo_row.f2[100000], foo_row.f4[100000].f1, foo_row.f4[200000].f2[5])
```

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
Foo foo = new Foo();
foo.f1 = 10;
foo.f2 = IntStream.range(0, 1000000).boxed().collect(Collectors.toList());
foo.f3 = IntStream.range(0, 1000000).boxed().collect(Collectors.toMap(i -> "k"+i, i->i));
List<Bar> bars = new ArrayList<>(1000000);
for (int i = 0; i < 1000000; i++) {
  Bar bar = new Bar();
  bar.f1 = "s"+i;
  bar.f2 = LongStream.range(0, 10).boxed().collect(Collectors.toList());
  bars.add(bar);
}
foo.f4 = bars;
// Can be zero-copy read by python
BinaryRow binaryRow = encoder.toRow(foo);
// can be data from python
Foo newFoo = encoder.fromRow(binaryRow);
// zero-copy read List<Integer> f2
BinaryArray binaryArray2 = binaryRow.getArray(1);
// zero-copy read List<Bar> f4
BinaryArray binaryArray4 = binaryRow.getArray(3);
// zero-copy read 11th element of `readList<Bar> f4`
BinaryRow barStruct = binaryArray4.getStruct(10);

// zero-copy read 6th of f2 of 11th element of `readList<Bar> f4`
barStruct.getArray(1).getInt64(5);
RowEncoder<Bar> barEncoder = Encoders.bean(Bar.class);
// deserialize part of data.
Bar newBar = barEncoder.fromRow(barStruct);
Bar newBar2 = barEncoder.fromRow(binaryArray4.getStruct(20));
```

## Useful Links

- **[Project Website](https://fory.apache.org)**
- **[Documentation](https://fory.apache.org/docs/latest/python_guide/)**
- **[GitHub Repository](https://github.com/apache/fory)**
- **[Issue Tracker](https://github.com/apache/fory/issues)**
- **[Slack Channel](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)**
