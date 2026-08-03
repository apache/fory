---
title: Python
sidebar_position: 3
id: python
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

## Output Layout

Python output is one module per schema file, for example:

- `<python_out>/addressbook.py`

## Type Generation

Unions generate a case enum plus a `Union` subclass with typed helpers:

```python
class AnimalCase(Enum):
    DOG = 1
    CAT = 2

class Animal(Union):
    @classmethod
    def dog(cls, v: Dog) -> "Animal": ...

    def case(self) -> AnimalCase: ...
    def case_id(self) -> int: ...

    def is_dog(self) -> bool: ...
    def dog_value(self) -> Dog: ...
    def set_dog(self, v: Dog) -> None: ...
```

Messages generate `@pyfory.dataclass` types, and nested types stay nested:

```python
@pyfory.dataclass
class Person:
    class PhoneType(IntEnum):
        MOBILE = 0
        HOME = 1
        WORK = 2

    @pyfory.dataclass
    class PhoneNumber:
        number: str = pyfory.field(id=1, default="")
        phone_type: Person.PhoneType = pyfory.field(id=2, default=None)

    name: str = pyfory.field(id=1, default="")
    phones: List[Person.PhoneNumber] = pyfory.field(id=7, default_factory=list)
    pet: Animal = pyfory.field(id=8, default=None)

    def to_bytes(self) -> bytes: ...
    @classmethod
    def from_bytes(cls, data: bytes) -> "Person": ...
```

## Registration

Generated registration function:

```python
def register_addressbook_types(fory: pyfory.Fory):
    fory.register_union(Animal, type_id=106, serializer=AnimalSerializer(fory))
    fory.register_type(Person, type_id=100)
    fory.register_type(Person.PhoneType, type_id=101)
    fory.register_type(Person.PhoneNumber, type_id=102)
    fory.register_type(Dog, type_id=104)
    fory.register_type(Cat, type_id=105)
    fory.register_type(AddressBook, type_id=103)
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs:

```python
fory.register_type(Status, type_id=1124725126)
fory.register_union(Wrapper, type_id=1471345060, serializer=WrapperSerializer(fory))
fory.register_type(Envelope, type_id=3022445236)
fory.register_union(Envelope.Detail, type_id=1609214087, serializer=Envelope.DetailSerializer(fory))
fory.register_type(Envelope.Payload, type_id=2862577837)
```

If `option enable_auto_type_id = false;` is set:

```python
fory.register_type(Config, name="myapp.models.Config")
fory.register_union(
    Holder,
    name="myapp.models.Holder",
    serializer=HolderSerializer(fory),
)
```

## Usage

```python
person = Person(name="Alice", pet=Animal.dog(Dog(name="Rex", bark_volume=10)))

data = person.to_bytes()
restored = Person.from_bytes(data)
```

## gRPC Service Companions

With `--grpc`, Python emits `<module>_grpc.py` with stubs, servicer bases, and registration functions. Async `grpc.aio` is the default; `--grpc-python-mode=sync` keeps the same filename and public names. See [Python gRPC](../../grpc/python.md) for dependencies and usage.
