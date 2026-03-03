---
title: Troubleshooting
sidebar_position: 11
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

If you create Fory without setting `CompatibleMode` to `org.apache.fory.config.CompatibleMode.COMPATIBLE`, and you get a strange serialization error, it may be caused by class inconsistency between the serialization peer and deserialization peer.

In such cases, you can invoke `ForyBuilder#withClassVersionCheck` to create Fory to validate it. If deserialization throws `org.apache.fory.exception.ClassNotCompatibleException`, it shows classes are inconsistent, and you should create Fory with `ForyBuilder#withCompatibleMode(CompatibleMode.COMPATIBLE)`.

```java
// Enable class version check to diagnose issues
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withClassVersionCheck(true)
  .build();

// If ClassNotCompatibleException is thrown, use compatible mode
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .build();
```

**Note**: `CompatibleMode.COMPATIBLE` has more performance and space cost. Do not set it by default if your classes are always consistent between serialization and deserialization.

## Using Wrong API for Deserialization

Make sure you use the matching API pairs:

| Serialization API                  | Deserialization API                  |
| ---------------------------------- | ------------------------------------ |
| `Fory#serialize`                   | `Fory#deserialize`                   |
| `Fory#serializeJavaObject`         | `Fory#deserializeJavaObject`         |
| `Fory#serializeJavaObjectAndClass` | `Fory#deserializeJavaObjectAndClass` |

**Wrong usage examples:**

```java
// ❌ Wrong: serialize with serialize, deserialize with deserializeJavaObject
byte[] bytes = fory.serialize(object);
Object result = fory.deserializeJavaObject(bytes, MyClass.class);  // Wrong!

// ❌ Wrong: serialize with serializeJavaObject, deserialize with deserialize
byte[] bytes = fory.serializeJavaObject(object);
Object result = fory.deserialize(bytes);  // Wrong!
```

**Correct usage:**

```java
// ✅ Correct: matching API pairs
byte[] bytes = fory.serialize(object);
Object result = fory.deserialize(bytes);

byte[] bytes = fory.serializeJavaObject(object);
MyClass result = fory.deserializeJavaObject(bytes, MyClass.class);

byte[] bytes = fory.serializeJavaObjectAndClass(object);
Object result = fory.deserializeJavaObjectAndClass(bytes);
```

## Deserialize POJO into Another Type

If you want to serialize one POJO and deserialize it into a different POJO type, you must use `CompatibleMode.COMPATIBLE`:

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
    .withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();

  public static void main(String[] args) {
    Struct1 struct1 = new Struct1(10, "abc");
    byte[] data = fory.serializeJavaObject(struct1);
    Struct2 struct2 = (Struct2) fory.deserializeJavaObject(data, Struct2.class);
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

**Solution**: Use compatible mode:

```java
Fory fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
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
fory.registerSerializer(MyClass.class, new MyClassSerializer(fory));
```

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

```java
LoggerFactory.setLogLevel(LogLevel.DEBUG_LEVEL);
```

## Related Topics

- [Configuration](configuration.md) - All ForyBuilder options
- [Schema Evolution](schema-evolution.md) - Compatible mode details
- [Type Registration](type-registration.md) - Registration best practices
- [Migration Guide](migration.md) - Upgrading Fory versions
