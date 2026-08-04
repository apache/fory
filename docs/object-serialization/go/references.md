---
title: References
sidebar_position: 8
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

Fory Go supports reference tracking to handle circular references and shared objects. This is essential for serializing complex data structures like graphs, trees with parent pointers, and linked lists with cycles.

## Enabling Reference Tracking

Reference tracking is **disabled by default**. Enable it when creating a Fory instance:

```go
f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))
```

**Important**: Global reference tracking must be enabled for any reference tracking to occur. When `WithTrackRef(false)` (the default), all per-field reference tags are ignored.

## How Reference Tracking Works

### Without Reference Tracking (Default)

When disabled, each object is serialized independently:

```go
f := fory.New(fory.WithXlang(true))  // TrackRef disabled by default

shared := &Data{Value: 42}
container := &Container{A: shared, B: shared}

data, _ := f.Serialize(container)
// 'shared' is serialized TWICE (no deduplication)
```

### With Reference Tracking

When enabled, objects are tracked by identity:

```go
f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))

shared := &Data{Value: 42}
container := &Container{A: shared, B: shared}

data, _ := f.Serialize(container)
// 'shared' is serialized ONCE, second occurrence is a reference
```

## Reference Flags

Fory uses flags to indicate reference states during serialization:

| Flag               | Value | Meaning                                   |
| ------------------ | ----- | ----------------------------------------- |
| `NullFlag`         | -3    | Nil/null value                            |
| `RefFlag`          | -2    | Reference to previously serialized object |
| `NotNullValueFlag` | -1    | Non-null value (data follows)             |
| `RefValueFlag`     | 0     | Reference value flag                      |

## Referenceable Types

Only certain types support reference tracking. In xlang mode, the following types can track references:

| Type                          | Reference Tracked | Notes                          |
| ----------------------------- | ----------------- | ------------------------------ |
| `*struct` (pointer to struct) | Yes               | Enable with `fory:"ref"` tag   |
| `any` (interface)             | Yes               | Automatically tracked          |
| `[]T` (slices)                | Yes               | Enable with `fory:"ref"` tag   |
| `map[K]V`                     | Yes               | Enable with `fory:"ref"` tag   |
| `*int`, `*string`, etc.       | No                | Pointer to primitives excluded |
| Primitives                    | No                | Value types                    |
| `time.Time`, `time.Duration`  | No                | Value types                    |
| Arrays (`[N]T`)               | No                | Value types                    |

## Per-Field Reference Control

By default, reference tracking is **disabled** for individual fields even when global `WithTrackRef(true)` is set. You can enable reference tracking for specific fields using the `ref` struct tag:

```go
type Container struct {
    // Enable ref tracking for this field
    SharedData *Data `fory:"ref"`

    // Explicitly disable ref tracking (same as default)
    SimpleData *Data `fory:"ref=false"`
}
```

**Important notes**:

- Per-field tags only take effect when global `WithTrackRef(true)` is set
- When global `WithTrackRef(false)` (default), all field ref tags are ignored
- Applies to slices, maps, and pointer to struct fields
- Pointer to primitive types (e.g., `*int`, `*string`) cannot use this tag
- Default is `ref=false` (no reference tracking per field)

See [Struct Tags](schema-metadata.md) for more details.

## Circular References

Reference tracking is required for circular data structures:

### Circular Linked List

```go
type Node struct {
    Value int32
    Next  *Node `fory:"ref"`
}

f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))
f.RegisterStruct(Node{}, 1)

// Create circular list
n1 := &Node{Value: 1}
n2 := &Node{Value: 2}
n3 := &Node{Value: 3}
n1.Next = n2
n2.Next = n3
n3.Next = n1  // Circular reference back to n1

data, _ := f.Serialize(n1)

var result Node
f.Deserialize(data, &result)
// Circular structure is preserved
// result.Next.Next.Next == &result
```

### Parent-Child Tree

```go
type TreeNode struct {
    Value    string
    Parent   *TreeNode   `fory:"ref"`
    Children []*TreeNode `fory:"ref"`
}

f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))
f.RegisterStruct(TreeNode{}, 1)

root := &TreeNode{Value: "root"}
child1 := &TreeNode{Value: "child1", Parent: root}
child2 := &TreeNode{Value: "child2", Parent: root}
root.Children = []*TreeNode{child1, child2}

data, _ := f.Serialize(root)

var result TreeNode
f.Deserialize(data, &result)
// result.Children[0].Parent == &result
```

