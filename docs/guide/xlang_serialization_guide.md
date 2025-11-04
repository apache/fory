---
title: Xlang Serialization Guide
sidebar_position: 4
id: xlang_serialization
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

## Serialize built-in types

Common types can be serialized automatically: primitive numeric types, string, binary, array, list, map and so on.

**Java**

```java
import org.apache.fory.*;
import org.apache.fory.config.*;

import java.util.*;

public class Example1 {
  public static void main(String[] args) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    List<Object> list = ofArrayList(true, false, "str", -1.1, 1, new int[100], new double[20]);
    byte[] bytes = fory.serialize(list);
    // bytes can be data serialized by other languages.
    fory.deserialize(bytes);
    Map<Object, Object> map = new HashMap<>();
    map.put("k1", "v1");
    map.put("k2", list);
    map.put("k3", -1);
    bytes = fory.serialize(map);
    // bytes can be data serialized by other languages.
    fory.deserialize(bytes);
  }
}
```

**Python**

```python
import pyfory
import numpy as np

fory = pyfory.Fory()
object_list = [True, False, "str", -1.1, 1,
               np.full(100, 0, dtype=np.int32), np.full(20, 0.0, dtype=np.double)]
data = fory.serialize(object_list)
# bytes can be data serialized by other languages.
new_list = fory.deserialize(data)
object_map = {"k1": "v1", "k2": object_list, "k3": -1}
data = fory.serialize(object_map)
# bytes can be data serialized by other languages.
new_map = fory.deserialize(data)
print(new_map)
```

**Golang**

```go
package main

import forygo "github.com/apache/fory/fory/go/fory"
import "fmt"

func main() {
 list := []interface{}{true, false, "str", -1.1, 1, make([]int32, 10), make([]float64, 20)}
 fory := forygo.NewFory()
 bytes, err := fory.Marshal(list)
 if err != nil {
  panic(err)
 }
 var newValue interface{}
 // bytes can be data serialized by other languages.
 if err := fory.Unmarshal(bytes, &newValue); err != nil {
  panic(err)
 }
 fmt.Println(newValue)
 dict := map[string]interface{}{
  "k1": "v1",
  "k2": list,
  "k3": -1,
 }
 bytes, err = fory.Marshal(dict)
 if err != nil {
  panic(err)
 }
 // bytes can be data serialized by other languages.
 if err := fory.Unmarshal(bytes, &newValue); err != nil {
  panic(err)
 }
 fmt.Println(newValue)
}
```

**JavaScript**

```javascript
import Fory from "@apache-fory/fory";

/**
 * @apache-fory/hps use v8's fast-calls-api that can be called directly by jit, ensure that the version of Node is 20 or above.
 * Experimental feature, installation success cannot be guaranteed at this moment
 * If you are unable to install the module, replace it with `const hps = null;`
 **/
import hps from "@apache-fory/hps";

const fory = new Fory({ hps });
const input = fory.serialize("hello fory");
const result = fory.deserialize(input);
console.log(result);
```

**Rust**

```rust
use chrono::{NaiveDate, NaiveDateTime};
use fory::{from_buffer, to_buffer, Fory};
use std::collections::HashMap;

fn run() {
    let bin: Vec<u8> = to_buffer(&"hello".to_string());
    let obj: String = from_buffer(&bin).expect("should success");
    assert_eq!("hello".to_string(), obj);
}
```

## Serialize custom types

Serializing user-defined types needs registering the custom type using the register API to establish the mapping relationship between the type in different languages.

**Java**

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import java.util.*;

public class Example2 {
  public static class SomeClass1 {
    Object f1;
    Map<Byte, Integer> f2;
  }

  public static class SomeClass2 {
    Object f1;
    String f2;
    List<Object> f3;
    Map<Byte, Integer> f4;
    Byte f5;
    Short f6;
    Integer f7;
    Long f8;
    Float f9;
    Double f10;
    short[] f11;
    List<Short> f12;
  }

