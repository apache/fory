---
title: Advanced Features
sidebar_position: 11
id: advanced_features
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

This page covers advanced features including zero-copy serialization, deep copy, memory management, and logging.

## Zero-Copy Serialization

Fory supports zero-copy serialization for efficient handling of large binary data:

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.memory.MemoryBuffer;

import java.util.*;
import java.util.stream.Collectors;

public class ZeroCopyExample {
  // Note that fory instance should be reused instead of creation every time.
  static Fory fory = Fory.builder()
    .withXlang(false)
    .build();

  public static void main(String[] args) {
    List<Object> list = Arrays.asList("str", new byte[1000], new int[100], new double[100]);
    Collection<BufferObject> bufferObjects = new ArrayList<>();
    byte[] bytes = fory.serialize(list, e -> !bufferObjects.add(e));
    List<MemoryBuffer> buffers = bufferObjects.stream()
      .map(BufferObject::toBuffer).collect(Collectors.toList());
    System.out.println(fory.deserialize(bytes, buffers));
  }
}
```

## Object Deep Copy

Fory provides efficient deep copy functionality:

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

## Memory Allocation Customization

Fory provides a `MemoryAllocator` interface that allows you to customize how memory buffers are allocated and grown during serialization operations. This can be useful for performance optimization, memory pooling, or debugging memory usage.

### MemoryAllocator Interface

The `MemoryAllocator` interface defines two key methods:

```java
public interface MemoryAllocator {
  /**
   * Allocates a new MemoryBuffer with the specified initial capacity.
   */
  MemoryBuffer allocate(int initialCapacity);

  /**
   * Grows an existing buffer to accommodate the new capacity.
   * The implementation must grow the buffer in-place by modifying
   * the existing buffer instance.
   */
  MemoryBuffer grow(MemoryBuffer buffer, int newCapacity);
}
```

### Using Custom Memory Allocators

You can set a global memory allocator that will be used by all `MemoryBuffer` instances:

```java
// Create a custom allocator
MemoryAllocator customAllocator = new MemoryAllocator() {
  @Override
  public MemoryBuffer allocate(int initialCapacity) {
    // Add extra capacity for debugging or pooling
    return MemoryBuffer.fromByteArray(new byte[initialCapacity + 100]);
  }

  @Override
  public MemoryBuffer grow(MemoryBuffer buffer, int newCapacity) {
    if (newCapacity <= buffer.size()) {
      return buffer;
    }

    // Custom growth strategy - add 100% extra capacity
    int newSize = (int) (newCapacity * 2);
    byte[] data = new byte[newSize];
    buffer.copyToUnsafe(0, data, Platform.BYTE_ARRAY_OFFSET, buffer.size());
    buffer.initHeapBuffer(data, 0, data.length);
    return buffer;
  }
};

// Set the custom allocator globally
MemoryBuffer.setGlobalAllocator(customAllocator);

// All subsequent MemoryBuffer allocations will use your custom allocator
Fory fory = Fory.builder().withXlang(false).build();
byte[] bytes = fory.serialize(someObject); // Uses custom allocator
```

### Default Memory Allocator Behavior

The default allocator uses the following growth strategy:

- For buffers smaller than `BUFFER_GROW_STEP_THRESHOLD` (100MB): multiply capacity by 2
- For larger buffers: multiply capacity by 1.5 (capped at `Integer.MAX_VALUE - 8`)

This provides a balance between avoiding frequent reallocations and preventing excessive memory usage.

### Use Cases

Custom memory allocators are useful for:

- **Memory Pooling**: Reuse allocated buffers to reduce GC pressure
- **Performance Tuning**: Use different growth strategies based on your workload
- **Debugging**: Add logging or tracking to monitor memory usage
- **Off-heap Memory**: Integrate with off-heap memory management systems

## Logging

### ForyLogger

By default, Fory uses a custom logger `ForyLogger` for internal needs. It builds resulting logged data into a single string and sends it directly to `System.out`. The result line layout is similar to (in Log4j notation):

```
%d{yyyy-MM-dd hh:mm:ss} %p  %C:%L [%t] - %m%n
```

The layout can't be changed.

Example output:

```
2025-11-07 08:49:59 INFO  CompileUnit:55 [main] - Generate code for org.apache.fory.builder.SerializedLambdaForyCodec_0 took 35 ms.
2025-11-07 08:50:00 INFO  JaninoUtils:121 [main] - Compile [SerializedLambdaForyCodec_0] take 144 ms
```

### Slf4jLogger

If a more sophisticated logger is required, configure Fory to use Slf4j via `LoggerFactory.useSlf4jLogging()`. For example, enabling Slf4j before creating Fory:

```java
public static final ThreadSafeFory FORY;

static {
  LoggerFactory.useSlf4jLogging(true);
  FORY = Fory.builder()
    .buildThreadSafeFory();
}
```

**Note:** Enabling Slf4j via `useSlf4jLogging` will be ignored when the application runs in a GraalVM native image.

### Suppress Fory Logs

Both `ForyLogger` and `Slf4jLogger` allow controlling log output level or suppressing logs entirely. Configure logger level via `LoggerFactory.setLogLevel()`:

```java
static {
  // to log only WARN and higher
  LoggerFactory.setLogLevel(LogLevel.WARN_LEVEL);

  // to disable logging entirely
  LoggerFactory.disableLogging();
}
```

**Note:** Selected logging level is applied before Slf4j implementation's logger level. So if you set `WARN_LEVEL` (as in the example above) then you will not see INFO messages from Fory even if INFO is enabled in Logback.

## Related Topics

- [Compression](compression.md) - Data compression options
- [Configuration](configuration.md) - All ForyBuilder options
- [Cross-Language Serialization](cross-language.md) - XLANG mode
