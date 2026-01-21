---
title: Polymorphic Serialization
sidebar_position: 5
id: polymorphism
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

Apache Fory™ supports polymorphic serialization through smart pointers (`std::shared_ptr` and `std::unique_ptr`), enabling dynamic dispatch and type flexibility for inheritance hierarchies.

## Supported Polymorphic Types

- `std::shared_ptr<Base>` - Shared ownership with polymorphic dispatch
- `std::unique_ptr<Base>` - Exclusive ownership with polymorphic dispatch
- Collections: `std::vector<std::shared_ptr<Base>>`, `std::map<K, std::unique_ptr<Base>>`
- Optional: `std::optional<std::shared_ptr<Base>>`

## Basic Polymorphic Serialization

```cpp
#include "fory/serialization/fory.h"

using namespace fory::serialization;

// Define base class with virtual methods
struct Animal {
  virtual ~Animal() = default;
  virtual std::string speak() const = 0;
  int32_t age = 0;
};
FORY_STRUCT(Animal, age);

// Define derived classes
struct Dog : Animal {
  std::string speak() const override { return "Woof!"; }
  std::string breed;
};
FORY_STRUCT(Dog, age, breed);

struct Cat : Animal {
  std::string speak() const override { return "Meow!"; }
  std::string color;
};
FORY_STRUCT(Cat, age, color);

// Struct with polymorphic field
struct Zoo {
  std::shared_ptr<Animal> star_animal;
};
FORY_STRUCT(Zoo, star_animal);

int main() {
  auto fory = Fory::builder().track_ref(true).build();

  // Register all types with unique type IDs
  fory.register_struct<Zoo>(100);
  fory.register_struct<Dog>(101);
  fory.register_struct<Cat>(102);

  // Create object with polymorphic field
  Zoo zoo;
  zoo.star_animal = std::make_shared<Dog>();
  zoo.star_animal->age = 3;
  static_cast<Dog*>(zoo.star_animal.get())->breed = "Labrador";

  // Serialize
  auto bytes_result = fory.serialize(zoo);
  assert(bytes_result.ok());

  // Deserialize - runtime type is preserved
  auto decoded_result = fory.deserialize<Zoo>(bytes_result.value());
  assert(decoded_result.ok());

  auto decoded = std::move(decoded_result).value();
  assert(decoded.star_animal->speak() == "Woof!");
  assert(decoded.star_animal->age == 3);

  auto* dog_ptr = dynamic_cast<Dog*>(decoded.star_animal.get());
  assert(dog_ptr != nullptr);
  assert(dog_ptr->breed == "Labrador");
}
```

## Type Registration for Polymorphism

For polymorphic serialization, register derived types with unique type IDs:

```cpp
// Register with numeric type ID
fory.register_struct<Derived1>(100);
fory.register_struct<Derived2>(101);
```

**Why type ID registration?**

- Compact binary representation
- Fast type lookup and dispatch
- Consistent with non-polymorphic type registration

## Automatic Polymorphism Detection

Fory automatically detects polymorphic types using `std::is_polymorphic<T>`:

```cpp
struct Base {
  virtual ~Base() = default;  // Virtual destructor makes it polymorphic
  int32_t value = 0;
};

struct NonPolymorphic {
  int32_t value = 0;  // No virtual methods
};

// Polymorphic field - type info written automatically
struct Container1 {
  std::shared_ptr<Base> ptr;  // Auto-detected as polymorphic
};

// Non-polymorphic field - no type info written
struct Container2 {
  std::shared_ptr<NonPolymorphic> ptr;  // Not polymorphic
};
```

## Controlling Dynamic Dispatch

Use `fory::dynamic<V>` to override automatic polymorphism detection:

```cpp
struct Animal {
  virtual ~Animal() = default;
  virtual std::string speak() const = 0;
};

struct Pet {
  // Auto-detected: type info written (Animal has virtual methods)
  std::shared_ptr<Animal> animal1;

  // Force dynamic: type info written explicitly
  fory::field<std::shared_ptr<Animal>, 0, fory::dynamic<true>> animal2;

  // Force non-dynamic: skip type info (faster but no runtime subtyping)
  fory::field<std::shared_ptr<Animal>, 1, fory::dynamic<false>> animal3;
};
FORY_STRUCT(Pet, animal1, animal2, animal3);
```

**When to use `fory::dynamic<false>`:**

- You know the runtime type will always match the declared type
- Performance is critical and you don't need subtype support
- Working with monomorphic data despite having a polymorphic base class

### Field Configuration Without Wrapper Types

Use `FORY_FIELD_CONFIG` to configure fields without `fory::field<>` wrapper:

