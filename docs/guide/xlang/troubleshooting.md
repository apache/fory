---
title: Troubleshooting
sidebar_position: 70
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

This page covers common issues and solutions when using cross-language serialization.

## Type Registration Errors

### "Type not registered" Error

**Symptom:**

```
Error: Type 'example.Person' is not registered
```

**Cause:** The type was not registered before deserialization, or the type name doesn't match.

**Solution:**

1. Ensure the type is registered with the same name on both sides:

   ```java
   // Java
   fory.register(Person.class, "example.Person");
   ```

   ```python
   # Python
   fory.register_type(Person, typename="example.Person")
   ```

2. Check for typos or case differences in type names

3. Register types before any serialization/deserialization calls

### "Type ID mismatch" Error

**Symptom:**

```
Error: Expected type ID 100, got 101
```

**Cause:** Different type IDs used across languages.

**Solution:** Use consistent type IDs:

```java
// Java
fory.register(Person.class, 100);
fory.register(Address.class, 101);
```

```python
# Python
fory.register_type(Person, type_id=100)
fory.register_type(Address, type_id=101)
```

## Type Mapping Issues

### Integer Overflow

**Symptom:** Values are truncated or wrapped unexpectedly.

**Cause:** Using different integer sizes across languages.

**Solution:**

1. In Python, use explicit type annotations:

   ```python
   @dataclass
   class Data:
       value: pyfory.Int32Type  # Not just 'int'
   ```

2. Ensure integer ranges are compatible:
   - `int8`: -128 to 127
   - `int16`: -32,768 to 32,767
   - `int32`: -2,147,483,648 to 2,147,483,647

### Float Precision Loss

**Symptom:** Float values have unexpected precision.

**Cause:** Mixing `float32` and `float64` types.

**Solution:**

1. Use consistent float types:

   ```python
   @dataclass
   class Data:
       value: pyfory.Float32Type  # Explicit 32-bit float
   ```

2. Be aware that Python's `float` maps to `float64` by default

### String Encoding Errors

**Symptom:**

```
Error: Invalid UTF-8 sequence
```

**Cause:** Non-UTF-8 encoded strings.

**Solution:**

1. Ensure all strings are valid UTF-8
2. In Python, decode bytes before serialization:

   ```python
   text = raw_bytes.decode('utf-8')
   ```

## Field Order Issues

### "Field mismatch" Error

**Symptom:** Deserialized objects have wrong field values.

**Cause:** Field order differs between languages.

**Solution:** Fory sorts fields by their snake_cased names. Ensure field names are consistent:

```java
// Java - fields will be sorted: age, email, name
public class Person {
    public String name;
    public int age;
    public String email;
}
```

```python
# Python - same field order
@dataclass
class Person:
    name: str
    age: pyfory.Int32Type
    email: str
```

## Reference Tracking Issues

### Stack Overflow with Circular References

**Symptom:**

```
StackOverflowError or RecursionError
```

**Cause:** Reference tracking is disabled but data has circular references.

**Solution:** Enable reference tracking:

```java
// Java
Fory fory = Fory.builder()
    .withLanguage(Language.XLANG)
    .withRefTracking(true)
    .build();
```

```python
# Python
fory = pyfory.Fory(xlang=True, ref=True)
```

### Duplicate Objects

**Symptom:** Shared objects are duplicated after deserialization.

**Cause:** Reference tracking is disabled.

**Solution:** Enable reference tracking if objects are shared within the graph.

## Language Mode Issues

### Incompatible Types in Xlang Mode

**Symptom:**

```
Error: Type 'Optional' is not supported in xlang mode
```

**Cause:** Using Java-specific types that don't have cross-language equivalents.

**Solution:** Use compatible types:

```java
// Instead of Optional<String>
public String email;  // nullable

// Instead of BigDecimal
public double amount;

// Instead of EnumSet<Status>
public Set<Status> statuses;
```

## Version Compatibility

### Serialization Format Changed

**Symptom:** Deserialization fails after upgrading Fory.

**Cause:** Breaking changes in serialization format.

**Solution:**

1. Ensure all services use compatible Fory versions
2. Check release notes for breaking changes
3. Consider using schema evolution (compatible mode) for gradual upgrades

## Debugging Tips

### Enable Debug Logging

**Java:**

```java
// Add to JVM options
-Dfory.debug=true
```

**Python:**

```python
import logging
logging.getLogger('pyfory').setLevel(logging.DEBUG)
```

### Inspect Serialized Data

Use hex dump to inspect the binary format:

```python
data = fory.serialize(obj)
print(data.hex())
```

### Test Round-Trip

Always test round-trip serialization in each language:

```java
byte[] bytes = fory.serialize(obj);
Object result = fory.deserialize(bytes);
assert obj.equals(result);
```

### Cross-Language Testing

Test serialization across all target languages before deployment:

```bash
# Serialize in Java
java -jar serializer.jar > data.bin

# Deserialize in Python
python deserializer.py data.bin
```

## Common Mistakes

1. **Not registering types**: Always register custom types before use
2. **Inconsistent type names/IDs**: Use the same names/IDs across all languages
3. **Forgetting xlang mode**: Use `Language.XLANG` in Java
4. **Wrong type annotations**: Use `pyfory.Int32Type` etc. in Python
5. **Ignoring reference tracking**: Enable for circular/shared references

## See Also

- [Type Mapping](../../specification/xlang_type_mapping.md) - Cross-language type mapping reference
- [Getting Started](getting-started.md) - Setup guide
- [Java Troubleshooting](../java/troubleshooting.md) - Java-specific issues
- [Python Troubleshooting](../python/troubleshooting.md) - Python-specific issues
