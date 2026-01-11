---
title: References
sidebar_position: 50
id: go_references
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

Enable reference tracking when creating a Fory instance:

```go
f := fory.New(fory.WithTrackRef(true))
```

## How Reference Tracking Works

### Without Reference Tracking (Default)

When disabled, each object is serialized independently:

```go
f := fory.New()  // TrackRef disabled by default

shared := &Data{Value: 42}
container := &Container{A: shared, B: shared}

data, _ := f.Serialize(container)
// 'shared' is serialized TWICE
```

### With Reference Tracking

When enabled, objects are tracked by identity:

```go
f := fory.New(fory.WithTrackRef(true))

shared := &Data{Value: 42}
container := &Container{A: shared, B: shared}

data, _ := f.Serialize(container)
// 'shared' is serialized ONCE, second occurrence is a reference
```

## Reference Flags

Fory uses flags to indicate reference states:

| Flag               | Value | Meaning                                   |
| ------------------ | ----- | ----------------------------------------- |
| `NullFlag`         | -3    | Nil/null value                            |
| `RefFlag`          | -2    | Reference to previously serialized object |
| `NotNullValueFlag` | -1    | Non-null value (data follows)             |
| `RefValueFlag`     | 0     | Reference value flag                      |

## Circular References

Reference tracking is required for circular data structures:

### Circular Linked List

```go
type Node struct {
    Value int32
    Next  *Node
}

f := fory.New(fory.WithTrackRef(true))
f.RegisterByName(Node{}, "example.Node")

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
    Parent   *TreeNode
    Children []*TreeNode
}

f := fory.New(fory.WithTrackRef(true))
f.RegisterByName(TreeNode{}, "example.TreeNode")

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
    ID       int32
    Neighbors []*GraphNode
}

f := fory.New(fory.WithTrackRef(true))
f.RegisterByName(GraphNode{}, "example.GraphNode")

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
f := fory.New(fory.WithTrackRef(true))

// Shared configuration
config := &Config{Setting: "value"}

// Multiple references to same object
app := &Application{
    MainConfig:   config,
    BackupConfig: config,
    FallbackConfig: config,
}

data, _ := f.Serialize(app)
// 'config' serialized once, others are references

var result Application
f.Deserialize(data, &result)
// result.MainConfig == result.BackupConfig == result.FallbackConfig
```

## Referenceable Types

Not all types track references. Only the following types support reference tracking in xlang mode:

| Type                               | Reference Tracked |
| ---------------------------------- | ----------------- |
| `*struct` (pointer to struct)      | Yes               |
| `interface{}`                      | Yes               |
| `[]T` (slices)                     | Yes               |
| `map[K]V`                          | Yes               |
| Primitives (`int`, `string`, etc.) | No                |
| `time.Time`, `time.Duration`       | No                |
| Arrays (`[N]T`)                    | No (value types)  |

## Per-Field Reference Control

You can control reference tracking per field using struct tags:

```go
type Container struct {
    // Always track refs for this field
    Important *Data `fory:"trackRef"`

    // Never track refs for this field
    Simple *Data `fory:"trackRef=false"`
}
```

See [Struct Tags](struct-tags) for more details.

## Performance Considerations

### Overhead

Reference tracking adds overhead:

- Memory for tracking seen objects
- Hash lookups during serialization
- Additional bytes for reference IDs

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

Reference tracking maintains a map of serialized objects:

```go
// Pseudocode for reference tracking
type RefResolver struct {
    written map[uintptr]int  // pointer -> reference ID
    read    []interface{}    // reference ID -> object
}
```

For large object graphs, this may increase memory usage.

## Error Handling

### Without Reference Tracking

Circular references cause infinite recursion:

```go
f := fory.New()  // No reference tracking

n1 := &Node{Value: 1}
n1.Next = n1  // Self-reference

data, err := f.Serialize(n1)
// Error: max depth exceeded (or stack overflow)
```

### Invalid Reference ID

```go
// Error during deserialization
ErrKindInvalidRefId
```

This occurs when serialized data contains an invalid reference.

## Complete Example

```go
package main

import (
    "fmt"
    "github.com/apache/fory/go/fory"
)

type Person struct {
    Name    string
    Friends []*Person
    BestFriend *Person
}

func main() {
    f := fory.New(fory.WithTrackRef(true))
    f.RegisterByName(Person{}, "example.Person")

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

- [Configuration](configuration)
- [Struct Tags](struct-tags)
- [Troubleshooting](troubleshooting)