```cpp
struct Zoo {
  std::shared_ptr<Animal> star;      // Auto-detected as polymorphic
  std::shared_ptr<Animal> backup;    // Nullable polymorphic field
  std::shared_ptr<Animal> mascot;    // Non-dynamic (no subtype dispatch)
};
FORY_STRUCT(Zoo, star, backup, mascot);

// Configure fields with tag IDs and options
FORY_FIELD_CONFIG(Zoo,
    (star, fory::F(0)),                    // Tag ID 0, default options
    (backup, fory::F(1).nullable()),       // Tag ID 1, allow nullptr
    (mascot, fory::F(2).dynamic(false))    // Tag ID 2, disable polymorphism
);
```

See [Field Configuration](field-configuration.md) for complete details on `fory::nullable`, `fory::ref`, and other field-level options

## std::unique_ptr Polymorphism

`std::unique_ptr` works the same way as `std::shared_ptr` for polymorphic types:

```cpp
struct Container {
  std::unique_ptr<Animal> pet;
};
FORY_STRUCT(Container, pet);

auto fory = Fory::builder().track_ref(true).build();
fory.register_struct<Container>(200);
fory.register_struct<Dog>(201);

Container container;
container.pet = std::make_unique<Dog>();
static_cast<Dog*>(container.pet.get())->breed = "Beagle";

auto bytes = fory.serialize(container).value();
auto decoded = fory.deserialize<Container>(bytes).value();

// Runtime type preserved
auto* dog = dynamic_cast<Dog*>(decoded.pet.get());
assert(dog != nullptr);
assert(dog->breed == "Beagle");
```

## Collections of Polymorphic Objects

```cpp
#include <vector>
#include <map>

struct AnimalShelter {
  std::vector<std::shared_ptr<Animal>> animals;
  std::map<std::string, std::unique_ptr<Animal>> registry;
};
FORY_STRUCT(AnimalShelter, animals, registry);

auto fory = Fory::builder().track_ref(true).build();
fory.register_struct<AnimalShelter>(100);
fory.register_struct<Dog>(101);
fory.register_struct<Cat>(102);

AnimalShelter shelter;
shelter.animals.push_back(std::make_shared<Dog>());
shelter.animals.push_back(std::make_shared<Cat>());
shelter.registry["pet1"] = std::make_unique<Dog>();

auto bytes = fory.serialize(shelter).value();
auto decoded = fory.deserialize<AnimalShelter>(bytes).value();

// All runtime types preserved
assert(dynamic_cast<Dog*>(decoded.animals[0].get()) != nullptr);
assert(dynamic_cast<Cat*>(decoded.animals[1].get()) != nullptr);
assert(dynamic_cast<Dog*>(decoded.registry["pet1"].get()) != nullptr);
```

## Reference Tracking

Reference tracking for `std::shared_ptr` works the same with polymorphic types.
See [Supported Types](supported-types.md) for details and examples.

## Nested Polymorphism Depth Limit

To prevent stack overflow from deeply nested polymorphic structures, Fory limits the maximum dynamic nesting depth:

```cpp
struct Container {
  virtual ~Container() = default;
  int32_t value = 0;
  std::shared_ptr<Container> nested;
};
FORY_STRUCT(Container, value, nested);

// Default max_dyn_depth is 5
auto fory1 = Fory::builder().build();
assert(fory1.config().max_dyn_depth == 5);

// Increase limit for deeper nesting
auto fory2 = Fory::builder().max_dyn_depth(10).build();
fory2.register_struct<Container>(1);

// Create deeply nested structure
auto level3 = std::make_shared<Container>();
level3->value = 3;

auto level2 = std::make_shared<Container>();
level2->value = 2;
level2->nested = level3;

auto level1 = std::make_shared<Container>();
level1->value = 1;
level1->nested = level2;

// Serialization succeeds
auto bytes = fory2.serialize(level1).value();

// Deserialization succeeds with sufficient depth
auto decoded = fory2.deserialize<std::shared_ptr<Container>>(bytes).value();
```

**Depth exceeded error:**

```cpp
auto fory_shallow = Fory::builder().max_dyn_depth(2).build();
fory_shallow.register_struct<Container>(1);

// 3 levels exceeds max_dyn_depth=2
auto result = fory_shallow.deserialize<std::shared_ptr<Container>>(bytes);
assert(!result.ok());  // Fails with depth exceeded error
```

**When to adjust:**

- **Increase `max_dyn_depth`**: For legitimate deeply nested polymorphic data structures
- **Decrease `max_dyn_depth`**: For stricter security requirements or shallow data structures

## Nullability for Polymorphic Fields

By default, `std::shared_ptr<T>` and `std::unique_ptr<T>` fields are treated as
non-nullable in the schema. To allow `nullptr`, wrap the field with
`fory::field<>` (or `FORY_FIELD_TAGS`) and opt in with `fory::nullable`.