  public static Object createObject() {
    SomeClass1 obj1 = new SomeClass1();
    obj1.f1 = true;
    obj1.f2 = ofHashMap((byte) -1, 2);
    SomeClass2 obj = new SomeClass2();
    obj.f1 = obj1;
    obj.f2 = "abc";
    obj.f3 = ofArrayList("abc", "abc");
    obj.f4 = ofHashMap((byte) 1, 2);
    obj.f5 = Byte.MAX_VALUE;
    obj.f6 = Short.MAX_VALUE;
    obj.f7 = Integer.MAX_VALUE;
    obj.f8 = Long.MAX_VALUE;
    obj.f9 = 1.0f / 2;
    obj.f10 = 1 / 3.0;
    obj.f11 = new short[]{(short) 1, (short) 2};
    obj.f12 = ofArrayList((short) -1, (short) 4);
    return obj;
  }

  // mvn exec:java -Dexec.mainClass="org.apache.fory.examples.Example2"
  public static void main(String[] args) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    fory.register(SomeClass1.class, "example.SomeClass1");
    fory.register(SomeClass2.class, "example.SomeClass2");
    byte[] bytes = fory.serialize(createObject());
    // bytes can be data serialized by other languages.
    System.out.println(fory.deserialize(bytes));
  }
}
```

**Python**

```python
from dataclasses import dataclass
from typing import List, Dict, Any
import pyfory, array


@dataclass
class SomeClass1:
    f1: Any
    f2: Dict[pyfory.Int8Type, pyfory.Int32Type]


@dataclass
class SomeClass2:
    f1: Any = None
    f2: str = None
    f3: List[str] = None
    f4: Dict[pyfory.Int8Type, pyfory.Int32Type] = None
    f5: pyfory.Int8Type = None
    f6: pyfory.Int16Type = None
    f7: pyfory.Int32Type = None
    # int type will be taken as `pyfory.Int64Type`.
    # use `pyfory.Int32Type` for type hint if peer
    # are more narrow type.
    f8: int = None
    f9: pyfory.Float32Type = None
    # float type will be taken as `pyfory.Float64Type`
    f10: float = None
    f11: pyfory.Int16ArrayType = None
    f12: List[pyfory.Int16Type] = None


if __name__ == "__main__":
    f = pyfory.Fory()
    f.register_type(SomeClass1, typename="example.SomeClass1")
    f.register_type(SomeClass2, typename="example.SomeClass2")
    obj1 = SomeClass1(f1=True, f2={-1: 2})
    obj = SomeClass2(
        f1=obj1,
        f2="abc",
        f3=["abc", "abc"],
        f4={1: 2},
        f5=2 ** 7 - 1,
        f6=2 ** 15 - 1,
        f7=2 ** 31 - 1,
        f8=2 ** 63 - 1,
        f9=1.0 / 2,
        f10=1 / 3.0,
        f11=array.array("h", [1, 2]),
        f12=[-1, 4],
    )
    data = f.serialize(obj)
    # bytes can be data serialized by other languages.
    print(f.deserialize(data))
```

**Golang**

```go
package main

import forygo "github.com/apache/fory/fory/go/fory"
import "fmt"

