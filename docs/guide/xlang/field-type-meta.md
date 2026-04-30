---
title: Field Type Meta
sidebar_position: 46
id: field_type_meta
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

Field type meta configuration controls whether type information is written during serialization for struct fields. This is essential for supporting polymorphism where the actual runtime type may differ from the declared field type.

## Overview

When serializing a struct field, Fory needs to determine whether to write type metadata:

- **Static typing**: Use the declared field type's serializer directly (no type info written)
- **Dynamic typing**: Write type information to support runtime subtypes

## When Type Meta Is Needed

Type metadata is required when:

1. **Interface/abstract fields**: The declared type is abstract, so concrete type must be recorded
2. **Polymorphic fields**: The runtime type may be a subclass of the declared type
3. **Cross-language compatibility**: When the receiver needs type information to deserialize correctly

Type metadata is NOT needed when:

1. **Final/concrete types**: The declared type is final/sealed and cannot be subclassed
2. **Primitive types**: Type is known at compile time
3. **Performance optimization**: When you know the runtime type always matches the declared type

## Language-Specific Configuration

### Java

Java requires explicit configuration because concrete classes can be subclassed unless marked `final`.

Use the `@ForyField` annotation with the `dynamic` parameter:

```java
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyField.Dynamic;

public class Container {
    // AUTO (default): Interface types write type info, concrete types don't
    @ForyField(id = 0)
    private Shape shape;  // Interface - type info written

    // FALSE: Never write type info (use declared type's serializer)
    @ForyField(id = 1, dynamic = Dynamic.FALSE)
    private Circle circle;  // Always treated as Circle

    // TRUE: Always write type info (support runtime subtypes)
    @ForyField(id = 2, dynamic = Dynamic.TRUE)
    private Shape concreteShape;  // Type info written even if concrete
}
```

**Dynamic Options**:

| Value   | Behavior                                               |
| ------- | ------------------------------------------------------ |
| `AUTO`  | Interface/abstract types are dynamic, concrete are not |
| `FALSE` | Never write type info, use declared type's serializer  |
| `TRUE`  | Always write type info to support runtime subtypes     |

**Use Cases**:

- `AUTO`: Default behavior, suitable for most cases
- `FALSE`: Performance optimization when you know the exact type
- `TRUE`: When a concrete field may hold subclass instances

### C++

C++ uses the `.dynamic(bool)` builder method inside `FORY_STRUCT`:

```cpp
#include "fory/serialization/fory.h"

// Abstract base class with pure virtual methods
struct Animal {
    virtual ~Animal() = default;
    virtual std::string speak() const = 0;
};

struct Zoo {
    // Auto: type info written because Animal is polymorphic (std::is_polymorphic)
    std::shared_ptr<Animal> animal;

    // Force non-dynamic: skip type info even though Animal is polymorphic
    std::shared_ptr<Animal> fixed_animal;

    // Force dynamic: write type info even for non-polymorphic types
    std::shared_ptr<Data> polymorphic_data;
};
FORY_STRUCT(Zoo,
    (animal, fory::F(0).nullable()),                    // Auto-detect polymorphism
    (fixed_animal, fory::F(1).nullable().dynamic(false)), // Skip type info
    (polymorphic_data, fory::F(2).dynamic(true))        // Force type info
);
```

**Default Behavior**: Fory auto-detects polymorphism via `std::is_polymorphic<T>`. Types with pure virtual methods are treated as dynamic by default.

### Go and Rust

Go and Rust do **not** require explicit dynamic configuration because:

- **Go**: Interface types are inherently dynamic - Fory can determine from the type whether it's an interface
- **Rust**: Trait objects (`dyn Trait`) are explicitly marked in the type system

The type system in these languages already indicates whether a field is polymorphic:

```go
// Go: interface types are automatically dynamic
type Container struct {
    Shape  Shape       // Interface - type info written automatically
    Circle Circle      // Concrete struct - no type info needed
}
```

```rust
// Rust: trait objects are explicitly marked
struct Container {
    shape: Box<dyn Shape>,  // Trait object - type info written automatically
    circle: Circle,         // Concrete type - no type info needed
}
```

