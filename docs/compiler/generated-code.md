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

Fory IDL generated types are idiomatic in host languages and can be used directly as domain objects. Generated types also include `to/from bytes` helpers and schema modules or registration helpers, depending on the target language.

Generated schema modules are schema-file owners, not package or namespace owners. In targets that expose the owner directly in a language package or namespace, the owner name includes a source-file-derived prefix such as `AddressbookForyModule` or `ComplexPbForyModule` so multiple IDL files can target the same package or namespace without producing colliding `ForyModule` types.

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
- Schema module: `AddressbookForyModule.java`

For schemas without a Java package, the schema module name is derived from the
source file stem, for example `main.fdl` generates `MainForyModule.java`.
Java import graphs cannot mix default-package schemas with named Java packages.

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

Messages with `evolving=false` are generated with Java fixed-schema struct encoding.

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

### Schema Module

Each JVM schema generates a `ForyModule`. Imported schema modules are installed
through `fory.register(...)`, so shared imports are deduplicated by the runtime.

```java
public final class AddressbookForyModule implements org.apache.fory.ForyModule {
  public static final AddressbookForyModule INSTANCE = new AddressbookForyModule();

  static ThreadSafeFory getFory() { ... }

  @Override
  public void install(Fory fory) {
    org.apache.fory.resolver.TypeResolver resolver = fory.getTypeResolver();
    resolver.registerUnion(Animal.class, 106L, new org.apache.fory.serializer.UnionSerializer(resolver, Animal.class));
    resolver.register(Person.class, 100L);
    resolver.register(Person.PhoneType.class, 101L);
    resolver.register(Person.PhoneNumber.class, 102L);
    resolver.register(Dog.class, 104L);
    resolver.register(Cat.class, 105L);
    resolver.register(AddressBook.class, 103L);
  }
}
```

For schemas without explicit `[id=...]`, generated registration uses computed numeric IDs (for example from `auto_id.fdl`):

```java
resolver.register(Status.class, 1124725126L);
resolver.registerUnion(Wrapper.class, 1471345060L, new org.apache.fory.serializer.UnionSerializer(resolver, Wrapper.class));
resolver.register(Envelope.class, 3022445236L);
resolver.registerUnion(Envelope.Detail.class, 1609214087L, new org.apache.fory.serializer.UnionSerializer(resolver, Envelope.Detail.class));
resolver.register(Envelope.Payload.class, 2862577837L);
```

If `option enable_auto_type_id = false;` is set, registration uses namespace and type name:

```java
resolver.register(Config.class, "myapp.models", "Config");
resolver.registerUnion(
    Holder.class,
    "myapp.models",
    "Holder",
    new org.apache.fory.serializer.UnionSerializer(resolver, Holder.class));
```

### Usage

```java
Person person = new Person();
person.setName("Alice");
person.setPet(Animal.ofDog(new Dog()));

byte[] data = person.toBytes();
Person restored = Person.fromBytes(data);
```

### gRPC Service Companions

When a schema contains services and the compiler is run with `--grpc`, Java
generation emits one `<ServiceName>Grpc.java` file per service next to the model
types.

```java
public final class AddressBookServiceGrpc {
  public static final String SERVICE_NAME = "addressbook.AddressBookService";

  public static AddressBookServiceStub newStub(io.grpc.Channel channel) { ... }
  public static AddressBookServiceBlockingStub newBlockingStub(io.grpc.Channel channel) { ... }
  public static AddressBookServiceFutureStub newFutureStub(io.grpc.Channel channel) { ... }

  public abstract static class AddressBookServiceImplBase
      implements io.grpc.BindableService {
    public void lookup(Person request, io.grpc.stub.StreamObserver<AddressBook> responseObserver) { ... }
  }
}
```

The generated marshaller serializes each request or response with the schema
module's `ThreadSafeFory`. It uses grpc-java's `MethodDescriptor.Marshaller`
API, so applications compiling these files must provide grpc-java dependencies.
Those dependencies are not added to Fory Java runtime artifacts.

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

### gRPC Service Companions

