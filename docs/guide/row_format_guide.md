---
title: Row Format Guide
sidebar_position: 1
id: row_format_guide
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

## Row format protocol

### Java

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

### Python

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
print(f"start: {datetime.datetime.now()}")
foo_row = pyfory.RowData(encoder.schema, binary)
print(foo_row.f2[100000], foo_row.f4[100000].f1, foo_row.f4[200000].f2[5])
print(f"end: {datetime.datetime.now()}")

binary = pickle.dumps(foo)
print(f"pickle start: {datetime.datetime.now()}")
new_foo = pickle.loads(binary)
print(new_foo.f2[100000], new_foo.f4[100000].f1, new_foo.f4[200000].f2[5])
print(f"pickle end: {datetime.datetime.now()}")
```

### Apache Arrow Support

Fory Format also supports automatic conversion from/to Arrow Table/RecordBatch.

Java:

```java
Schema schema = TypeInference.inferSchema(BeanA.class);
ArrowWriter arrowWriter = ArrowUtils.createArrowWriter(schema);
Encoder<BeanA> encoder = Encoders.rowEncoder(BeanA.class);
for (int i = 0; i < 10; i++) {
  BeanA beanA = BeanA.createBeanA(2);
  arrowWriter.write(encoder.toRow(beanA));
}
return arrowWriter.finishAsRecordBatch();
```

### Support for Interface and Extension Types

Fury now supports row format mapping for Java `interface` types and subclassed (`extends`) types, enabling more dynamic and flexible data schemas.

These enhancements were introduced in [#2243](https://github.com/apache/fury/pull/2243), [#2250](https://github.com/apache/fury/pull/2250), and [#2256](https://github.com/apache/fury/pull/2256).

#### Example: Interface Mapping with RowEncoder

```java
public interface Animal {
  String speak();
}

public class Dog implements Animal {
  public String name;

  @Override
  public String speak() {
    return "Woof";
  }
}

// Encode and decode using RowEncoder with interface type
RowEncoder<Animal> encoder = Encoders.bean(Animal.class);
Dog dog = new Dog();
dog.name = "Bingo";
BinaryRow row = encoder.toRow(dog);
Animal decoded = encoder.fromRow(row);
System.out.println(decoded.speak()); // Woof

```

#### Example: Extension Type with RowEncoder

```java
public class Parent {
    public String parentField;
}

public class Child extends Parent {
    public String childField;
}

// Encode and decode using RowEncoder with parent class type
RowEncoder<Parent> encoder = Encoders.bean(Parent.class);
Child child = new Child();
child.parentField = "Hello";
child.childField = "World";
BinaryRow row = encoder.toRow(child);
Parent decoded = encoder.fromRow(row);

```

Python:

```python
import pyfory
encoder = pyfory.encoder(Foo)
encoder.to_arrow_record_batch([foo] * 10000)
encoder.to_arrow_table([foo] * 10000)
```

C++

```c++
std::shared_ptr<ArrowWriter> arrow_writer;
EXPECT_TRUE(
    ArrowWriter::Make(schema, ::arrow::default_memory_pool(), &arrow_writer)
        .ok());
for (auto &row : rows) {
  EXPECT_TRUE(arrow_writer->Write(row).ok());
}
std::shared_ptr<::arrow::RecordBatch> record_batch;
EXPECT_TRUE(arrow_writer->Finish(&record_batch).ok());
EXPECT_TRUE(record_batch->Validate().ok());
EXPECT_EQ(record_batch->num_columns(), schema->num_fields());
EXPECT_EQ(record_batch->num_rows(), row_nums);
```

```java
Schema schema = TypeInference.inferSchema(BeanA.class);
ArrowWriter arrowWriter = ArrowUtils.createArrowWriter(schema);
Encoder<BeanA> encoder = Encoders.rowEncoder(BeanA.class);
for (int i = 0; i < 10; i++) {
  BeanA beanA = BeanA.createBeanA(2);
  arrowWriter.write(encoder.toRow(beanA));
}
return arrowWriter.finishAsRecordBatch();
```