func main() {
 type SomeClass1 struct {
  F1  interface{}
  F2  string
  F3  []interface{}
  F4  map[int8]int32
  F5  int8
  F6  int16
  F7  int32
  F8  int64
  F9  float32
  F10 float64
  F11 []int16
  F12 fory.Int16Slice
 }

 type SomeClas2 struct {
  F1 interface{}
  F2 map[int8]int32
 }
 fory := forygo.NewFory()
 if err := fory.RegisterNamedType(SomeClass1{}, "example.SomeClass1"); err != nil {
  panic(err)
 }
 if err := fory.RegisterNamedType(SomeClass2{}, "example.SomeClass2"); err != nil {
  panic(err)
 }
 obj1 := &SomeClass1{}
 obj1.F1 = true
 obj1.F2 = map[int8]int32{-1: 2}
 obj := &SomeClass1{}
 obj.F1 = obj1
 obj.F2 = "abc"
 obj.F3 = []interface{}{"abc", "abc"}
 f4 := map[int8]int32{1: 2}
 obj.F4 = f4
 obj.F5 = fory.MaxInt8
 obj.F6 = fory.MaxInt16
 obj.F7 = fory.MaxInt32
 obj.F8 = fory.MaxInt64
 obj.F9 = 1.0 / 2
 obj.F10 = 1 / 3.0
 obj.F11 = []int16{1, 2}
 obj.F12 = []int16{-1, 4}
 bytes, err := fory.Marshal(obj);
 if err != nil {
  panic(err)
 }
 var newValue interface{}
 // bytes can be data serialized by other languages.
 if err := fory.Unmarshal(bytes, &newValue); err != nil {
  panic(err)
 }
 fmt.Println(newValue)
}
```

**JavaScript**

```javascript
import Fory, { Type, InternalSerializerType } from "@apache-fory/fory";

/**
 * @apache-fory/hps use v8's fast-calls-api that can be called directly by jit, ensure that the version of Node is 20 or above.
 * Experimental feature, installation success cannot be guaranteed at this moment
 * If you are unable to install the module, replace it with `const hps = null;`
 **/
import hps from "@apache-fory/hps";

// Now we describe data structures using JSON, but in the future, we will use more ways.
const description = Type.object("example.foo", {
  foo: Type.string(),
});
const fory = new Fory({ hps });
const { serialize, deserialize } = fory.registerSerializer(description);
const input = serialize({ foo: "hello fory" });
const result = deserialize(input);
console.log(result);
```

**Rust**

```rust
use chrono::{NaiveDate, NaiveDateTime};
use fory::{from_buffer, to_buffer, Fory};
use std::collections::HashMap;

#[test]
fn complex_struct() {
    #[derive(Fory, Debug, PartialEq)]
    #[tag("example.foo2")]
    struct Animal {
        category: String,
    }

    #[derive(Fory, Debug, PartialEq)]
    #[tag("example.foo")]
    struct Person {
        c1: Vec<u8>,  // binary
        c2: Vec<i16>, // primitive array
        animal: Vec<Animal>,
        c3: Vec<Vec<u8>>,
        name: String,
        c4: HashMap<String, String>,
        age: u16,
        op: Option<String>,
        op2: Option<String>,
        date: NaiveDate,
        time: NaiveDateTime,
        c5: f32,
        c6: f64,
    }
    let person: Person = Person {
        c1: vec![1, 2, 3],
        c2: vec![5, 6, 7],
        c3: vec![vec![1, 2], vec![1, 3]],
        animal: vec![Animal {
            category: "Dog".to_string(),
        }],
        c4: HashMap::from([
            ("hello1".to_string(), "hello2".to_string()),
            ("hello2".to_string(), "hello3".to_string()),
        ]),
        age: 12,
        name: "helo".to_string(),
        op: Some("option".to_string()),
        op2: None,
        date: NaiveDate::from_ymd_opt(2025, 12, 12).unwrap(),
        time: NaiveDateTime::from_timestamp_opt(1689912359, 0).unwrap(),
        c5: 2.0,
        c6: 4.0,
    };

    let bin: Vec<u8> = to_buffer(&person);
    let obj: Person = from_buffer(&bin).expect("should success");
    assert_eq!(person, obj);
}
```

## Serialize Shared Reference and Circular Reference

Shared reference and circular reference can be serialized automatically, no duplicate data or recursion error.

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

  public static Object createObject() {
    SomeClass obj = new SomeClass();
    obj.f1 = obj;
    obj.f2 = ofHashMap("k1", "v1", "k2", "v2");
    obj.f3 = obj.f2;
    return obj;
  }

  // mvn exec:java -Dexec.mainClass="org.apache.fory.examples.ReferenceExample"
  public static void main(String[] args) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG)
      .withRefTracking(true).build();
    fory.register(SomeClass.class, "example.SomeClass");
    byte[] bytes = fory.serialize(createObject());
    // bytes can be data serialized by other languages.
    System.out.println(fory.deserialize(bytes));
  }
}
```

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
# bytes can be data serialized by other languages.
print(fory.deserialize(data))
```

**Golang**

```go
package main