### Graph Structures

```go
type GraphNode struct {
    ID        int32
    Neighbors []*GraphNode `fory:"ref"`
}

f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))
f.RegisterStruct(GraphNode{}, 1)

// Create a graph
a := &GraphNode{ID: 1}
b := &GraphNode{ID: 2}
c := &GraphNode{ID: 3}

// Bidirectional connections
a.Neighbors = []*GraphNode{b, c}
b.Neighbors = []*GraphNode{a, c}
c.Neighbors = []*GraphNode{a, b}

data, _ := f.Serialize(a)

var result GraphNode
f.Deserialize(data, &result)
```

## Shared Object Deduplication

Reference tracking also deduplicates shared objects:

```go
type Config struct {
    Setting string
}

type Application struct {
    MainConfig     *Config `fory:"ref"`
    BackupConfig   *Config `fory:"ref"`
    FallbackConfig *Config `fory:"ref"`
}

f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))
f.RegisterStruct(Config{}, 1)
f.RegisterStruct(Application{}, 2)

// Shared configuration
config := &Config{Setting: "value"}

// Multiple references to same object
app := &Application{
    MainConfig:     config,
    BackupConfig:   config,
    FallbackConfig: config,
}

data, _ := f.Serialize(app)
// 'config' serialized once, others are references

var result Application
f.Deserialize(data, &result)
// result.MainConfig == result.BackupConfig == result.FallbackConfig
```

## Performance Considerations

### Overhead

Reference tracking adds overhead:

- Memory for tracking seen objects (hash map)
- Hash lookups during serialization
- Additional bytes for reference flags and IDs

### When to Enable

**Enable reference tracking when**:

- Data has circular references
- Same object referenced multiple times
- Serializing graph structures
- Object identity must be preserved

**Disable reference tracking when**:

- Data is tree-structured (no cycles)
- Each object appears only once
- Maximum performance is required
- Object identity doesn't matter

### Memory Usage

Reference tracking maintains a map of serializing objects:

```go
// Internal reference tracking structure
type RefResolver struct {
    writtenObjects map[refKey]int32  // pointer -> reference ID
    readObjects    []reflect.Value   // reference ID -> object
}
```

For large object graphs, this may increase memory usage.

## Error Handling

### Without Reference Tracking

Circular references without tracking cause stack overflow or max depth errors:

```go
f := fory.New(fory.WithXlang(true))  // No reference tracking

n1 := &Node{Value: 1}
n1.Next = n1  // Self-reference

data, err := f.Serialize(n1)
// Error: max depth exceeded (or stack overflow)
```

### Invalid Reference ID

During deserialization, an invalid reference ID produces an error:

```go
// Error type: ErrKindInvalidRefId
```

This occurs when serialized data contains a reference to an object that wasn't previously serialized.

## Complete Example

```go
package main

import (
    "fmt"
    "github.com/apache/fory/go/fory"
)

type Person struct {
    Name       string
    Friends    []*Person  `fory:"ref"`
    BestFriend *Person    `fory:"ref"`
}

func main() {
    f := fory.New(fory.WithXlang(true), fory.WithTrackRef(true))
    f.RegisterStruct(Person{}, 1)

    // Create people with mutual friendships
    alice := &Person{Name: "Alice"}
    bob := &Person{Name: "Bob"}
    charlie := &Person{Name: "Charlie"}

    alice.Friends = []*Person{bob, charlie}
    alice.BestFriend = bob

    bob.Friends = []*Person{alice, charlie}
    bob.BestFriend = alice  // Mutual best friends

    charlie.Friends = []*Person{alice, bob}

    // Serialize
    data, err := f.Serialize(alice)
    if err != nil {
        panic(err)
    }
    fmt.Printf("Serialized %d bytes\n", len(data))

    // Deserialize
    var result Person
    if err := f.Deserialize(data, &result); err != nil {
        panic(err)
    }

    // Verify circular references preserved
    fmt.Printf("Alice's best friend: %s\n", result.BestFriend.Name)
    fmt.Printf("Bob's best friend: %s\n", result.BestFriend.BestFriend.Name)
    // Output: Alice (circular reference preserved)
}
```

## Related Topics

- [Configuration](configuration.md)
- [Struct Tags](schema-metadata.md)
- [Xlang Serialization](xlang.md)