When a schema contains services and the compiler is run with `--grpc`, Python
generation emits a companion module named `<module>_grpc.py`. The module name is
derived from the Fory package by replacing dots with underscores, or `generated`
when the schema has no package.

```python
class AddressBookServiceStub:
    def __init__(self, channel):
        self.lookup = channel.unary_unary(
            "/addressbook.AddressBookService/Lookup",
            request_serializer=_serialize,
            response_deserializer=_deserialize,
        )


class AddressBookServiceServicer:
    def lookup(self, request, context):
        raise NotImplementedError("Method not implemented!")


def add_servicer(servicer, server): ...
```

Python gRPC serializers receive and return complete `bytes` payloads, so the
generated callbacks call the model module's `_get_fory().serialize(...)` and
`_get_fory().deserialize(...)` directly. Applications using the generated
companion module must install `grpcio`; `pyfory` does not add a hard gRPC
dependency. The Python API uses snake_case method names while preserving the
original IDL method names in the gRPC wire paths.

## Rust

### Output Layout

Rust output is one module file per schema, for example:

- `<rust_out>/addressbook.rs`

### Type Generation

Unions map to Rust enums with `#[fory(id = ...)]` schema case attributes.
`#[fory(unknown)] Unknown(::fory::UnknownCase)` marks the runtime
forward-compatibility carrier. The marker only selects the carrier and does not
add an entry to the schema case table; schema cases still use the full `0..N`
ID range. A generated typed union must have at least one non-`Unknown` case. The
compiler marks the first declared non-`Unknown` case as `#[fory(default)]` and
emits `Default` from that case:

```rust
#[derive(::fory::ForyUnion, Clone, Debug, PartialEq, Eq, Hash)]
pub enum Animal {
    #[fory(unknown)]
    Unknown(::fory::UnknownCase),
    #[fory(id = 0, default)]
    Dog(self::Dog),
    #[fory(id = 1)]
    Cat(self::Cat),
}

impl ::std::default::Default for Animal {
    fn default() -> Self {
        Self::Dog(<self::Dog as ::fory::ForyDefault>::fory_default())
    }
}
```

Nested types generate nested modules:

```rust
pub mod person {
    #[derive(ForyEnum, Debug, Clone, PartialEq, Default)]
    #[repr(i32)]
    pub enum PhoneType {
        #[default]
        Mobile = 0,
        Home = 1,
        Work = 2,
    }

    #[derive(ForyStruct, Debug, Clone, PartialEq, Default)]
    pub struct PhoneNumber {
        #[fory(id = 1)]
        pub number: String,
        #[fory(id = 2)]
        pub phone_type: PhoneType,
    }
}
```

Messages derive `ForyStruct` and include `to_bytes`/`from_bytes` helpers:

```rust
#[derive(ForyStruct, Debug, Clone, PartialEq, Default)]
pub struct Person {
    #[fory(id = 1)]
    pub name: String,
    #[fory(id = 7)]
    pub phones: Vec<person::PhoneNumber>,
    #[fory(id = 8)]
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
fory.register_by_name::<Config>("myapp.models", "Config")?;
fory.register_union_by_name::<Holder>("myapp.models", "Holder")?;
```

### Usage

```rust
let person = Person {
    name: "Alice".into(),
    pet: Animal::Dog(self::Dog::default()),
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

Generated headers include `FORY_UNION`, `FORY_ENUM`, and `FORY_STRUCT` macros
for serialization metadata. Field and payload configuration is embedded in the
generated `FORY_STRUCT`/`FORY_UNION` entries.

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
    Id     int32                `fory:"id=2"`
    Phones []Person_PhoneNumber `fory:"id=7,type=list"`
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
if err := f.RegisterStructByName(Config{}, "myapp.models.Config"); err != nil { ... }
if err := f.RegisterUnionByName(Holder{}, "myapp.models.Holder", fory.NewUnionSerializer(...)); err != nil { ... }
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

Messages generate `[ForyStruct]` classes with C# properties and byte helpers:

```csharp
[ForyStruct]
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

