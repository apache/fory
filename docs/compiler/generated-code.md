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

All snippets are representative excerpts from real generated output.

## Example Fory IDL Schema

The sections below use this schema:

```protobuf
package demo;

enum DeviceTier [id=100] {
    DEVICE_TIER_UNKNOWN = 0;
    DEVICE_TIER_TIER1 = 1;
    DEVICE_TIER_TIER2 = 2;
}

message User [id=101] {
    string id = 1;
    string name = 2;
    optional string email = 3;
}

message SearchResponse [id=102] {
    message Result [id=103] {
        string url = 1;
        string title = 2;
    }
    list<Result> results = 1;
}

message Dog [id=104] {
    string name = 1;
}

message Cat [id=105] {
    string name = 1;
}

union Animal [id=106] {
    Dog dog = 1;
    Cat cat = 2;
}

message Order [id=107] {
    string id = 1;
    ref User customer = 2;
    list<string> items = 3;
    map<string, int32> quantities = 4;
    DeviceTier tier = 5;
    Animal pet = 6;
}
```

## Java

### Output Layout

For package `demo`, Java code is generated under `demo/`:

- `DeviceTier.java`, `User.java`, `SearchResponse.java`, `Dog.java`, `Cat.java`, `Animal.java`, `Order.java`
- `DemoForyRegistration.java`

### Type Generation

Enum prefix stripping keeps scoped enum values clean:

```java
public enum DeviceTier {
    UNKNOWN,
    TIER1,
    TIER2;
}
```

Messages are regular Java classes with `@ForyField` metadata and Java-style getters/setters:

```java
public class Order {
    @ForyField(id = 1)
    private String id;

    @ForyField(id = 2, nullable = true, ref = true)
    private User customer;

    @ForyField(id = 3)
    private List<String> items;

    @ForyField(id = 4)
    private Map<String, Integer> quantities;

    @ForyField(id = 5)
    private DeviceTier tier;

    @ForyField(id = 6)
    private Animal pet;

    public String getId() { ... }
    public void setId(String id) { ... }
    public User getCustomer() { ... }
    public void setCustomer(User customer) { ... }

    public byte[] toBytes() { ... }
    public static Order fromBytes(byte[] bytes) { ... }
}
```

Nested messages become static inner classes:

```java
public class SearchResponse {
    public static class Result { ... }
}
```

Unions generate explicit case APIs:

```java
Animal pet = Animal.ofDog(new Dog());
if (pet.hasDog()) {
    Dog dog = pet.getDog();
}
Animal.AnimalCase c = pet.getAnimalCase();
int caseId = pet.getAnimalCaseId();
```

### Registration

Generated registration helper:

```java
public class DemoForyRegistration {
    public static void register(Fory fory) {
        org.apache.fory.resolver.TypeResolver resolver = fory.getTypeResolver();
        resolver.register(DeviceTier.class, 100L);
        resolver.registerUnion(
            Animal.class,
            106L,
            new org.apache.fory.serializer.UnionSerializer(fory, Animal.class));
        resolver.register(User.class, 101L);
        resolver.register(SearchResponse.class, 102L);
        resolver.register(SearchResponse.Result.class, 103L);
        resolver.register(Dog.class, 104L);
        resolver.register(Cat.class, 105L);
        resolver.register(Order.class, 107L);
    }
}
```

If you disable auto IDs (`option enable_auto_type_id = false;`), registration switches to namespace + type name:

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
Order order = new Order();
order.setId("o456");
order.setCustomer(new User());
order.setTier(DeviceTier.TIER1);
order.setPet(Animal.ofDog(new Dog()));

byte[] bytes = order.toBytes();
Order restored = Order.fromBytes(bytes);
```

## Python

### Output Layout

One module is generated per package, for example `demo.py`.

### Type Generation

Enums are `IntEnum` values with prefix stripping:

```python
class DeviceTier(IntEnum):
    UNKNOWN = 0
    TIER1 = 1
    TIER2 = 2
```

Messages are `@pyfory.dataclass` classes:

```python
@pyfory.dataclass(repr=False)
class Order:
    id: str = pyfory.field(id=1, default="")
    customer: Optional[User] = pyfory.field(id=2, nullable=True, ref=True, default=None)
    items: List[str] = pyfory.field(id=3, default_factory=list)
    quantities: Dict[str, pyfory.int32] = pyfory.field(id=4, default_factory=dict)
    tier: DeviceTier = pyfory.field(id=5, default=None)
    pet: Animal = pyfory.field(id=6, default=None)

    def to_bytes(self) -> bytes: ...
    @classmethod
    def from_bytes(cls, data: bytes) -> "Order": ...
```

Nested messages stay nested:

```python
@pyfory.dataclass
class SearchResponse:
    @pyfory.dataclass
    class Result:
        url: str = pyfory.field(id=1, default="")
        title: str = pyfory.field(id=2, default="")
```

Unions generate case enum + typed accessors:

```python
pet = Animal.dog(Dog(name="Rex"))
if pet.is_dog():
    dog = pet.dog_value()