```cpp
struct Pet {
  // Non-nullable (default)
  std::shared_ptr<Animal> primary;

  // Nullable via explicit field metadata
  fory::field<std::shared_ptr<Animal>, 0, fory::nullable> optional;
};
FORY_STRUCT(Pet, primary, optional);
```

See [Field Configuration](field-configuration.md) for more details.

## Combining Polymorphism with Other Features

### Polymorphism + Reference Tracking

```cpp
struct GraphNode {
  virtual ~GraphNode() = default;
  int32_t id = 0;
  std::vector<std::shared_ptr<GraphNode>> neighbors;
};
FORY_STRUCT(GraphNode, id, neighbors);

struct WeightedNode : GraphNode {
  double weight = 0.0;
};
FORY_STRUCT(WeightedNode, id, neighbors, weight);

// Enable ref tracking to handle shared references and cycles
auto fory = Fory::builder().track_ref(true).build();
fory.register_struct<GraphNode>(100);
fory.register_struct<WeightedNode>(101);

// Create cyclic graph
auto node1 = std::make_shared<WeightedNode>();
node1->id = 1;

auto node2 = std::make_shared<WeightedNode>();
node2->id = 2;

node1->neighbors.push_back(node2);
node2->neighbors.push_back(node1);  // Cycle

auto bytes = fory.serialize(node1).value();
auto decoded = fory.deserialize<std::shared_ptr<GraphNode>>(bytes).value();
// Cycle handled correctly
```

### Polymorphism + Schema Evolution

Use compatible mode for schema evolution with polymorphic types:

```cpp
auto fory = Fory::builder()
    .compatible(true)  // Enable schema evolution
    .track_ref(true)
    .build();
```

## Best Practices

1. **Use type ID registration** for polymorphic types:

   ```cpp
   fory.register_struct<DerivedType>(100);
   ```

2. **Enable reference tracking** for polymorphic types:

   ```cpp
   auto fory = Fory::builder().track_ref(true).build();
   ```

3. **Virtual destructors required**: Ensure base classes have virtual destructors:

   ```cpp
   struct Base {
     virtual ~Base() = default;  // Required for polymorphism
   };
   ```

4. **Register all concrete types** before serialization/deserialization:

   ```cpp
   fory.register_struct<Derived1>(100);
   fory.register_struct<Derived2>(101);
   ```

5. **Use `dynamic_cast`** to downcast after deserialization:

   ```cpp
   auto* derived = dynamic_cast<DerivedType*>(base_ptr.get());
   if (derived) {
     // Use derived-specific members
   }
   ```

6. **Adjust `max_dyn_depth`** based on your data structure depth:

   ```cpp
   auto fory = Fory::builder().max_dyn_depth(10).build();
   ```

7. **Use `fory::nullable`** for optional polymorphic fields:

   ```cpp
   fory::field<std::shared_ptr<Base>, 0, fory::nullable> optional_ptr;
   ```

## Error Handling

```cpp
auto bytes_result = fory.serialize(obj);
if (!bytes_result.ok()) {
  std::cerr << "Serialization failed: "
            << bytes_result.error().to_string() << std::endl;
  return;
}

auto decoded_result = fory.deserialize<MyType>(bytes_result.value());
if (!decoded_result.ok()) {
  std::cerr << "Deserialization failed: "
            << decoded_result.error().to_string() << std::endl;
  return;
}
```

**Common errors:**

- **Type not registered**: Register all concrete types with unique IDs before use
- **Depth exceeded**: Increase `max_dyn_depth` for deeply nested structures
- **Type ID conflict**: Ensure each type has a unique type ID across all registered types

## Performance Considerations

**Polymorphic serialization overhead:**

- Type metadata written for each polymorphic object (~16-32 bytes)
- Dynamic type resolution during deserialization
- Virtual function calls for runtime dispatch

**Optimization tips:**

1. **Use `fory::dynamic<false>`** when runtime type matches declared type:

   ```cpp
   fory::field<std::shared_ptr<Base>, 0, fory::dynamic<false>> fixed_type;
   ```

2. **Minimize nesting depth** to reduce metadata overhead

3. **Batch polymorphic objects** in collections rather than individual fields

4. **Consider non-polymorphic alternatives** when polymorphism isn't needed:

   ```cpp
   std::variant<Dog, Cat> animal;  // Type-safe union instead of polymorphism
   ```

## Related Topics

- [Type Registration](type-registration.md) - Registering types for serialization
- [Field Configuration](field-configuration.md) - Field-level metadata and options
- [Supported Types](supported-types.md) - Smart pointers and collections
- [Configuration](configuration.md) - `max_dyn_depth` and other settings
- [Basic Serialization](basic-serialization.md) - Core serialization concepts