Unions generate `[ForyUnion]` ADTs. `Unknown(UnknownCase)` is the
runtime-owned forward-compatibility carrier marked with `[ForyUnknownCase]`.
The marker only selects the carrier and does not add an entry to the schema case
table. Schema-defined cases use non-negative `[ForyCase]` IDs. If a case needs
non-default schema encoding, the generated `[ForyCase]` carries `Type`. Known
case record names are PascalCase FDL case names; payload types are emitted as
qualified references when needed to avoid name conflicts. A typed union must
have at least one non-`Unknown` case.

```csharp
[ForyUnion]
public abstract partial record Animal
{
    private Animal() {}

    [ForyUnknownCase]
    public sealed partial record Unknown(UnknownCase Value) : Animal;

    [ForyCase(0)]
    public sealed partial record Dog(global::addressbook.Dog Value) : Animal;

    [ForyCase(1)]
    public sealed partial record Cat(global::addressbook.Cat Value) : Animal;
}
```

### Module Installation

Each schema generates a module owner that installs imported modules first and
then registers the local schema types:

```csharp
public static class AddressbookForyModule
{
    public static void Install(Fory fory)
    {
        fory.Register<addressbook.Animal>((uint)106);
        fory.Register<addressbook.Person>((uint)100);
        // ...
    }
}
```

The C# module owner keeps the schema-file prefix even when several schemas share
the same C# namespace.

When explicit type IDs are not provided, generated installation uses computed
numeric IDs (same behavior as other targets).

## JavaScript/TypeScript

### Output Layout

JavaScript/TypeScript output is one `.ts` file per schema, for example:

- `<javascript_out>/addressbook.ts`

### Type Generation

Messages generate `export interface` declarations with camelCase field names:

```typescript
export interface Person {
  name: string;
  id: number;
  phones: PhoneNumber[];
  pet?: Animal | null;
}
```

Enums generate `export enum` declarations:

```typescript
export enum PhoneType {
  MOBILE = 0,
  HOME = 1,
  WORK = 2,
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

The generator creates Swift models with split model macros and stable field/case IDs.
A typed union must include `@ForyUnknownCase case unknown(UnknownCase)` and at
least one non-`unknown` case; `unknown(UnknownCase)` is only the
runtime-owned forward-compatibility carrier. The marker only selects the carrier
and does not add an entry to the schema case table.

When package/namespace is non-empty, namespace shaping is controlled by `swift_namespace_style`:

- `enum` (default): nested enum namespace wrappers.
- `flatten`: package-derived prefix on top-level type names (for example `Demo_Foo_User`).

When package/namespace is empty, no enum wrapper or flatten prefix is applied.

For non-empty package with default `enum` style:

```swift
public enum Addressbook {
    @ForyUnion
    public enum Animal {
        @ForyUnknownCase
        case unknown(UnknownCase)
        @ForyCase(id: 0)
        case dog(Addressbook.Dog)
        @ForyCase(id: 1)
        case cat(Addressbook.Cat)
    }

    @ForyStruct
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
@ForyStruct
public struct Addressbook_Person: Equatable { ... }
```

The CLI flag `--swift_namespace_style` overrides schema option `swift_namespace_style` when both are set.

Unions are generated as tagged Swift enums with associated payload values.
Messages with `ref`/`weak_ref` fields are generated as `final class` models to preserve reference semantics.
Fixed or tagged integer encodings inside list/map fields are emitted as Swift
field type hints, for example `@ListField(element: .encoding(.fixed))` or
`@MapField(value: .encoding(.tagged))`.
For non-null fixed-width integer list elements, Swift classifies the field as
the corresponding Fory primitive packed-array type; fixed-width integer sets
remain Fory sets.

### Module Installation

Each schema includes a `ForyModule` owner with transitive import installation:

```swift
public enum ForyModule {
    public static func install(_ fory: Fory) throws {
        try ComplexPb.ForyModule.install(fory)
        fory.register(Addressbook.Person.self, id: 100)
        fory.register(Addressbook.Animal.self, id: 106)
    }
}
```

With non-empty package and `flatten` style, the helper is prefixed too (for example `Addressbook_ForyModule`).

For schemas without explicit `[id=...]`, installation uses computed numeric IDs.
If `option enable_auto_type_id = false;` is set, generated code uses name-based registration APIs.

## Dart

### Output Layout

Dart output is two files per schema: a main `.dart` file with annotated types and the IDL module owner, and a `.fory.dart` part file with generated serializers and metadata.

- `<dart_out>/package/package.dart`
- `<dart_out>/package/package.fory.dart`

### Type Generation

Messages generate `@ForyStruct` annotated `final class` declarations with `@ForyField` on each field:

```dart
@ForyStruct()
final class Person {
  Person();