case_id = pet.case_id()
```

### Registration

Generated registration function:

```python
def register_demo_types(fory: pyfory.Fory):
    fory.register_type(DeviceTier, type_id=100)
    fory.register_union(Animal, type_id=106, serializer=AnimalSerializer(fory))
    fory.register_type(User, type_id=101)
    fory.register_type(SearchResponse, type_id=102)
    fory.register_type(SearchResponse.Result, type_id=103)
    fory.register_type(Dog, type_id=104)
    fory.register_type(Cat, type_id=105)
    fory.register_type(Order, type_id=107)
```

If auto IDs are disabled:

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
order = Order(
    id="o456",
    customer=User(id="u1", name="Alice"),
    items=["a", "b"],
    quantities={"a": 1, "b": 2},
    tier=DeviceTier.TIER1,
    pet=Animal.dog(Dog(name="Rex")),
)

data = order.to_bytes()
restored = Order.from_bytes(data)
```

## Rust

### Output Layout

One Rust module file per package, for example `demo.rs`.

### Type Generation

Enums are strongly typed and use stripped, idiomatic variant names:

```rust
#[derive(ForyObject, Debug, Clone, PartialEq, Default)]
#[repr(i32)]
pub enum DeviceTier {
    #[default]
    Unknown = 0,
    Tier1 = 1,
    Tier2 = 2,
}
```

Messages derive `ForyObject`:

```rust
#[derive(ForyObject, Clone, PartialEq, Default)]
pub struct Order {
    #[fory(id = 1)]
    pub id: String,
    #[fory(id = 2, nullable = true, ref = true)]
    pub customer: Option<Arc<User>>,
    #[fory(id = 3)]
    pub items: Vec<String>,
    #[fory(id = 4)]
    pub quantities: HashMap<String, i32>,
    #[fory(id = 5)]
    pub tier: DeviceTier,
    #[fory(id = 6, type_id = "union")]
    pub pet: Animal,
}
```

Nested types are generated in nested modules:

```rust
pub mod search_response {
    #[derive(ForyObject, Debug, Clone, PartialEq, Default)]
    pub struct Result { ... }
}
```

Unions map to Rust enums with per-case IDs:

```rust
#[derive(ForyObject, Debug, Clone, PartialEq)]
pub enum Animal {
    #[fory(id = 1)]
    Dog(Dog),
    #[fory(id = 2)]
    Cat(Cat),
}
```

### Registration

Generated registration function:

```rust
pub fn register_types(fory: &mut Fory) -> Result<(), fory::Error> {
    fory.register::<DeviceTier>(100)?;
    fory.register_union::<Animal>(106)?;
    fory.register::<User>(101)?;
    fory.register::<search_response::Result>(103)?;
    fory.register::<SearchResponse>(102)?;
    fory.register::<Dog>(104)?;
    fory.register::<Cat>(105)?;
    fory.register::<Order>(107)?;
    Ok(())
}
```

If auto IDs are disabled:

```rust
fory.register_by_namespace::<Config>("myapp.models", "Config")?;
fory.register_union_by_namespace::<Holder>("myapp.models", "Holder")?;
```

### Usage

```rust
let order = Order {
    id: "o456".into(),
    customer: Some(Arc::new(User::default())),
    items: vec!["a".into(), "b".into()],
    quantities: HashMap::new(),
    tier: DeviceTier::Tier1,
    pet: Animal::Dog(Dog { name: "Rex".into() }),
};

let bytes = order.to_bytes()?;
let restored = Order::from_bytes(&bytes)?;
```

## C++

### Output Layout

One header per package, for example `demo.h`.

### Type Generation

Enums are generated as `enum class` with stripped names:

```cpp
enum class DeviceTier : int32_t {
  UNKNOWN = 0,
  TIER1 = 1,
  TIER2 = 2,
};
FORY_ENUM(demo::DeviceTier, UNKNOWN, TIER1, TIER2);
```

Messages are generated as classes with typed accessors and private fields, including `has_xxx`, `mutable_xxx`, and `set_xxx` where applicable:

```cpp
class Order final {
 public:
  const std::string& id() const;
  std::string* mutable_id();
  template <class Arg, class... Args>
  void set_id(Arg&& arg, Args&&... args);

  bool has_customer() const;
  const std::shared_ptr<User>& customer() const;
  std::shared_ptr<User>* mutable_customer();
  void set_customer(std::shared_ptr<User> value);
  void clear_customer();

  fory::Result<std::vector<uint8_t>, fory::Error> to_bytes() const;
  static fory::Result<Order, fory::Error> from_bytes(
      const std::vector<uint8_t>& data);

 private:
  std::string id_;
  std::shared_ptr<User> customer_;
  std::vector<std::string> items_;
  std::map<std::string, int32_t> quantities_;
  DeviceTier tier_;
  Animal pet_;

 public:
  FORY_STRUCT(Order, id_, customer_, items_, quantities_, tier_, pet_);
};
```

