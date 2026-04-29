---
title: Shared & Circular References
sidebar_position: 5
id: references
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

Apache Fory™ automatically tracks and preserves reference identity for shared objects using `Rc<T>` and `Arc<T>`.

## Shared References

When the same object is referenced multiple times, Fory serializes it only once and uses reference IDs for subsequent occurrences. This ensures:

- **Space efficiency**: No data duplication in serialized output
- **Reference identity preservation**: Deserialized objects maintain the same sharing relationships
- **Circular reference support**: Use `RcWeak<T>` and `ArcWeak<T>` to break cycles

### Shared References with Rc

```rust
use fory::Fory;
use std::rc::Rc;

let fory = Fory::default();

// Create a shared value
let shared = Rc::new(String::from("shared_value"));

// Reference it multiple times
let data = vec![shared.clone(), shared.clone(), shared.clone()];

// The shared value is serialized only once
let bytes = fory.serialize(&data);
let decoded: Vec<Rc<String>> = fory.deserialize(&bytes)?;

// Verify reference identity is preserved
assert_eq!(decoded.len(), 3);
assert_eq!(*decoded[0], "shared_value");

// All three Rc pointers point to the same object
assert!(Rc::ptr_eq(&decoded[0], &decoded[1]));
assert!(Rc::ptr_eq(&decoded[1], &decoded[2]));
```

### Shared References with Arc

For thread-safe shared references, use `Arc<T>`:

```rust
use fory::Fory;
use std::sync::Arc;

let fory = Fory::default();

let shared = Arc::new(String::from("shared_value"));
let data = vec![shared.clone(), shared.clone()];

let bytes = fory.serialize(&data);
let decoded: Vec<Arc<String>> = fory.deserialize(&bytes)?;

assert!(Arc::ptr_eq(&decoded[0], &decoded[1]));
```

## Circular References with Weak Pointers

To serialize circular references like parent-child relationships or doubly-linked structures, use `RcWeak<T>` or `ArcWeak<T>` to break the cycle.

**How it works:**

- Weak pointers serialize as references to their target objects
- If the strong pointer has been dropped, weak serializes as `Null`
- Forward references (weak appearing before target) are resolved via callbacks
- All clones of a weak pointer share the same internal cell for automatic updates

### Circular References with RcWeak

```rust
use fory::{Fory, Error};
use fory::ForyStruct;
use fory::RcWeak;
use std::rc::Rc;
use std::cell::RefCell;

#[derive(ForyStruct, Debug)]
struct Node {
    value: i32,
    parent: RcWeak<RefCell<Node>>,
    children: Vec<Rc<RefCell<Node>>>,
}

let mut fory = Fory::default();
fory.register::<Node>(2000);

// Build a parent-child tree
let parent = Rc::new(RefCell::new(Node {
    value: 1,
    parent: RcWeak::new(),
    children: vec![],
}));

let child1 = Rc::new(RefCell::new(Node {
    value: 2,
    parent: RcWeak::from(&parent),
    children: vec![],
}));

let child2 = Rc::new(RefCell::new(Node {
    value: 3,
    parent: RcWeak::from(&parent),
    children: vec![],
}));

parent.borrow_mut().children.push(child1.clone());
parent.borrow_mut().children.push(child2.clone());

// Serialize and deserialize the circular structure
let bytes = fory.serialize(&parent);
let decoded: Rc<RefCell<Node>> = fory.deserialize(&bytes)?;

// Verify the circular relationship
assert_eq!(decoded.borrow().children.len(), 2);
for child in &decoded.borrow().children {
    let upgraded_parent = child.borrow().parent.upgrade().unwrap();
    assert!(Rc::ptr_eq(&decoded, &upgraded_parent));
}
```

### Thread-Safe Circular Graphs with Arc

```rust
use fory::{Fory, Error};
use fory::ForyStruct;
use fory::ArcWeak;
use std::sync::{Arc, Mutex};

#[derive(ForyStruct)]
struct Node {
    val: i32,
    parent: ArcWeak<Mutex<Node>>,
    children: Vec<Arc<Mutex<Node>>>,
}

let mut fory = Fory::default();
fory.register::<Node>(6000);

let parent = Arc::new(Mutex::new(Node {
    val: 10,
    parent: ArcWeak::new(),
    children: vec![],
}));

let child1 = Arc::new(Mutex::new(Node {
    val: 20,
    parent: ArcWeak::from(&parent),
    children: vec![],
}));

let child2 = Arc::new(Mutex::new(Node {
    val: 30,
    parent: ArcWeak::from(&parent),
    children: vec![],
}));

parent.lock().unwrap().children.push(child1.clone());
parent.lock().unwrap().children.push(child2.clone());

let bytes = fory.serialize(&parent);
let decoded: Arc<Mutex<Node>> = fory.deserialize(&bytes)?;

assert_eq!(decoded.lock().unwrap().children.len(), 2);
for child in &decoded.lock().unwrap().children {
    let upgraded_parent = child.lock().unwrap().parent.upgrade().unwrap();
    assert!(Arc::ptr_eq(&decoded, &upgraded_parent));
}
```

## Supported Smart Pointer Types

| Type         | Description                                         |
| ------------ | --------------------------------------------------- |
| `Rc<T>`      | Reference counting, shared refs tracked             |
| `Arc<T>`     | Thread-safe reference counting, shared refs tracked |
| `RcWeak<T>`  | Weak reference to `Rc<T>`, breaks circular refs     |
| `ArcWeak<T>` | Weak reference to `Arc<T>`, breaks circular refs    |
| `RefCell<T>` | Interior mutability with runtime borrow checking    |
| `Mutex<T>`   | Thread-safe interior mutability                     |

## Best Practices

1. **Use Rc/Arc for shared data**: Let Fory handle deduplication
2. **Use weak pointers for cycles**: Prevent infinite recursion
3. **Prefer Arc for thread-safe scenarios**: When data crosses thread boundaries
4. **Combine with RefCell/Mutex**: For interior mutability

## Related Topics

- [Basic Serialization](basic-serialization.md) - Supported types
- [Polymorphism](polymorphism.md) - Trait objects with Rc/Arc
- [Configuration](configuration.md) - Reference tracking options