  @ForyField(id: 1)
  String name = '';

  @ForyField(id: 2, type: Int32Type())
  int id = 0;

  @ForyField(id: 7)
  List<Person_PhoneNumber> phones = <Person_PhoneNumber>[];

  @ForyField(id: 8)
  Animal pet = Animal._empty();
}
```

Enums generate Dart `enum` declarations with a `rawValue` getter and `fromRawValue` factory:

```dart
enum Person_PhoneType {
  mobile,
  home,
  work;

  int get rawValue => switch (this) {
    Person_PhoneType.mobile => 0,
    Person_PhoneType.home => 1,
    Person_PhoneType.work => 2,
  };

  static Person_PhoneType fromRawValue(int value) => switch (value) {
    0 => Person_PhoneType.mobile,
    1 => Person_PhoneType.home,
    2 => Person_PhoneType.work,
    _ => throw StateError('Unknown Person_PhoneType raw value $value.'),
  };
}
```

Unions generate `@ForyUnion` annotated classes with factory constructors, a case enum, and a custom serializer:

```dart
enum AnimalCase {
  dog,
  cat;

  int get id => switch (this) {
    AnimalCase.dog => 1,
    AnimalCase.cat => 2,
  };
}

@ForyUnion()
final class Animal {
  final AnimalCase _case;
  final Object? _value;

  const Animal._(this._case, this._value);

  factory Animal.dog(Dog value) => Animal._(AnimalCase.dog, value);
  factory Animal.cat(Cat value) => Animal._(AnimalCase.cat, value);

  bool get isDog => _case == AnimalCase.dog;
  Dog get dogValue => _value as Dog;
  // ...
}
```

Nested types use flat underscore naming (e.g., `Person_PhoneNumber`, `Person_PhoneType`).

`list<T>` fields generate ordered collection carriers and use the Fory list
protocol. `array<T>` fields generate dense one-dimensional bool or numeric
carriers and use the specialized dense-array protocol. Generated code must not
choose `array<T>` only because a language has an optimized list-like carrier;
the schema kind comes from the IDL.

| IDL schema          | Dart generated carrier | Notes                                      |
| ------------------- | ---------------------- | ------------------------------------------ |
| `list<int32>`       | `List<int>`            | List protocol, varint element encoding     |
| `list<fixed int32>` | `List<int>`            | List protocol, fixed-width element segment |
| `array<bool>`       | `BoolList`             | One byte per bool                          |
| `array<int8>`       | `Int8List`             | Dense signed bytes                         |
| `array<int16>`      | `Int16List`            | Dense little-endian int16                  |
| `array<int32>`      | `Int32List`            | Dense little-endian int32                  |
| `array<int64>`      | `Int64List`            | Dense little-endian int64                  |
| `array<uint8>`      | `Uint8List`            | Dense unsigned bytes                       |
| `array<uint16>`     | `Uint16List`           | Dense little-endian uint16                 |
| `array<uint32>`     | `Uint32List`           | Dense little-endian uint32                 |
| `array<uint64>`     | `Uint64List`           | Dense little-endian uint64                 |
| `array<float16>`    | `Float16List`          | Dense binary16 storage                     |
| `array<bfloat16>`   | `Bfloat16List`         | Dense bfloat16 storage                     |
| `array<float32>`    | `Float32List`          | Dense little-endian float32                |
| `array<float64>`    | `Float64List`          | Dense little-endian float64                |

Generated Dart fields that use `ArrayType(element: BoolType())` must use
`BoolList`; plain `List<bool>` remains the generated and handwritten carrier
for `list<bool>`.

Reference tracking on list elements or map values uses the container sugar annotations:

```dart
@ListField(element: DeclaredType(ref: true))
@ForyField(id: 3)
List<Node> children = <Node>[];

@MapField(value: DeclaredType(ref: true))
@ForyField(id: 2)
Map<String, Node> byName = <String, Node>{};
```

### Module Installation

Each generated Dart IDL library includes a module owner named after the input
file, such as `AddressbookForyModule` for `addressbook.dart`. The module
installs imported modules first and then registers every local schema type with
its default IDL identity:

```dart
abstract final class AddressbookForyModule {
  static void install(Fory fory) {
    complex_pb.ComplexPbForyModule.install(fory);
    _registerType(fory, Person);
    _registerType(fory, Dog);
  }