Nested messages are nested classes:

```cpp
class SearchResponse final {
 public:
  class Result final { ... };
};
```

Unions are generated as tagged `std::variant` wrappers:

```cpp
Animal pet = Animal::dog(Dog{});
if (pet.is_dog()) {
  const Dog& dog = pet.dog();
}
uint32_t case_id = pet.animal_case_id();
```

### Registration

Generated registration function:

```cpp
inline void register_types(fory::serialization::BaseFory& fory) {
  fory.register_enum<DeviceTier>(100);
  fory.register_union<Animal>(106);
  fory.register_struct<User>(101);
  fory.register_struct<SearchResponse::Result>(103);
  fory.register_struct<SearchResponse>(102);
  fory.register_struct<Dog>(104);
  fory.register_struct<Cat>(105);
  fory.register_struct<Order>(107);
}
```

If auto IDs are disabled:

```cpp
fory.register_struct<Config>("myapp.models", "Config");
fory.register_union<Holder>("myapp.models", "Holder");
```

### Usage

```cpp
demo::Order order;
order.set_id("o456");
order.set_customer(std::make_shared<demo::User>());

auto bytes_result = order.to_bytes();
if (!bytes_result.ok()) {
  return 1;
}
auto order_result = demo::Order::from_bytes(bytes_result.value());
if (!order_result.ok()) {
  return 1;
}
demo::Order restored = std::move(order_result.value());
```

## Go

### Output Layout

Go output path depends on whether `go_package` is configured.

When `go_package` is set in schema options (as in `integration_tests/idl_tests/idl/addressbook.fdl`), output follows that package path, for example:

- `integration_tests/idl_tests/go/addressbook/generated/addressbook.go`

Without `go_package`, compiler derives output from the Fory IDL package name.

For package `demo`, output is:

- `<go_out>/demo/demo.go`

For package `myapp.models`, output is:

- `<go_out>/models/myapp_models.go`

### Type Generation

Enums keep Go-style unscoped constant names:

```go
type DeviceTier int32

const (
    DeviceTierUnknown DeviceTier = 0
    DeviceTierTier1   DeviceTier = 1
    DeviceTierTier2   DeviceTier = 2
)
```

Messages are regular structs with fory tags:

```go
type User struct {
    Id    string                    `fory:"id=1"`
    Name  string                    `fory:"id=2"`
    Email optional.Optional[string] `fory:"id=3,nullable"`
}

type Order struct {
    Id         string            `fory:"id=1"`
    Customer   *User             `fory:"id=2,nullable,ref"`
    Items      []string          `fory:"id=3"`
    Quantities map[string]int32  `fory:"id=4"`
    Tier       DeviceTier        `fory:"id=5"`
    Pet        Animal            `fory:"id=6"`
}
```

Nested type naming defaults to underscore:

```go
type SearchResponse_Result struct { ... }
```

You can switch to concatenated names with:

```protobuf
option go_nested_type_style = "camelcase";
```

Unions generate typed case helpers:

```go
pet := DogAnimal(&Dog{Name: "Rex"})
if dog, ok := pet.AsDog(); ok {
    _ = dog
}
_ = pet.Visit(AnimalVisitor{
    Dog: func(d *Dog) error { return nil },
})
```

### Registration

Generated registration function:

```go
func RegisterTypes(f *fory.Fory) error {
    if err := f.RegisterEnum(DeviceTier(0), 100); err != nil {
        return err
    }
    if err := f.RegisterUnion(Animal{}, 106, ...); err != nil {
        return err
    }
    if err := f.RegisterStruct(User{}, 101); err != nil {
        return err
    }
    // ... SearchResponse_Result, SearchResponse, Dog, Cat, Order
    return nil
}
```

If auto IDs are disabled:

```go
if err := f.RegisterNamedStruct(Config{}, "myapp.models.Config"); err != nil { ... }
if err := f.RegisterNamedUnion(Holder{}, "myapp.models.Holder", ...); err != nil { ... }
```

### Usage

```go
email := optional.Some("alice@example.com")
order := &Order{
    Id:       "o456",
    Customer: &User{Id: "u1", Name: "Alice", Email: email},
    Items:    []string{"a", "b"},
    Tier:     DeviceTierTier1,
    Pet:      DogAnimal(&Dog{Name: "Rex"}),
}

data, err := order.ToBytes()
if err != nil {
    panic(err)
}
var restored Order
if err := restored.FromBytes(data); err != nil {
    panic(err)
}
```

## Cross-Language Notes

### Type ID Behavior

- Explicit `[id=...]` is used directly.
- Without explicit IDs, compiler-generated IDs are used by default.
- With `option enable_auto_type_id = false;`, generated code registers by namespace + type name.

### Nested Type Shapes

| Language | Nested Type Form        |
| -------- | ----------------------- |
| Java     | `Outer.Inner`           |
| Python   | `Outer.Inner`           |
| Rust     | `outer::Inner`          |
| C++      | `Outer::Inner`          |
| Go       | `Outer_Inner` (default) |
