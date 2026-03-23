---
title: Generated Code
sidebar_position: 5
id: generated_code
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

This document explains generated code for each target language.

Fory IDL generated types are idiomatic in host languages and can be used directly as domain objects. Generated types also include `to/from bytes` helpers and registration helpers.

## Reference Schemas

The examples below use two real schemas:

1. `addressbook.fdl` (explicit type IDs)
2. `auto_id.fdl` (no explicit type IDs)

### `addressbook.fdl` (excerpt)

```protobuf
package addressbook;

option go_package = "github.com/myorg/myrepo/gen/addressbook;addressbook";

message Person [id=100] {
    string name = 1;
    int32 id = 2;

    enum PhoneType [id=101] {
        PHONE_TYPE_MOBILE = 0;
        PHONE_TYPE_HOME = 1;
        PHONE_TYPE_WORK = 2;
    }

    message PhoneNumber [id=102] {
        string number = 1;
        PhoneType phone_type = 2;
    }

    list<PhoneNumber> phones = 7;
    Animal pet = 8;
}

message Dog [id=104] {
    string name = 1;
    int32 bark_volume = 2;
}

message Cat [id=105] {
    string name = 1;
    int32 lives = 2;
}

union Animal [id=106] {
    Dog dog = 1;
    Cat cat = 2;
}

message AddressBook [id=103] {
    list<Person> people = 1;
    map<string, Person> people_by_name = 2;
}
```

### `auto_id.fdl` (excerpt)

```protobuf
package auto_id;

enum Status {
    UNKNOWN = 0;
    OK = 1;
}

message Envelope {
    string id = 1;

    message Payload {
        int32 value = 1;
    }

    union Detail {
        Payload payload = 1;
        string note = 2;
    }

    Payload payload = 2;
    Detail detail = 3;
    Status status = 4;
}

union Wrapper {
    Envelope envelope = 1;
    string raw = 2;
}
```

## Java

### Output Layout

For `package addressbook`, Java output is generated under:

- `<java_out>/addressbook/`
- Type files: `AddressBook.java`, `Person.java`, `Dog.java`, `Cat.java`, `Animal.java`
- Registration helper: `AddressbookForyRegistration.java`

### Type Generation

Messages generate Java classes with `@ForyField`, default constructors, getters/setters, and byte helpers:

```java
public class Person {
    public static enum PhoneType {
        MOBILE,
        HOME,
        WORK;
    }

    public static class PhoneNumber {
        @ForyField(id = 1)
        private String number;

        @ForyField(id = 2)
        private PhoneType phoneType;

        public byte[] toBytes() { ... }
        public static PhoneNumber fromBytes(byte[] bytes) { ... }
    }

    @ForyField(id = 1)
    private String name;

    @ForyField(id = 8)
    private Animal pet;

    public byte[] toBytes() { ... }
    public static Person fromBytes(byte[] bytes) { ... }
}
```

Unions generate classes extending `org.apache.fory.type.union.Union`:

```java
public final class Animal extends Union {
    public enum AnimalCase {
        DOG(1),
        CAT(2);
        public final int id;
        AnimalCase(int id) { this.id = id; }
    }

    public static Animal ofDog(Dog v) { ... }
    public AnimalCase getAnimalCase() { ... }
    public int getAnimalCaseId() { ... }

    public boolean hasDog() { ... }
    public Dog getDog() { ... }
    public void setDog(Dog v) { ... }
}
```

### Registration

Generated registration helper:

```java
public static void register(Fory fory) {
    org.apache.fory.resolver.TypeResolver resolver = fory.getTypeResolver();
    resolver.registerUnion(Animal.class, 106L, new org.apache.fory.serializer.UnionSerializer(fory, Animal.class));
    resolver.register(Person.class, 100L);
    resolver.register(Person.PhoneType.class, 101L);
    resolver.register(Person.PhoneNumber.class, 102L);
    resolver.register(Dog.class, 104L);
    resolver.register(Cat.class, 105L);
    resolver.register(AddressBook.class, 103L);
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs (for example from `auto_id.fdl`):

```java
resolver.register(Status.class, 1124725126L);
resolver.registerUnion(Wrapper.class, 1471345060L, new org.apache.fory.serializer.UnionSerializer(fory, Wrapper.class));
resolver.register(Envelope.class, 3022445236L);
resolver.registerUnion(Envelope.Detail.class, 1609214087L, new org.apache.fory.serializer.UnionSerializer(fory, Envelope.Detail.class));
resolver.register(Envelope.Payload.class, 2862577837L);
```

If `option enable_auto_type_id = false;` is set, registration uses namespace and type name:

```java
resolver.register(Config.class, "myapp.models", "Config");
resolver.registerUnion(
    Holder.class,
    "myapp.models",
    "Holder",
    new org.apache.fory.serializer.UnionSerializer(fory, Holder.class));
```

### Usage

```java
Person person = new Person();
person.setName("Alice");
person.setPet(Animal.ofDog(new Dog()));

byte[] data = person.toBytes();
Person restored = Person.fromBytes(data);
```

## Python

### Output Layout

Python output is one module per schema file, for example:

- `<python_out>/addressbook.py`

### Type Generation

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

### Registration

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
fory.register_type(Config, namespace="myapp.models", typename="Config")
fory.register_union(
    Holder,
    namespace="myapp.models",
    typename="Holder",
    serializer=HolderSerializer(fory),
)
```

### Usage

```python
person = Person(name="Alice", pet=Animal.dog(Dog(name="Rex", bark_volume=10)))

data = person.to_bytes()
restored = Person.from_bytes(data)
```

## Rust

### Output Layout

Rust output is one module file per schema, for example:

- `<rust_out>/addressbook.rs`

### Type Generation

Unions map to Rust enums with `#[fory(id = ...)]` case attributes:

```rust
#[derive(ForyObject, Debug, Clone, PartialEq)]
pub enum Animal {
    #[fory(id = 1)]
    Dog(Dog),
    #[fory(id = 2)]
    Cat(Cat),
}
```

Nested types generate nested modules:

```rust
pub mod person {
    #[derive(ForyObject, Debug, Clone, PartialEq, Default)]
    #[repr(i32)]
    pub enum PhoneType {
        #[default]
        Mobile = 0,
        Home = 1,
        Work = 2,
    }

    #[derive(ForyObject, Debug, Clone, PartialEq, Default)]
    pub struct PhoneNumber {
        #[fory(id = 1)]
        pub number: String,
        #[fory(id = 2)]
        pub phone_type: PhoneType,
    }
}
```

Messages derive `ForyObject` and include `to_bytes`/`from_bytes` helpers:

```rust
#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
pub struct Person {
    #[fory(id = 1)]
    pub name: String,
    #[fory(id = 7)]
    pub phones: Vec<person::PhoneNumber>,
    #[fory(id = 8, type_id = "union")]
    pub pet: Animal,
}
```

### Registration

Generated registration function:

```rust
pub fn register_types(fory: &mut Fory) -> Result<(), fory::Error> {
    fory.register_union::<Animal>(106)?;
    fory.register::<person::PhoneType>(101)?;
    fory.register::<person::PhoneNumber>(102)?;
    fory.register::<Person>(100)?;
    fory.register::<Dog>(104)?;
    fory.register::<Cat>(105)?;
    fory.register::<AddressBook>(103)?;
    Ok(())
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs:

```rust
fory.register::<Status>(1124725126)?;
fory.register_union::<Wrapper>(1471345060)?;
fory.register::<Envelope>(3022445236)?;
fory.register_union::<envelope::Detail>(1609214087)?;
fory.register::<envelope::Payload>(2862577837)?;
```

If `option enable_auto_type_id = false;` is set:

```rust
fory.register_by_namespace::<Config>("myapp.models", "Config")?;
fory.register_union_by_namespace::<Holder>("myapp.models", "Holder")?;
```

### Usage

```rust
let person = Person {
    name: "Alice".into(),
    pet: Animal::Dog(Dog::default()),
    ..Default::default()
};

