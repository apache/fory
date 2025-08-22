# Apache Fory™ Python

[![Build Status](https://img.shields.io/github/actions/workflow/status/apache/fory/ci.yml?branch=main&style=for-the-badge&label=GITHUB%20ACTIONS&logo=github)](https://github.com/apache/fory/actions/workflows/ci.yml)
[![PyPI](https://img.shields.io/pypi/v/pyfory.svg?logo=PyPI)](https://pypi.org/project/pyfory/)
[![Slack Channel](https://img.shields.io/badge/slack-join-3f0e40?logo=slack&style=for-the-badge)](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)
[![X](https://img.shields.io/badge/@ApacheFory-follow-blue?logo=x&style=for-the-badge)](https://x.com/ApacheFory)

**Apache Fory** (formerly _Fury_) is a blazing fast multi-language serialization framework powered by **JIT** (just-in-time compilation) and **zero-copy**, providing up to 170x performance and ease of use.

This package provides the Python bindings for Apache Fory.

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

Fory excels at cross-language serialization. You can serialize data in Python and deserialize it in another language like Java or Go, and vice-versa.

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

## Useful Links

- **[Project Website](https://fory.apache.org)**
- **[Documentation](https://fory.apache.org/docs/latest/python_guide/)**
- **[GitHub Repository](https://github.com/apache/fory)**
- **[Issue Tracker](https://github.com/apache/fory/issues)**
- **[Slack Channel](https://join.slack.com/t/fory-project/shared_invite/zt-36g0qouzm-kcQSvV_dtfbtBKHRwT5gsw)**