  static Fory getFory() { ... }

  static void _registerType(Fory fory, Type type) {
    if (type == Person) {
      registerGeneratedStruct(fory, _personForySchema, id: 100, namespace: null, typeName: null);
      return;
    }
    // ... other types
  }
}
```

### Usage

```dart
import 'package:fory/fory.dart';
import 'generated/addressbook/addressbook.dart';

void main() {
  final fory = Fory();
  AddressbookForyModule.install(fory);

  final person = Person()
    ..name = 'Alice'
    ..id = 1;

  final bytes = fory.serialize(person);
  final roundTrip = fory.deserialize<Person>(bytes);
}
```

## Kotlin

The Kotlin target emits Kotlin source only. The compiler does not generate Java
files.

### Output Layout

For source file `addressbook.fdl` with `package addressbook`, Kotlin output is
generated under:

- `<kotlin_out>/addressbook/`
- Type files: `AddressBook.kt`, `Person.kt`, `Dog.kt`, `Cat.kt`, `Animal.kt`
- Schema module: `AddressbookForyModule.kt`

The schema module name is derived from the source file stem. Schemas in the same
Kotlin package need distinct generated file names; duplicate generated Kotlin
file paths are rejected before files are written.

If `option kotlin_package = "...";` is present, the output path and Kotlin
package use that option. Otherwise Kotlin uses the FDL package. A Kotlin import
graph cannot mix default-package schemas with named Kotlin packages.
Registration still uses the FDL package so cross-language type names stay
stable.

### Type Generation

Messages generate Kotlin `data class` declarations by default:

```kotlin
@ForyStruct
public data class Person(
  @field:ForyField(id = 1)
  public val name: String,

  @field:ForyField(id = 7)
  public val phones: List<PersonPhoneNumber>,

  @field:ForyField(id = 8)
  public val pet: Animal,
) {
  public fun toBytes(): ByteArray = AddressbookForyModule.getFory().serialize(this)

  public companion object {
    public fun fromBytes(bytes: ByteArray): Person =
      AddressbookForyModule.getFory().deserialize(bytes, Person::class.java)
  }
}
```

Messages that participate in compiler-detected construction cycles generate
normal mutable classes so the generated serializer can publish the instance
before reading back-references:

```kotlin
@ForyStruct
public class Node() {
  @ForyField(id = 1)
  public var id: String = ""

  @Ref
  @ForyField(id = 2)
  public var parent: Node? = null
}
```

Generated Kotlin IDL sources express nullability with Kotlin `?`, not Fory
`@Nullable`, including mutable classes emitted for compiler-detected
construction cycles.

Enums generate Kotlin enum classes with stable Fory enum IDs. Unions generate
sealed classes with `@ForyUnion`; the runtime-owned `Unknown(UnknownCase)`
carrier is marked with `@ForyUnknownCase`. The marker only selects the carrier
and does not add an entry to the schema case table. Schema-defined cases may use
case IDs `0..N` and hold a single `value` property. A typed union must have at
least one non-`Unknown` case.

```kotlin
package addressbook

import org.apache.fory.annotation.ForyCase
import org.apache.fory.annotation.ForyUnion
import org.apache.fory.annotation.ForyUnknownCase
import org.apache.fory.type.union.UnknownCase

@ForyUnion
public sealed class Animal {
  @ForyUnknownCase
  public data class Unknown(public val value: UnknownCase) : Animal()

