---
title: Zero-Copy Serialization
sidebar_position: 50
id: zero_copy
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

Zero-copy serialization allows large binary data (byte arrays, numeric arrays) to be serialized out-of-band, avoiding memory copies and reducing serialization overhead.

## When to Use Zero-Copy

Use zero-copy serialization when:

- Serializing large byte arrays or binary blobs
- Working with numeric arrays (int[], double[], etc.)
- Transferring data over high-performance networks
- Memory efficiency is critical

## How It Works

1. **Serialization**: Large buffers are extracted and returned separately via a callback
2. **Transport**: The main serialized data and buffer objects are transmitted separately
3. **Deserialization**: Buffers are provided back to reconstruct the original object

This avoids copying large data into the main serialization buffer.

## Java

```java
import org.apache.fory.*;
import org.apache.fory.config.*;
import org.apache.fory.serializer.BufferObject;
import org.apache.fory.memory.MemoryBuffer;

import java.util.*;
import java.util.stream.Collectors;

public class ZeroCopyExample {
  public static void main(String[] args) {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();

    // Data with large arrays
    List<Object> list = List.of(
        "str",
        new byte[1000],    // Large byte array
        new int[100],      // Large int array
        new double[100]    // Large double array
    );

    // Collect buffer objects during serialization
    Collection<BufferObject> bufferObjects = new ArrayList<>();
    byte[] bytes = fory.serialize(list, e -> !bufferObjects.add(e));

    // Convert to buffers for transport
    List<MemoryBuffer> buffers = bufferObjects.stream()
        .map(BufferObject::toBuffer)
        .collect(Collectors.toList());

    // Deserialize with buffers
    Object result = fory.deserialize(bytes, buffers);
    System.out.println(result);
  }
}
```

## Python

```python
import array
import pyfory
import numpy as np

fory = pyfory.Fory(xlang=True)

# Data with large arrays
data = [
    "str",
    bytes(bytearray(1000)),           # Large byte array
    array.array("i", range(100)),     # Large int array
    np.full(100, 0.0, dtype=np.double) # Large numpy array
]

# Collect buffer objects during serialization
serialized_objects = []
serialized_data = fory.serialize(data, buffer_callback=serialized_objects.append)

# Convert to buffers for transport
buffers = [obj.to_buffer() for obj in serialized_objects]

# Deserialize with buffers
result = fory.deserialize(serialized_data, buffers=buffers)
print(result)
```

## Go

```go
package main

import forygo "github.com/apache/fory/go/fory"
import "fmt"

func main() {
  serializer := forygo.NewFory(forygo.WithXlang(true))

  // Data with large arrays
  list := []any{
    "str",
    make([]byte, 1000), // Large byte array
  }

  buf := forygo.NewByteBuffer(nil)
  var bufferObjects []forygo.BufferObject

  // Collect buffer objects during serialization
  if err := serializer.SerializeWithCallback(buf, list, func(o forygo.BufferObject) bool {
    bufferObjects = append(bufferObjects, o)
    return false
  }); err != nil {
    panic(err)
  }

  // Convert to buffers for transport
  var buffers []*forygo.ByteBuffer
  for _, o := range bufferObjects {
    buffers = append(buffers, o.ToBuffer())
  }

  // Deserialize with buffers
  var newList []any
  if err := serializer.DeserializeWithCallbackBuffers(buf, &newList, buffers); err != nil {
    panic(err)
  }
  fmt.Println(newList)
}
```

## JavaScript

```javascript
// Zero-copy support coming soon
```

## Use Cases

### High-Performance Data Transfer

When sending large datasets over the network:

```java
// Sender
Collection<BufferObject> buffers = new ArrayList<>();
byte[] metadata = fory.serialize(dataObject, e -> !buffers.add(e));

// Send metadata and buffers separately
network.sendMetadata(metadata);
for (BufferObject buf : buffers) {
    network.sendBuffer(buf.toBuffer());
}

// Receiver
byte[] metadata = network.receiveMetadata();
List<MemoryBuffer> buffers = network.receiveBuffers();
Object data = fory.deserialize(metadata, buffers);
```

### Memory-Mapped Files

Zero-copy works well with memory-mapped files:

```java
// Write
Collection<BufferObject> buffers = new ArrayList<>();
byte[] data = fory.serialize(largeObject, e -> !buffers.add(e));
writeToFile("data.bin", data);
for (int i = 0; i < buffers.size(); i++) {
    writeToFile("buffer" + i + ".bin", buffers.get(i).toBuffer());
}

// Read
byte[] data = readFromFile("data.bin");
List<MemoryBuffer> buffers = readBufferFiles();
Object result = fory.deserialize(data, buffers);
```

## Performance Considerations

1. **Threshold**: Small arrays may not benefit from zero-copy due to callback overhead
2. **Network**: Zero-copy is most beneficial when buffers can be sent without copying
3. **Memory**: Reduces peak memory usage by avoiding buffer copies

## See Also

- [Serialization](serialization.md) - Standard serialization examples
- [Python Out-of-Band Guide](../python/out-of-band.md) - Python-specific zero-copy details
