---
title: JDK Custom Serialization
sidebar_position: 12
id: jdk-serialization
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

Java native mode can honor existing JDK object customization hooks while writing the Fory native format. Use this page when migrating classes that already implement Java serialization behavior.

## JDK Serialization Hooks

Java native mode supports the JDK serialization hooks that are part of many existing Java object
models:

- `writeObject` and `readObject`
- `writeReplace` and `readResolve`
- `readObjectNoData`
- `Externalizable`

```java
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

public class MyClass implements Serializable {
  private void writeObject(ObjectOutputStream out) throws IOException {
    // Custom serialization logic.
  }

  private void readObject(ObjectInputStream in) throws IOException {
    // Custom deserialization logic.
  }

  private Object writeReplace() {
    return this;
  }

  private Object readResolve() {
    return this;
  }
}
```

Fory native payloads are not JDK `ObjectOutputStream` payloads. The hooks are honored for
Java-object compatibility, but new payloads should be written and read by Fory.

## Migrating From Java Serialization Frameworks

When replacing JDK serialization, Kryo, FST, Hessian, or a Java-only Protocol Buffers pipeline:

1. Start with `.withXlang(false)` because the data is Java-only.
2. Keep `requireClassRegistration(true)` and register application classes with explicit IDs.
3. Keep compatible mode enabled when writer and reader deployments roll independently.
4. Enable `.withRefTracking(true)` only when identity or circular references matter.
5. Add custom serializers for hot classes that would otherwise use expensive JDK serialization hooks.
6. Keep old and new byte streams separated when possible.

When an application must read data that may be either JDK `ObjectOutputStream` bytes or Fory
native-mode bytes, `JavaSerializer.serializedByJDK` can identify the JDK payload before falling
back to Fory:

```java
import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import org.apache.fory.serializer.JavaSerializer;

if (JavaSerializer.serializedByJDK(bytes)) {
  ObjectInputStream objectInputStream = new ObjectInputStream(new ByteArrayInputStream(bytes));
  return objectInputStream.readObject();
}
return fory.deserialize(bytes);
```

Use this bridge only at boundaries that actually accept both formats. Native-mode Fory payloads
should otherwise be written and read by Fory directly.

## Boundaries

Fory native payloads are not JDK `ObjectOutputStream` payloads. Keep mixed legacy streams behind an explicit format-discrimination boundary, and use a Fory custom serializer for new high-throughput object customization.