### Python

Use `pyfory.field()` with the `dynamic` parameter:

```python
from dataclasses import dataclass
from abc import ABC, abstractmethod
import pyfory

class Shape(ABC):
    @abstractmethod
    def area(self) -> float:
        pass

@dataclass
class Circle(Shape):
    radius: float = 0.0

    def area(self) -> float:
        return 3.14159 * self.radius * self.radius

@dataclass
class Container:
    # Abstract class: dynamic is always True (type info written)
    shape: Shape = pyfory.field(id=0)

    # Concrete type with explicit dynamic=True (force type info)
    circle: Circle = pyfory.field(id=1, dynamic=True)

    # Concrete type with explicit dynamic=False (skip type info)
    fixed_circle: Circle = pyfory.field(id=2, dynamic=False)
```

**Default Behavior**:

| Mode        | Abstract Class | Concrete Object Types | Numeric/str/time Types |
| ----------- | -------------- | --------------------- | ---------------------- |
| Native mode | `True`         | `True`                | `False`                |
| Xlang mode  | `True`         | `False`               | `False`                |

- **Abstract classes**: `dynamic` is always `True` (type info must be written)
- **Native mode**: `dynamic` defaults to `True` for object types, `False` for numeric/str/time types
- **Xlang mode**: `dynamic` defaults to `False` for concrete types

## Default Behavior

| Language | Interface/Abstract Types | Concrete Types   |
| -------- | ------------------------ | ---------------- |
| Java     | Dynamic (write type)     | Static (no type) |
| C++      | Dynamic (virtual)        | Static           |
| Go       | Dynamic (interface)      | Static (struct)  |
| Rust     | Dynamic (dyn Trait)      | Static           |
| Python   | Dynamic (all objects)    | Dynamic          |

## Performance Considerations

Writing type metadata has overhead:

- **Space**: Type information adds bytes to serialized output
- **Time**: Type resolution during serialization/deserialization

Use `dynamic = FALSE` (Java) or `dynamic(false)` (C++) when:

- You're certain the runtime type matches the declared type
- Performance is critical and polymorphism is not needed
- The field type is effectively final

## Cross-Language Compatibility

When serializing data for cross-language consumption:

1. **Use consistent type registration**: Register types with the same ID across languages
2. **Prefer explicit configuration**: Use `dynamic = TRUE` when unsure about receiver's expectations
3. **Document polymorphic fields**: Make it clear which fields may contain subtypes

## Example: Polymorphic Container

### Java

```java
public interface Animal {
    String speak();
}

public class Dog implements Animal {
    private String name;

    @Override
    public String speak() { return "Woof!"; }
}

public class Cat implements Animal {
    private String name;

    @Override
    public String speak() { return "Meow!"; }
}

public class Zoo {
    // Type info written because Animal is an interface
    @ForyField(id = 0)
    private Animal animal;

    // Force type info for concrete type that may hold subtypes
    @ForyField(id = 1, dynamic = Dynamic.TRUE)
    private Dog maybeMixedBreed;
}
```

### C++

```cpp
// Abstract base class with pure virtual methods
class Animal {
public:
    virtual std::string speak() const = 0;
    virtual ~Animal() = default;
};

class Dog : public Animal {
public:
    std::string name;
    std::string speak() const override { return "Woof!"; }
};

struct Zoo {
    std::shared_ptr<Animal> animal;
    std::shared_ptr<Dog> maybe_mixed_breed;
};

FORY_STRUCT(Zoo,
    (animal, fory::F(0).nullable()),              // Auto-detect (Animal is polymorphic)
    (maybe_mixed_breed, fory::F(1).dynamic(true)) // Force dynamic for concrete type
);
```

## Related Topics

- [Field Nullability](field-nullability.md) - Controlling null handling for fields
- [Field Reference Tracking](field-reference-tracking.md) - Managing shared/circular references
- [Type Mapping](../../specification/xlang_type_mapping.md) - Cross-language type compatibility
