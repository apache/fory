---
title: Troubleshooting
sidebar_position: 18
id: troubleshooting
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

This page covers common issues and their solutions.

## Class Inconsistency and Class Version Check

If you explicitly disabled compatible mode and get a strange serialization error, it may be caused by class inconsistency between the serialization peer and deserialization peer.

In such cases, you can invoke `ForyBuilder#withClassVersionCheck` with `withCompatible(false)` to validate it. If deserialization throws `org.apache.fory.exception.ClassNotCompatibleException`, the classes are inconsistent. Remove the `withCompatible(false)` override unless every reader and writer always uses the same class schema.

```java
// Enable class version check to diagnose issues
Fory fory = Fory.builder()
  .withCompatible(false)
  .withClassVersionCheck(true)
  .build();

// If ClassNotCompatibleException is thrown, remove withCompatible(false).
```

**Note**: compatible mode is the default for both xlang and native mode. Use `withCompatible(false)` only if every reader and writer always uses the same class schema and you want faster serialization and smaller size.

## Using Wrong API for Deserialization

Use `serialize` with one of the `deserialize` overloads:

| Serialization API | Deserialization API |
| ----------------- | ------------------- |
| `Fory#serialize`  | `Fory#deserialize`  |

**Wrong usage example:**

```java
// Wrong: deserialize with an incompatible target class
byte[] bytes = fory.serialize(struct1);
Struct2 result = fory.deserialize(bytes, Struct2.class);  // May throw ClassCastException
```

**Correct usage:**

```java
byte[] bytes = fory.serialize(object);
Object result = fory.deserialize(bytes);

byte[] typedBytes = fory.serialize(object);
MyClass typedResult = fory.deserialize(typedBytes, MyClass.class);
```

## Deserialize POJO into Another Type

If you want to serialize one POJO and deserialize it into a different POJO type, use compatible mode:

```java
public class DeserializeIntoType {
  static class Struct1 {
    int f1;
    String f2;

    public Struct1(int f1, String f2) {
      this.f1 = f1;
      this.f2 = f2;
    }
  }

  static class Struct2 {
    int f1;
    String f2;
    double f3;
  }

  static ThreadSafeFory fory = Fory.builder()
    .buildThreadSafeFory();

  public static void main(String[] args) {
    Struct1 struct1 = new Struct1(10, "abc");
    byte[] data = fory.serialize(struct1);
    Struct2 struct2 = fory.deserialize(data, Struct2.class);
  }
}
```

## Common Error Messages

### "Class not registered"

**Cause**: Class registration is required but the class wasn't registered.

**Solution**: Register the class before serialization:

```java
fory.register(MyClass.class);
// or with explicit ID
fory.register(MyClass.class, 100);
```

### "ClassNotCompatibleException"

**Cause**: Class schema differs between serialization and deserialization.

**Solution**: Keep compatible mode enabled:

```java
Fory fory = Fory.builder()
  .build();
```

### "Max depth exceeded"

**Cause**: Object graph is too deep, possibly indicating a circular reference attack.

**Solution**: Increase max depth if legitimate, or check for malicious data:

```java
Fory fory = Fory.builder()
  .withMaxDepth(100)  // Increase from default 50
  .build();
```

### "Serializer not found"

**Cause**: No serializer registered for the type.

**Solution**: Register a custom serializer:

```java
fory.registerSerializer(MyClass.class, new MyClassSerializer(fory.getTypeResolver()));
```

### JDK25+ access errors

On JDK25+, if an error names `java.base/java.lang.invoke`, open `java.lang.invoke` to Fory. Use
`ALL-UNNAMED` when Fory is on the classpath:

```bash
--add-opens=java.base/java.lang.invoke=ALL-UNNAMED
```

Use the Fory core module name when Fory is on the module path:

```bash
--add-opens=java.base/java.lang.invoke=org.apache.fory.core
```

Fory does not require application package opens for private-field access.

## Performance Issues

### Slow Initial Serialization

**Cause**: JIT compilation happening on first serialization.

**Solution**: Enable async compilation:

```java
Fory fory = Fory.builder()
  .withAsyncCompilation(true)
  .build();
```

### High Memory Usage

**Cause**: Large object graphs or reference tracking overhead.

**Solutions**:

- Disable reference tracking if not needed: `.withRefTracking(false)`
- Use custom memory allocator for pooling
- Consider row format for large datasets

### Large Serialized Size

**Cause**: Metadata overhead or uncompressed data.

**Solutions**:

- Enable compression: `.withIntCompressed(true)`, `.withLongCompressed(true)`
- Use compatible mode only when needed
- Register classes to avoid class name serialization

## Debugging Tips

1. **Enable class version check** to diagnose schema issues
2. **Check API pairing** - ensure serialize/deserialize APIs match
3. **Verify registration order** - must be consistent across peers
4. **Enable logging** to see internal operations:

```bash
FORY_LOG_LEVEL=INFO mvn test -Dtest=org.apache.fory.TestClass#testMethod
```

## Related Topics

- [Configuration](configuration.md) - All ForyBuilder options
- [Schema Evolution](schema-evolution.md) - Compatible mode details
- [Type Registration](type-registration.md) - Registration best practices
- [Native Serialization](native-serialization.md) - Java-only serialization features