let bytes = person.to_bytes()?;
let restored = Person::from_bytes(&bytes)?;
```

## C++

### Output Layout

C++ output is one header per schema file, for example:

- `<cpp_out>/addressbook.h`

### Type Generation

Messages generate `final` classes with typed accessors and byte helpers:

```cpp
class Person final {
 public:
  class PhoneNumber final {
   public:
    const std::string& number() const;
    std::string* mutable_number();
    template <class Arg, class... Args>
    void set_number(Arg&& arg, Args&&... args);

    fory::Result<std::vector<uint8_t>, fory::Error> to_bytes() const;
    static fory::Result<PhoneNumber, fory::Error> from_bytes(const std::vector<uint8_t>& data);
  };

  const std::string& name() const;
  std::string* mutable_name();
  template <class Arg, class... Args>
  void set_name(Arg&& arg, Args&&... args);

  const Animal& pet() const;
  Animal* mutable_pet();
};
```

Optional message fields generate `has_xxx`, `mutable_xxx`, and `clear_xxx` APIs:

```cpp
class Envelope final {
 public:
  bool has_payload() const { return payload_ != nullptr; }
  const Envelope::Payload& payload() const { return *payload_; }
  Envelope::Payload* mutable_payload() {
    if (!payload_) {
      payload_ = std::make_unique<Envelope::Payload>();
    }
    return payload_.get();
  }
  void clear_payload() { payload_.reset(); }

 private:
  std::unique_ptr<Envelope::Payload> payload_;
};
```

Unions generate `std::variant` wrappers:

```cpp
class Animal final {
 public:
  enum class AnimalCase : uint32_t {
    DOG = 1,
    CAT = 2,
  };

  static Animal dog(Dog v);
  static Animal cat(Cat v);

  AnimalCase animal_case() const noexcept;
  uint32_t animal_case_id() const noexcept;

  bool is_dog() const noexcept;
  const Dog* as_dog() const noexcept;
  Dog* as_dog() noexcept;
  const Dog& dog() const;
  Dog& dog();

  template <class Visitor>
  decltype(auto) visit(Visitor&& vis) const;