import forygo "github.com/apache/fory/fory/go/fory"
import "fmt"

func main() {
 type SomeClass struct {
  F1 *SomeClass
  F2 map[string]string
  F3 map[string]string
 }
 fory := forygo.NewFory(true)
 if err := fory.Register(SomeClass{}, 65); err != nil {
  panic(err)
 }
 value := &SomeClass{F2: map[string]string{"k1": "v1", "k2": "v2"}}
 value.F3 = value.F2
 value.F1 = value
 bytes, err := fory.Marshal(value)
 if err != nil {
 }
 var newValue interface{}
 // bytes can be data serialized by other languages.
 if err := fory.Unmarshal(bytes, &newValue); err != nil {
  panic(err)
 }
 fmt.Println(newValue)
}
```

**JavaScript**

```javascript
import Fory, { Type } from '@apache-fory/fory';
/**
 * @apache-fory/hps use v8's fast-calls-api that can be called directly by jit, ensure that the version of Node is 20 or above.
 * Experimental feature, installation success cannot be guaranteed at this moment
 * If you are unable to install the module, replace it with `const hps = null;`
 **/
import hps from '@apache-fory/hps';

const description = Type.object('example.foo', {
  foo: Type.string(),
  bar: Type.object('example.foo'),
});

const fory = new Fory({ hps });
const { serialize, deserialize } = fory.registerSerializer(description);
const data: any = {
  foo: 'hello fory',
};
data.bar = data;
const input = serialize(data);
const result = deserialize(input);
console.log(result.bar.foo === result.foo);
```

**JavaScript**
Reference cannot be implemented because of rust ownership restrictions

## Zero-Copy Serialization

**Java**

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.memory.MemoryBuffer;

import java.util.*;
import java.util.stream.Collectors;

public class ZeroCopyExample {
  // mvn exec:java -Dexec.mainClass="io.ray.fory.examples.ZeroCopyExample"
  public static void main(String[] args) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    List<Object> list = ofArrayList("str", new byte[1000], new int[100], new double[100]);
    Collection<BufferObject> bufferObjects = new ArrayList<>();
    byte[] bytes = fory.serialize(list, e -> !bufferObjects.add(e));
    // bytes can be data serialized by other languages.
    List<MemoryBuffer> buffers = bufferObjects.stream()
      .map(BufferObject::toBuffer).collect(Collectors.toList());
    System.out.println(fory.deserialize(bytes, buffers));
  }
}
```

**Python**

```python
import array
import pyfory
import numpy as np

fory = pyfory.Fory()
list_ = ["str", bytes(bytearray(1000)),
         array.array("i", range(100)), np.full(100, 0.0, dtype=np.double)]
serialized_objects = []
data = fory.serialize(list_, buffer_callback=serialized_objects.append)
buffers = [o.to_buffer() for o in serialized_objects]
# bytes can be data serialized by other languages.
print(fory.deserialize(data, buffers=buffers))
```

**Golang**

```go
package main

import forygo "github.com/apache/fory/fory/go/fory"
import "fmt"

func main() {
 fory := forygo.NewFory()
 list := []interface{}{"str", make([]byte, 1000)}
 buf := fory.NewByteBuffer(nil)
 var bufferObjects []fory.BufferObject
 fory.Serialize(buf, list, func(o fory.BufferObject) bool {
  bufferObjects = append(bufferObjects, o)
  return false
 })
 var newList []interface{}
 var buffers []*fory.ByteBuffer
 for _, o := range bufferObjects {
  buffers = append(buffers, o.ToBuffer())
 }
 if err := fory.Deserialize(buf, &newList, buffers); err != nil {
  panic(err)
 }
 fmt.Println(newList)
}
```

**JavaScript**

```javascript
// Coming soon
```