  @ForyCase(id = 0)
  public data class Dog(public val value: addressbook.Dog) : Animal()
}
```

Packaged Kotlin output keeps the schema case name and qualifies the payload
type when both have the same simple name. If a target output mode cannot express
a legal qualifier for a conflict, the compiler appends `Case` to the generated
case class name.

Kotlin `int32`, `int64`, `uint32`, and `uint64` fields use xlang varint
encoding by default, so generated Kotlin does not emit `@VarInt` for the
default case. It emits `@Fixed` or `@Tagged` only when the schema requests that
non-default encoding. `duration` maps to `kotlin.time.Duration`, and infinite
durations are rejected when encoded. Dense `array<float16>` and
`array<bfloat16>` use the Java core `Float16Array` and `BFloat16Array`
carriers. Generated Kotlin IDL uses `@ArrayType ByteArray` for `array<int8>`,
including nested positions.

### Schema Module

Generated schema modules register schema types and resolve KSP-generated
serializers from the target class name. The package-owned helper runtime uses
`ForyKotlin.builder().withXlang(true)` with the schema module installed, so message
`toBytes`/`fromBytes` helpers work without caller-managed runtime setup. For
`addressbook.fdl`:

```kotlin
public object AddressbookForyModule : ForyModule {
  private val fory: ThreadSafeFory by lazy {
    ForyKotlin.builder()
      .withXlang(true)
      .withRefTracking(true)
      .withModule(this)
      .buildThreadSafeFory()
  }

  internal fun getFory(): ThreadSafeFory = fory

  override fun install(fory: Fory) {
    KotlinSerializers.registerType(fory, Person::class.java, 100L)
    KotlinSerializers.registerSerializer(fory, Person::class.java)
    KotlinSerializers.registerUnion(fory, Animal::class.java, 106L)
  }
}
```

`registerUnion` discovers the generated `<Target>_ForySerializer`; callers do
not pass a serializer instance.

## Scala

The Scala target emits Scala 3 source only. The `fory-scala` runtime artifact
still supports Scala 2.13 and Scala 3, but generated IDL source and macro
derivation require Scala 3.

### Output Layout

For `package addressbook`, Scala output is generated under:

- `<scala_out>/addressbook/`
- Type files: `AddressBook.scala`, `Person.scala`, `Dog.scala`, `Cat.scala`, `Animal.scala`
- Schema module: `AddressbookForyModule.scala`

For schemas without a Scala package, the schema module name is derived from the
source file stem, for example `main.fdl` generates `MainForyModule.scala`.
Scala import graphs cannot mix default-package schemas with named Scala
packages.

### Type Generation

Messages outside compiler-detected construction cycles generate case classes:

```scala
import org.apache.fory.annotation.{ForyField, ForyStruct}
import org.apache.fory.scala.ForySerializer

@ForyStruct
final case class Person(
  @ForyField(id = 1) name: String,
  @ForyField(id = 3) email: Option[String],
  @ForyField(id = 7) phones: List[Person.PhoneNumber],
  @ForyField(id = 8) pet: Animal
) derives ForySerializer {
  def toBytes(): Array[Byte] =
    AddressbookForyModule.getFory.serialize(this)
}

object Person {
  def fromBytes(bytes: Array[Byte]): Person =
    AddressbookForyModule.getFory.deserialize(bytes).asInstanceOf[Person]
}
```

Messages in circular construction cycles generate normal classes with mutable
serialized fields so reads can register the object before reading back-references:

```scala
import org.apache.fory.annotation.{ForyField, ForyStruct, Ref}
import org.apache.fory.scala.ForySerializer

@ForyStruct
final class Node() derives ForySerializer {
  @ForyField(id = 1)
  var id: String = ""

  @Ref
  @ForyField(id = 2)
  var parent: Option[Node] = None
}
```

Enums generate Scala 3 enums with stable Fory IDs:

```scala
import org.apache.fory.annotation.ForyEnumId

enum PhoneType {
  @ForyEnumId(0)
  case Mobile

  @ForyEnumId(1)
  case Home