 private:
  std::variant<Dog, Cat> value_;
};
```

Generated headers also include `FORY_UNION`, `FORY_FIELD_CONFIG`, `FORY_ENUM`, and `FORY_STRUCT` macros for serialization metadata.

### Registration

Generated registration function:

```cpp
inline void register_types(fory::serialization::BaseFory& fory) {
    fory.register_union<Animal>(106);
    fory.register_enum<Person::PhoneType>(101);
    fory.register_struct<Person::PhoneNumber>(102);
    fory.register_struct<Person>(100);
    fory.register_struct<Dog>(104);
    fory.register_struct<Cat>(105);
    fory.register_struct<AddressBook>(103);
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs:

```cpp
fory.register_enum<Status>(1124725126);
fory.register_union<Wrapper>(1471345060);
fory.register_struct<Envelope>(3022445236);
fory.register_union<Envelope::Detail>(1609214087);
fory.register_struct<Envelope::Payload>(2862577837);
```

If `option enable_auto_type_id = false;` is set:

```cpp
fory.register_struct<Config>("myapp.models", "Config");
fory.register_union<Holder>("myapp.models", "Holder");
```

### Usage

```cpp
addressbook::Person person;
person.set_name("Alice");
*person.mutable_pet() = addressbook::Animal::dog(addressbook::Dog{});

auto bytes = person.to_bytes();
auto restored = addressbook::Person::from_bytes(bytes.value());
```

## Go

### Output Layout

Go output path depends on schema options and `--go_out`.

For `addressbook.fdl`, `go_package` is configured and generated output follows the configured import path/package (for example under your `--go_out` root).

Without `go_package`, output uses the requested `--go_out` directory and package-derived file naming.

### Type Generation

Nested types use underscore naming by default (`Person_PhoneType`, `Person_PhoneNumber`):

```go
type Person_PhoneType int32

const (
    Person_PhoneTypeMobile Person_PhoneType = 0
    Person_PhoneTypeHome   Person_PhoneType = 1
    Person_PhoneTypeWork   Person_PhoneType = 2
)

type Person_PhoneNumber struct {
    Number    string           `fory:"id=1"`
    PhoneType Person_PhoneType `fory:"id=2"`
}
```

Messages generate structs with `fory` tags and byte helpers:

```go
type Person struct {
    Name   string               `fory:"id=1"`
    Id     int32                `fory:"id=2,compress=true"`
    Phones []Person_PhoneNumber `fory:"id=7"`
    Pet    Animal               `fory:"id=8"`
}

func (m *Person) ToBytes() ([]byte, error) { ... }
func (m *Person) FromBytes(data []byte) error { ... }
```

Unions generate typed case structs with constructors/accessors/visitor APIs:

```go
type AnimalCase uint32

type Animal struct {
    case_ AnimalCase
    value any
}

func DogAnimal(v *Dog) Animal { ... }
func CatAnimal(v *Cat) Animal { ... }

func (u Animal) Case() AnimalCase { ... }
func (u Animal) AsDog() (*Dog, bool) { ... }
func (u Animal) Visit(visitor AnimalVisitor) error { ... }
```

### Registration

Generated registration function:

```go
func RegisterTypes(f *fory.Fory) error {
    if err := f.RegisterUnion(Animal{}, 106, fory.NewUnionSerializer(...)); err != nil {
        return err
    }
    if err := f.RegisterEnum(Person_PhoneType(0), 101); err != nil {
        return err
    }
    if err := f.RegisterStruct(Person_PhoneNumber{}, 102); err != nil {
        return err
    }
    if err := f.RegisterStruct(Person{}, 100); err != nil {
        return err
    }
    return nil
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs:

```go
if err := f.RegisterEnum(Status(0), 1124725126); err != nil { ... }
if err := f.RegisterUnion(Wrapper{}, 1471345060, fory.NewUnionSerializer(...)); err != nil { ... }
if err := f.RegisterStruct(Envelope{}, 3022445236); err != nil { ... }
if err := f.RegisterUnion(Envelope_Detail{}, 1609214087, fory.NewUnionSerializer(...)); err != nil { ... }
if err := f.RegisterStruct(Envelope_Payload{}, 2862577837); err != nil { ... }
```

If `option enable_auto_type_id = false;` is set:

```go
if err := f.RegisterNamedStruct(Config{}, "myapp.models.Config"); err != nil { ... }
if err := f.RegisterNamedUnion(Holder{}, "myapp.models.Holder", fory.NewUnionSerializer(...)); err != nil { ... }
```

`go_nested_type_style` controls nested type naming:

```protobuf
option go_nested_type_style = "camelcase";
```

The CLI flag `--go_nested_type_style` overrides this schema option when both are set.

### Usage

```go
person := &Person{
    Name: "Alice",
    Pet:  DogAnimal(&Dog{Name: "Rex"}),
}

data, err := person.ToBytes()
if err != nil {
    panic(err)
}
var restored Person
if err := restored.FromBytes(data); err != nil {
    panic(err)
}
```

## C\#

### Output Layout

C# output is one `.cs` file per schema, for example:

- `<csharp_out>/addressbook/addressbook.cs`

### Type Generation

Messages generate `[ForyObject]` classes with C# properties and byte helpers:

```csharp
[ForyObject]
public sealed partial class Person
{
    public string Name { get; set; } = string.Empty;
    public int Id { get; set; }
    public List<Person.PhoneNumber> Phones { get; set; } = new();
    public Animal Pet { get; set; } = null!;

    public byte[] ToBytes() { ... }
    public static Person FromBytes(byte[] data) { ... }
}
```

Unions generate `Union` subclasses with typed case helpers:

```csharp
public sealed class Animal : Union
{
    public static Animal Dog(Dog value) { ... }
    public static Animal Cat(Cat value) { ... }
    public bool IsDog => ...;
    public Dog DogValue() { ... }
}
```

### Registration

Each schema generates a registration helper:

```csharp
public static class AddressbookForyRegistration
{
    public static void Register(Fory fory)
    {
        fory.Register<addressbook.Animal>((uint)106);
        fory.Register<addressbook.Person>((uint)100);
        // ...
    }
}
```

When explicit type IDs are not provided, generated registration uses computed
numeric IDs (same behavior as other targets).

## JavaScript

### Output Layout

JavaScript output is one `.ts` file per schema, for example:

- `<javascript_out>/addressbook.ts`

### Type Generation

Messages generate `export interface` declarations with camelCase field names:

```typescript
export interface Person {
  name: string;
  id: number;
  phones: PhoneNumber[];
  pet?: Animal | undefined;
}
```

Enums generate `export enum` declarations:

```typescript
export enum PhoneType {
  PHONE_TYPE_MOBILE = 0,
  PHONE_TYPE_HOME = 1,
  PHONE_TYPE_WORK = 2,
}
```

Unions generate a discriminated union with a case enum:

```typescript
export enum AnimalCase {
  DOG = 1,
  CAT = 2,
}

export type Animal =
  | { case: AnimalCase.DOG; value: Dog }
  | { case: AnimalCase.CAT; value: Cat };
```

## Swift

### Output Layout

Swift output is one `.swift` file per schema, for example:

- `<swift_out>/addressbook/addressbook.swift`

### Type Generation

The generator creates Swift models with `@ForyObject` and field IDs.

When package/namespace is non-empty, namespace shaping is controlled by `swift_namespace_style`:

- `enum` (default): nested enum namespace wrappers.
- `flatten`: package-derived prefix on top-level type names (for example `Demo_Foo_User`).

When package/namespace is empty, no enum wrapper or flatten prefix is applied.

For non-empty package with default `enum` style:

```swift
public enum Addressbook {
    @ForyObject
    public enum Animal: Equatable {
        @ForyField(id: 1)
        case dog(Addressbook.Dog)
        @ForyField(id: 2)
        case cat(Addressbook.Cat)
    }

    @ForyObject
    public struct Person: Equatable {
        @ForyField(id: 1)
        public var name: String = ""
        @ForyField(id: 8)
        public var pet: Addressbook.Animal = .foryDefault()
    }
}
```

For non-empty package with `flatten` style:

```swift
@ForyObject
public struct Addressbook_Person: Equatable { ... }
```

The CLI flag `--swift_namespace_style` overrides schema option `swift_namespace_style` when both are set.

Unions are generated as tagged Swift enums with associated payload values.
Messages with `ref`/`weak_ref` fields are generated as `final class` models to preserve reference semantics.

### Registration

Each schema includes a registration helper with transitive import registration:

```swift
public enum ForyRegistration {
    public static func register(_ fory: Fory) throws {
        try ComplexPb.ForyRegistration.register(fory)
        fory.register(Addressbook.Person.self, id: 100)
        fory.register(Addressbook.Animal.self, id: 106)
    }
}
```

With non-empty package and `flatten` style, the helper is prefixed too (for example `Addressbook_ForyRegistration`).

For schemas without explicit `[id=...]`, registration uses computed numeric IDs.
If `option enable_auto_type_id = false;` is set, generated code uses name-based registration APIs.

## Cross-Language Notes

### Type ID Behavior

- Explicit `[id=...]` values are used directly in generated registration.
- When type IDs are omitted, generated code uses computed numeric IDs (see `auto_id.*` outputs).
- If `option enable_auto_type_id = false;` is set, generated registration uses namespace/type-name APIs instead of numeric IDs.

### Nested Type Shape

| Language   | Nested type form               |
| ---------- | ------------------------------ |
| Java       | `Person.PhoneNumber`           |
| Python     | `Person.PhoneNumber`           |
| Rust       | `person::PhoneNumber`          |
| C++        | `Person::PhoneNumber`          |
| Go         | `Person_PhoneNumber` (default) |
| C#         | `Person.PhoneNumber`           |
| JavaScript | `PhoneNumber` (flat)           |
| Swift      | `Person.PhoneNumber`           |

### Byte Helper Naming

| Language   | Helpers                   |
| ---------- | ------------------------- |
| Java       | `toBytes` / `fromBytes`   |
| Python     | `to_bytes` / `from_bytes` |
| Rust       | `to_bytes` / `from_bytes` |
| C++        | `to_bytes` / `from_bytes` |
| Go         | `ToBytes` / `FromBytes`   |
| C#         | `ToBytes` / `FromBytes`   |
| JavaScript | (via `fory.serialize()`)  |
| Swift      | `toBytes` / `fromBytes`   |
