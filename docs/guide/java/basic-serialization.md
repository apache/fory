---
title: Basic Serialization
sidebar_position: 1
id: basic_serialization
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

This page covers basic serialization patterns and Fory instance creation.

## Creating Fory Instances

### Single-Thread Fory

For single-threaded applications:

```java
Fory fory = Fory.builder()
  .withXlang(false)
  // enable reference tracking for shared/circular reference.
  // Disable it will have better performance if no duplicate reference.
  .withRefTracking(false)
  .withCompatible(false)
  // enable type forward/backward compatibility
  // disable it for small size and better performance.
  // .withCompatible(true)
  // enable async multi-threaded compilation.
  .withAsyncCompilation(true)
  .build();
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

### Thread-Safe Fory

For multi-threaded applications:

```java
ThreadSafeFory fory = Fory.builder()
  .withXlang(false)
  // enable reference tracking for shared/circular reference.
  // Disable it will have better performance if no duplicate reference.
  .withRefTracking(false)
  // compress int for smaller size
  // .withIntCompressed(true)
  // compress long for smaller size
  // .withLongCompressed(true)
  .withCompatible(false)
  // enable type forward/backward compatibility
  // disable it for small size and better performance.
  // .withCompatible(true)
  // enable async multi-threaded compilation.
  .withAsyncCompilation(true)
  .buildThreadSafeFory();
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

## Object Deep Copy

Fory provides efficient deep copy functionality:

For the full copy semantics, custom copy hooks, and troubleshooting guidance, see
[Object Copy](object-copy.md).

### With Reference Tracking

```java
Fory fory = Fory.builder().withRefCopy(true).build();
SomeClass a = xxx;
SomeClass copied = fory.copy(a);
```

### Without Reference Tracking (Better Performance)

When disabled, deep copy will ignore circular and shared references. Same reference of an object graph will be copied into different objects in one `Fory#copy`:

```java
Fory fory = Fory.builder().withRefCopy(false).build();
SomeClass a = xxx;
SomeClass copied = fory.copy(a);
```

## Serialization APIs

### Basic Serialize/Deserialize

```java
// Serialize object to byte array
byte[] bytes = fory.serialize(object);

// Deserialize byte array to object
Object obj = fory.deserialize(bytes);
```

### Serialize/Deserialize with Type

```java
// Serialize object
byte[] bytes = fory.serialize(object);

// Deserialize with expected type
MyClass obj = fory.deserialize(bytes, MyClass.class);
```

## Best Practices

1. **Reuse Fory instances**: Creating Fory is expensive, always reuse instances
2. **Use appropriate thread safety**: Choose between single-thread and thread-safe based on your needs
3. **Register classes**: Register frequently used classes for better performance
4. **Configure reference tracking**: Disable if you don't have circular/shared references

## Related Topics

- [Configuration](configuration.md) - All ForyBuilder options
- [Object Copy](object-copy.md) - Deep-copy semantics and custom copy hooks
- [Type Registration](type-registration.md) - Class registration
- [Troubleshooting](troubleshooting.md) - Common API usage issues