  @ForyEnumId(2)
  case Work
}
```

Unions generate Scala 3 ADT enums. `Unknown(UnknownCase)` is the runtime-owned
forward-compatibility carrier marked with `@ForyUnknownCase`. It is omitted
from the schema case table because the marker only selects the carrier and does
not add a schema entry. Schema-defined cases use non-negative `@ForyCase` IDs.
A typed union must have at least one
non-`Unknown` case.

```scala
package addressbook

import org.apache.fory.annotation.{ForyCase, ForyUnion, ForyUnknownCase}
import org.apache.fory.scala.ForySerializer
import org.apache.fory.`type`.union.UnknownCase

@ForyUnion
enum Animal derives ForySerializer {
  @ForyUnknownCase
  case Unknown(value: UnknownCase)

  @ForyCase(id = 0)
  case Dog(value: _root_.addressbook.Dog)

  @ForyCase(id = 1)
  case Cat(value: _root_.addressbook.Cat)
}
```

Packaged Scala output keeps the schema case name and qualifies the payload type
when both have the same simple name. If a target output mode cannot express a
legal qualifier for a conflict, the compiler appends `Case` to the generated
case name.

`optional T` fields generate `Option[T]`. Top-level message references use
`@Ref` on the field or constructor parameter. Nested element/value references
use type-use annotations such as `List[Node @Ref]`.

### Schema Module

Generated schema modules register schema serializers, enums, structs, and
unions. The package-owned helper runtime uses
`ForyScala.builder().withXlang(true)` with the schema module installed, so
message `toBytes`/`fromBytes` helpers work without caller-managed runtime setup:

```scala
object AddressbookForyModule extends org.apache.fory.ForyModule {
  private lazy val fory: ThreadSafeFory =
    ForyScala.builder()
      .withXlang(true)
      .withRefTracking(true)
      .withModule(this)
      .buildThreadSafeFory()

  private[addressbook] def getFory: ThreadSafeFory = fory

  override def install(fory: Fory): Unit = {
    ScalaSerializers.registerEnum(fory, classOf[Person.PhoneType], 101L)
    ForySerializer.register(fory, classOf[Person.PhoneNumber], 102L)
    ForySerializer.register(fory, classOf[Person], 100L)
    ForySerializer.register(fory, classOf[Animal], 106L)
  }
}
```

## Cross-Language Notes

### Type ID Behavior

- Explicit `[id=...]` values are used directly by generated module installation or registration helpers.
- When type IDs are omitted, generated code uses computed numeric IDs (see `auto_id.*` outputs).
- If `option enable_auto_type_id = false;` is set, generated module installation or registration helpers use name-based APIs instead of numeric IDs.

### Nested Type Shape

| Language              | Nested type form               |
| --------------------- | ------------------------------ |
| Java                  | `Person.PhoneNumber`           |
| Python                | `Person.PhoneNumber`           |
| Rust                  | `person::PhoneNumber`          |
| C++                   | `Person::PhoneNumber`          |
| Go                    | `Person_PhoneNumber` (default) |
| C#                    | `Person.PhoneNumber`           |
| JavaScript/TypeScript | `Person.PhoneNumber`           |
| Swift                 | `Person.PhoneNumber`           |
| Dart                  | `Person_PhoneNumber`           |
| Kotlin                | `PersonPhoneNumber`            |
| Scala                 | `Person.PhoneNumber`           |

### Byte Helper Naming

| Language              | Helpers                   |
| --------------------- | ------------------------- |
| Java                  | `toBytes` / `fromBytes`   |
| Kotlin                | `toBytes` / `fromBytes`   |
| Scala                 | `toBytes` / `fromBytes`   |
| Python                | `to_bytes` / `from_bytes` |
| Rust                  | `to_bytes` / `from_bytes` |
| C++                   | `to_bytes` / `from_bytes` |
| Go                    | `ToBytes` / `FromBytes`   |
| C#                    | `ToBytes` / `FromBytes`   |
| JavaScript/TypeScript | (via `fory.serialize()`)  |
| Swift                 | `toBytes` / `fromBytes`   |
| Dart                  | (via `fory.serialize()`)  |
