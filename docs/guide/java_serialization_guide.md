---
title: Java Serialization Guide
sidebar_position: 0
id: java_object_graph_guide
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

When only java object serialization needed, this mode will have better performance compared to cross-language object
graph serialization.

## Quick Start

Note that fory creation is not cheap, the **fory instances should be reused between serializations** instead of creating
it everytime.
You should keep fory to a static global variable, or instance variable of some singleton object or limited objects.

Fory for single-thread usage:

```java
import java.util.List;
import java.util.Arrays;

import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    // Note that Fory instances should be reused between
    // multiple serializations of different objects.
    Fory fory = Fory.builder().withLanguage(Language.JAVA)
      .requireClassRegistration(true)
      .build();
    // Registering types can reduce class name serialization overhead, but not mandatory.
    // If class registration enabled, all custom types must be registered.
    fory.register(SomeClass.class);
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

Fory for multiple-thread usage:

```java
import java.util.List;
import java.util.Arrays;

import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    // Note that Fory instances should be reused between
    // multiple serializations of different objects.
    ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
      Fory f = Fory.builder().withLanguage(Language.JAVA)
        .withClassLoader(classLoader).build();
      f.register(SomeClass.class);
      return f;
    });
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

Fory instances reuse example:

```java
import java.util.List;
import java.util.Arrays;

import org.apache.fory.*;
import org.apache.fory.config.*;

public class Example {
  // reuse fory.
  private static final ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
    Fory f = Fory.builder().withLanguage(Language.JAVA)
      .withClassLoader(classLoader).build();
    f.register(SomeClass.class);
    return f;
  });

  public static void main(String[] args) {
    SomeClass object = new SomeClass();
    byte[] bytes = fory.serialize(object);
    System.out.println(fory.deserialize(bytes));
  }
}
```

## ForyBuilder options

| Option Name                         | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | Default Value                                                  |
| ----------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `timeRefIgnored`                    | Whether to ignore reference tracking of all time types registered in `TimeSerializers` and subclasses of those types when ref tracking is enabled. If ignored, ref tracking of every time type can be enabled by invoking `Fory#registerSerializer(Class, Serializer)`. For example, `fory.registerSerializer(Date.class, new DateSerializer(fory, true))`. Note that enabling ref tracking should happen before serializer codegen of any types which contain time fields. Otherwise, those fields will still skip ref tracking. | `true`                                                         |
| `compressInt`                       | Enables or disables int compression for smaller size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `true`                                                         |
| `compressLong`                      | Enables or disables long compression for smaller size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | `true`                                                         |
| `compressString`                    | Enables or disables string compression for smaller size.                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | `false`                                                        |
| `classLoader`                       | The classloader should not be updated; Fory caches class metadata. Use `LoaderBinding` or `ThreadSafeFory` for classloader updates.                                                                                                                                                                                                                                                                                                                                                                                               | `Thread.currentThread().getContextClassLoader()`               |
| `compatibleMode`                    | Type forward/backward compatibility config. Also Related to `checkClassVersion` config. `SCHEMA_CONSISTENT`: Class schema must be consistent between serialization peer and deserialization peer. `COMPATIBLE`: Class schema can be different between serialization peer and deserialization peer. They can add/delete fields independently. [See more](#class-inconsistency-and-class-version-check).                                                                                                                            | `CompatibleMode.SCHEMA_CONSISTENT`                             |
| `checkClassVersion`                 | Determines whether to check the consistency of the class schema. If enabled, Fory checks, writes, and checks consistency using the `classVersionHash`. It will be automatically disabled when `CompatibleMode#COMPATIBLE` is enabled. Disabling is not recommended unless you can ensure the class won't evolve.                                                                                                                                                                                                                  | `false`                                                        |
| `checkJdkClassSerializable`         | Enables or disables checking of `Serializable` interface for classes under `java.*`. If a class under `java.*` is not `Serializable`, Fory will throw an `UnsupportedOperationException`.                                                                                                                                                                                                                                                                                                                                         | `true`                                                         |
| `registerGuavaTypes`                | Whether to pre-register Guava types such as `RegularImmutableMap`/`RegularImmutableList`. These types are not public API, but seem pretty stable.                                                                                                                                                                                                                                                                                                                                                                                 | `true`                                                         |
| `requireClassRegistration`          | Disabling may allow unknown classes to be deserialized, potentially causing security risks.                                                                                                                                                                                                                                                                                                                                                                                                                                       | `true`                                                         |
| `suppressClassRegistrationWarnings` | Whether to suppress class registration warnings. The warnings can be used for security audit, but may be annoying, this suppression will be enabled by default.                                                                                                                                                                                                                                                                                                                                                                   | `true`                                                         |
| `metaShareEnabled`                  | Enables or disables meta share mode.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | `true` if `CompatibleMode.Compatible` is set, otherwise false. |
| `scopedMetaShareEnabled`            | Scoped meta share focuses on a single serialization process. Metadata created or identified during this process is exclusive to it and is not shared with by other serializations.                                                                                                                                                                                                                                                                                                                                                | `true` if `CompatibleMode.Compatible` is set, otherwise false. |
| `metaCompressor`                    | Set a compressor for meta compression. Note that the passed MetaCompressor should be thread-safe. By default, a `Deflater` based compressor `DeflaterMetaCompressor` will be used. Users can pass other compressor such as `zstd` for better compression rate.                                                                                                                                                                                                                                                                    | `DeflaterMetaCompressor`                                       |
| `deserializeNonexistentClass`       | Enables or disables deserialization/skipping of data for non-existent classes.                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `true` if `CompatibleMode.Compatible` is set, otherwise false. |
| `codeGenEnabled`                    | Disabling may result in faster initial serialization but slower subsequent serializations.                                                                                                                                                                                                                                                                                                                                                                                                                                        | `true`                                                         |
| `asyncCompilationEnabled`           | If enabled, serialization uses interpreter mode first and switches to JIT serialization after async serializer JIT for a class is finished.                                                                                                                                                                                                                                                                                                                                                                                       | `false`                                                        |
| `scalaOptimizationEnabled`          | Enables or disables Scala-specific serialization optimization.                                                                                                                                                                                                                                                                                                                                                                                                                                                                    | `false`                                                        |
| `copyRef`                           | When disabled, the copy performance will be better. But fory deep copy will ignore circular and shared reference. Same reference of an object graph will be copied into different objects in one `Fory#copy`.                                                                                                                                                                                                                                                                                                                     | `true`                                                         |
| `serializeEnumByName`               | When Enabled, fory serialize enum by name instead of ordinal.                                                                                                                                                                                                                                                                                                                                                                                                                                                                     | `false`                                                        |

## Advanced Usage

### Fory creation

Single thread fory:

```java
Fory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  // enable reference tracking for shared/circular reference.
  // Disable it will have better performance if no duplicate reference.
  .withRefTracking(false)
  .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
  // enable type forward/backward compatibility
  // disable it for small size and better performance.
  // .withCompatibleMode(CompatibleMode.COMPATIBLE)
  // enable async multi-threaded compilation.
  .withAsyncCompilation(true)
  .build();
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

Thread-safe fory:

```java
ThreadSafeFory fory = Fory.builder()
  .withLanguage(Language.JAVA)
  // enable reference tracking for shared/circular reference.
  // Disable it will have better performance if no duplicate reference.
  .withRefTracking(false)
  // compress int for smaller size
  // .withIntCompressed(true)
  // compress long for smaller size
  // .withLongCompressed(true)
  .withCompatibleMode(CompatibleMode.SCHEMA_CONSISTENT)
  // enable type forward/backward compatibility
  // disable it for small size and better performance.
  // .withCompatibleMode(CompatibleMode.COMPATIBLE)
  // enable async multi-threaded compilation.
  .withAsyncCompilation(true)
  .buildThreadSafeFory();
byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

Note that calling `buildThreadSafeFory()` on `ForyBuilder` will create an instance of `ThreadLocalFory`.
This may not be appropriate in environments where virtual threads are used, as each thread will create its own fory instance, a relatively expensive operation.
An alternative for virtual threads is to use `buildThreadSafeForyPool`.

### Handling Class Schema Evolution in Serialization

In many systems, the schema of a class used for serialization may change over time. For instance, fields within a class
may be added or removed. When serialization and deserialization processes use different versions of jars, the schema of
the class being deserialized may differ from the one used during serialization.

By default, Fory serializes objects using the `CompatibleMode.SCHEMA_CONSISTENT` mode. This mode assumes that the
deserialization process uses the same class schema as the serialization process, minimizing payload overhead.
However, if there is a schema inconsistency, deserialization will fail.

If the schema is expected to change, to make deserialization succeed, i.e. schema forward/backward compatibility.
Users must configure Fory to use `CompatibleMode.COMPATIBLE`. This can be done using the
`ForyBuilder#withCompatibleMode(CompatibleMode.COMPATIBLE)` method.
In this compatible mode, deserialization can handle schema changes such as missing or extra fields, allowing it to
succeed even when the serialization and deserialization processes have different class schemas.

Here is an example of creating Fory to support schema evolution:

```java
Fory fory = Fory.builder()
  .withCompatibleMode(CompatibleMode.COMPATIBLE)
  .build();

byte[] bytes = fory.serialize(object);
System.out.println(fory.deserialize(bytes));
```

This compatible mode involves serializing class metadata into the serialized output. Despite Fory's use of
sophisticated compression techniques to minimize overhead, there is still some additional space cost associated with
class metadata.

To further reduce metadata costs, Fory introduces a class metadata sharing mechanism, which allows the metadata to be
sent to the deserialization process only once. For more details, please refer to the [Meta Sharing](https://fory.apache.org/docs/specification/fory_java_serialization_spec#meta-share) specification.

### Compression

`ForyBuilder#withIntCompressed`/`ForyBuilder#withLongCompressed` can be used to compress int/long for smaller size.
Normally compress int is enough.

Both compression are enabled by default, if the serialized is not important, for example, you use flatbuffers for
serialization before, which doesn't compress anything, then you should disable compression. If your data are all
numbers,
the compression may bring 80% performance regression.

For int compression, fory use 1~5 bytes for encoding. First bit in every byte indicate whether has next byte. if first
bit is set, then next byte will be read util first bit of next byte is unset.

For long compression, fory support two encoding:

- Fory SLI(Small long as int) Encoding (**used by default**):
  - If long is in `[-1073741824, 1073741823]`, encode as 4 bytes int: `| little-endian: ((int) value) << 1 |`
  - Otherwise write as 9 bytes: `| 0b1 | little-endian 8bytes long |`
- Fory PVL(Progressive Variable-length Long) Encoding:
  - First bit in every byte indicate whether has next byte. if first bit is set, then next byte will be read util
    first bit of next byte is unset.
  - Negative number will be converted to positive number by `(v << 1) ^ (v >> 63)` to reduce cost of small negative
    numbers.

If a number are `long` type, it can't be represented by smaller bytes mostly, the compression won't get good enough
result,
not worthy compared to performance cost. Maybe you should try to disable long compression if you find it didn't bring
much
space savings.

### Object deep copy

Deep copy example:

```java
Fory fory = Fory.builder().withRefCopy(true).build();
SomeClass a = xxx;
SomeClass copied = fory.copy(a);
```

Make fory deep copy ignore circular and shared reference, this deep copy mode will ignore circular and shared reference.
Same reference of an object graph will be copied into different objects in one `Fory#copy`.

```java
Fory fory = Fory.builder().withRefCopy(false).build();
SomeClass a = xxx;
SomeClass copied = fory.copy(a);
```

### Implement a customized serializer

In some cases, you may want to implement a serializer for your type, especially some class customize serialization by
JDK `writeObject/writeReplace/readObject/readResolve`, which is very inefficient. For example, if you don't want
following `Foo#writeObject` got invoked, you can take following `FooSerializer` as an example:

```java
class Foo {
  public long f1;

  private void writeObject(ObjectOutputStream s) throws IOException {
    System.out.println(f1);
    s.defaultWriteObject();
  }
}

class FooSerializer extends Serializer<Foo> {
  public FooSerializer(Fory fory) {
    super(fory, Foo.class);
  }

  @Override
  public void write(MemoryBuffer buffer, Foo value) {
    buffer.writeInt64(value.f1);
  }

  @Override
  public Foo read(MemoryBuffer buffer) {
    Foo foo = new Foo();
    foo.f1 = buffer.readInt64();
    return foo;
  }
}
```

Register serializer:

```java
Fory fory = getFory();
fory.registerSerializer(Foo.class, new FooSerializer(fory));
```

### Implement Collection Serializer

Similar to maps, when implementing a serializer for a custom Collection type, you must extend `CollectionSerializer` or `AbstractCollectionSerializer`.
The key difference between these two is that `AbstractCollectionSerializer` can serialize a class which has a collection-like structure but is not a java Collection subtype.

For collection serializer, this is a special parameter `supportCodegenHook` needs be configured:

- When `true`:
  - Enables optimized access to collection elements and JIT compilation for better performance
  - Direct serialization invocation and inline for map key-value items without dynamic serializer dispatch cost.
  - Better performance for standard collection types
  - Recommended for most collections

- When `false`:
  - Uses interfaced-based element access and dynamic serializer dispatch for elements, which have higher cost
  - More flexible for custom collection types
  - Required when collection has special serialization needs
  - Handles complex collection implementations

#### Implement Collection Serializer with JIT support

When implementing a Collection serializer with JIT support, you can leverage Fory's existing binary format and collection serialization infrastructure. The key is to properly implement the `onCollectionWrite` and `newCollection` methods to handle metadata while letting Fory handle the element serialization.

Here's an example:

```java
public class CustomCollectionSerializer<T extends Collection> extends CollectionSerializer<T> {
    public CustomCollectionSerializer(Fory fory, Class<T> cls) {
        // supportCodegenHook controls whether to use JIT compilation
        super(fory, cls, true);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, T value) {
        // Write collection size
        buffer.writeVarUint32Small7(value.size());
        // Write any additional collection metadata
        return value;
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
        // Create new collection instance
        Collection collection = super.newCollection(buffer);
        // Read and set collection size
        int numElements = getAndClearNumElements();
        setNumElements(numElements);
        return collection;
    }
}
```

Note that please invoke `setNumElements` when implementing `newCollection` to let fory know how many elements to deserialize.

#### Implement a totally-customzied Collection Serializer without JIT

Sometimes you need to serialize a collection type that uses primitive arrays or has special requirements.
In such cases, you can implement a serializer with JIT disabled and directly override the `write` and `read` methods.

This approach:

- Gives you full control over serialization
- Works well with primitive arrays
- Bypasses collection iteration overhead
- Allows direct memory access

Here's an example of a custom integer list backed by a primitive array:

```java
class IntList extends AbstractCollection<Integer> {
    private final int[] elements;
    private final int size;

    public IntList(int size) {
        this.elements = new int[size];
        this.size = size;
    }

    public IntList(int[] elements, int size) {
        this.elements = elements;
        this.size = size;
    }

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<Integer>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public Integer next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return elements[index++];
            }
        };
    }

    @Override
    public int size() {
        return size;
    }

    public int get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return elements[index];
    }

    public void set(int index, int value) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        elements[index] = value;
    }

    public int[] getElements() {
        return elements;
    }
}

class IntListSerializer extends AbstractCollectionSerializer<IntList> {
    public IntListSerializer(Fory fory) {
        // Disable JIT since we're handling serialization directly
        super(fory, IntList.class, false);
    }

    @Override
    public void write(MemoryBuffer buffer, IntList value) {
        // Write size
        buffer.writeVarUint32Small7(value.size());

        // Write elements directly as primitive ints
        int[] elements = value.getElements();
        for (int i = 0; i < value.size(); i++) {
            buffer.writeVarInt32(elements[i]);
        }
    }

    @Override
    public IntList read(MemoryBuffer buffer) {
        // Read size
        int size = buffer.readVarUint32Small7();

        // Create array and read elements
        int[] elements = new int[size];
        for (int i = 0; i < size; i++) {
            elements[i] = buffer.readVarInt32();
        }

        return new IntList(elements, size);
    }

    // These methods are not used when JIT is disabled
    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, IntList value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IntList onCollectionRead(Collection collection) {
        throw new UnsupportedOperationException();
    }
}
```

Key Points:

1. **Primitive Array Storage**:
   - Uses `int[]` for direct storage
   - Avoids boxing/unboxing overhead
   - Provides efficient memory layout
   - Enables direct array access

2. **Direct Serialization**:
   - Write size first
   - Write primitive values directly
   - No iteration overhead
   - No boxing/unboxing during serialization

3. **Direct Deserialization**:
   - Read size first
   - Create primitive array
   - Read values directly into array
   - Create list with populated array

4. **Disabled JIT**:
   - Set `supportCodegenHook=false`
   - Override `write`/`read` methods
   - Skip collection view pattern
   - Full control over serialization format

When to Use: this approach is best when:

- Working with primitive types
- Need maximum performance
- Want to minimize memory overhead
- Have special serialization requirements

Usage Example:

```java
// Create and populate list
IntList list = new IntList(3);
list.set(0, 1);
list.set(1, 2);
list.set(2, 3);

// Serialize
byte[] bytes = fory.serialize(list);

// Deserialize
IntList newList = (IntList) fory.deserialize(bytes);
```

This implementation is particularly efficient for scenarios where:

- You're working exclusively with integers
- Performance is critical
- Memory efficiency is important
- Serialization overhead needs to be minimized

Remember that while this approach gives up some of Fory's optimizations, it can provide better performance for specific use cases involving primitive types and direct array access.

#### Implement Serializer for Collection-like Types

Sometimes you may want to implement a serializer for a type that behaves like a collection but isn't a standard Java Collection. This section demonstrates how to implement a serializer for such types.

The key principles for collection-like type serialization are:

1. Extend `AbstractCollectionSerializer` for custom collection-like types
2. Enable JIT optimization with `supportCodegenHook`
3. Provide efficient element access through views
4. Maintain proper size tracking

Here's an example:

```java
class CustomCollectionLike {
    private final Object[] elements;
    private final int size;

    public CustomCollectionLike(int size) {
        this.elements = new Object[size];
        this.size = size;
    }

    // Constructor for wrapping existing array
    public CustomCollectionLike(Object[] elements, int size) {
        this.elements = elements;
        this.size = size;
    }

    public Object get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException();
        }
        return elements[index];
    }

    public int size() {
        return size;
    }

    public Object[] getElements() {
        return elements;
    }
}

// A view class that extends AbstractCollection for simpler implementation
class CollectionView extends AbstractCollection<Object> {
    private final Object[] elements;
    private final int size;
    private int writeIndex;

    // Constructor for serialization (wrapping existing array)
    public CollectionView(CustomCollectionLike collection) {
        this.elements = collection.getElements();
        this.size = collection.size();
    }

    // Constructor for deserialization
    public CollectionView(int size) {
        this.size = size;
        this.elements = new Object[size];
    }

    @Override
    public Iterator<Object> iterator() {
        return new Iterator<Object>() {
            private int index = 0;

            @Override
            public boolean hasNext() {
                return index < size;
            }

            @Override
            public Object next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return elements[index++];
            }
        };
    }

    @Override
    public boolean add(Object element) {
        if (writeIndex >= size) {
            throw new IllegalStateException("Collection is full");
        }
        elements[writeIndex++] = element;
        return true;
    }

    @Override
    public int size() {
        return size;
    }

    public Object[] getElements() {
        return elements;
    }
}

class CustomCollectionSerializer extends AbstractCollectionSerializer<CustomCollectionLike> {
    public CustomCollectionSerializer(Fory fory) {
        super(fory, CustomCollectionLike.class, true);
    }

    @Override
    public Collection onCollectionWrite(MemoryBuffer buffer, CustomCollectionLike value) {
        buffer.writeVarUint32Small7(value.size());
        return new CollectionView(value);
    }

    @Override
    public Collection newCollection(MemoryBuffer buffer) {
        int numElements = buffer.readVarUint32Small7();
        setNumElements(numElements);
        return new CollectionView(numElements);
    }

    @Override
    public CustomCollectionLike onCollectionRead(Collection collection) {
        CollectionView view = (CollectionView) collection;
        return new CustomCollectionLike(view.getElements(), view.size());
    }
}
```

Key takeways:

1. **Collection Structure**:
   - Array-based storage for elements
   - Fixed size after creation
   - Direct element access
   - Size tracking

2. **View Implementation**:
   - Extends `AbstractCollection` for simplicity
   - Provides iterator for element access
   - Implements `add()` for deserialization
   - Shares array reference with original type

3. **Serializer Features**:
   - Uses `supportCodegenHook=true` for JIT optimization
   - Shares array references when possible
   - Maintains proper size tracking
   - Uses view pattern for serialization

4. **Performance Aspects**:
   - Direct array access
   - Minimal object creation
   - Array sharing between instances
   - Efficient iteration

Note that this implementation provides better performance at the cost of flexibility. Consider your specific use case when choosing this approach.

### Implement Map Serializer

When implementing a serializer for a custom Map type, you must extend `MapSerializer` or `AbstractMapSerializer`. The key difference between these two is that `AbstractMapSerializer` can serialize a class which has a map-like structure but is not a java Map subtype.

Similar to collection serializer, this is a special parameter `supportCodegenHook` needs be configured:

- When `true`:
  - Enables optimized access to map elements and JIT compilation for better performance
  - Direct serialization invocation and inline for map key-value items without dynamic serializer dispatch cost.
  - Better performance for standard map types
  - Recommended for most maps

- When `false`:
  - Uses interfaced-based element access and dynamic serializer dispatch for elements, which have higher cost
  - More flexible for custom map types
  - Required when map has special serialization needs
  - Handles complex map implementations

#### Implement Map Serializer with JIT support

When implementing a Map serializer with JIT support, you can leverage Fory's existing chunk-based binary format and map serialization infrastructure. The key is to properly implement the `onMapWrite` and `newMap` methods to handle metadata while letting Fory handle the map key-value serialization.

Here's an example of implementing a custom map serializer:

```java
public class CustomMapSerializer<T extends Map> extends MapSerializer<T> {
    public CustomMapSerializer(Fory fory, Class<T> cls) {
        // supportCodegenHook is a critical parameter that determines serialization behavior
        super(fory, cls, true);
    }

    @Override
    public Map onMapWrite(MemoryBuffer buffer, T value) {
        // Write map size
        buffer.writeVarUint32Small7(value.size());
        // Write any additional map metadata here
        return value;
    }

    @Override
    public Map newMap(MemoryBuffer buffer) {
        // Read map size
        int numElements = buffer.readVarUint32Small7();
        setNumElements(numElements);
        // Create and return new map instance
        T map = (T) new HashMap(numElements);
        fory.getRefResolver().reference(map);
        return map;
    }
}
```

Note that please invoke `setNumElements` when implementing `newMap` to let fory know how many elements to deserialize.

#### Implement a totally-customzied Map Serializer without JIT

Sometimes you may need complete control over the serialization process, or your map type might have special requirements that don't fit the standard patterns. In such cases, you can implement a serializer with `supportCodegenHook=false` and directly override the `write` and `read` methods.

This approach:

- Gives you full control over serialization
- Allows custom binary format
- Bypasses the standard map serialization pattern
- May be simpler for special cases

Here's an example:

```java
class FixedValueMap extends AbstractMap<String, Integer> {
    private final Set<String> keys;
    private final int fixedValue;

    public FixedValueMap(Set<String> keys, int fixedValue) {
        this.keys = keys;
        this.fixedValue = fixedValue;
    }

    @Override
    public Set<Entry<String, Integer>> entrySet() {
        Set<Entry<String, Integer>> entries = new HashSet<>();
        for (String key : keys) {
            entries.add(new SimpleEntry<>(key, fixedValue));
        }
        return entries;
    }

    @Override
    public Integer get(Object key) {
        return keys.contains(key) ? fixedValue : null;
    }

    public Set<String> getKeys() {
        return keys;
    }

    public int getFixedValue() {
        return fixedValue;
    }
}

class FixedValueMapSerializer extends AbstractMapSerializer<FixedValueMap> {
    public FixedValueMapSerializer(Fory fory) {
        // Disable codegen since we're handling serialization directly
        super(fory, FixedValueMap.class, false);
    }

    @Override
    public void write(MemoryBuffer buffer, FixedValueMap value) {
        // Write the fixed value
        buffer.writeInt32(value.getFixedValue());
        // Write the number of keys
        buffer.writeVarUint32Small7(value.getKeys().size());
        // Write each key
        for (String key : value.getKeys()) {
            buffer.writeString(key);
        }
    }

    @Override
    public FixedValueMap read(MemoryBuffer buffer) {
        // Read the fixed value
        int fixedValue = buffer.readInt32();
        // Read the number of keys
        int size = buffer.readVarUint32Small7();
        Set<String> keys = new HashSet<>(size);
        for (int i = 0; i < size; i++) {
            keys.add(buffer.readString());
        }
        return new FixedValueMap(keys, fixedValue);
    }

    // These methods are not used when supportCodegenHook is false
    @Override
    public Map onMapWrite(MemoryBuffer buffer, FixedValueMap value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FixedValueMap onMapRead(Map map) {
        throw new UnsupportedOperationException();
    }

    @Override
    public FixedValueMap onMapCopy(Map map) {
        throw new UnsupportedOperationException();
    }
}
```

Key Points:

1. **Disable Codegen**:
   - Set `supportCodegenHook=false` in constructor
   - Fory will use your `write`/`read` methods directly
   - No JIT optimization will be applied
   - Full control over serialization format

2. **Write Method**:
   - Handle all serialization manually
   - Write custom fields first
   - Write map entries in your preferred format
   - Control the exact binary layout

3. **Read Method**:
   - Handle all deserialization manually
   - Read in same order as written
   - Create and populate map instance
   - Restore custom fields

4. **Unused Methods**:
   - `onMapWrite`, `onMapRead`, `onMapCopy` are not used
   - Can throw `UnsupportedOperationException`
   - Only `write` and `read` are important

When to Use: this approach is best when

- Map has custom fields or metadata
- Special serialization format is needed
- Complete control over binary format is required
- Standard map patterns don't fit

Trade-offs

1. **Advantages**:
   - Complete control over serialization
   - Custom binary format possible
   - Simpler implementation for special cases
   - Direct handling of custom fields

2. **Disadvantages**:
   - No JIT optimization
   - Potentially lower performance
   - Manual handling of all serialization
   - More code to maintain

Remember that disabling codegen means giving up some performance optimizations that Fory provides. Only use this approach when the standard map serialization pattern doesn't meet your needs.

#### Implement Serializer for Map-like Types

Sometimes you may want to implement a serializer for a type that behaves like a map but isn't a standard Java map. This section demonstrates how to implement a serializer for such types.

The key principles for map-like type serialization are:

1. Extend `AbstractMapSerializer` for custom collection-like types
2. Enable JIT optimization with `supportCodegenHook`
3. Provide efficient element access through views
4. Maintain proper size tracking

Here's a complete example:

```java
// It's better to make it to implements the java.util.Map interface, in this way we don't have to implement such serializers by ourself.
class CustomMapLike {
    private final Object[] keyArray;
    private final Object[] valueArray;
    private final int size;

    // Constructor for creating new instance
    public CustomMapLike(int initialCapacity) {
        this.keyArray = new Object[initialCapacity];
        this.valueArray = new Object[initialCapacity];
        this.size = 0;
    }

    // Constructor for wrapping existing arrays
    public CustomMapLike(Object[] keyArray, Object[] valueArray, int size) {
        this.keyArray = keyArray;
        this.valueArray = valueArray;
        this.size = size;
    }

    public Integer get(String key) {
        for (int i = 0; i < size; i++) {
            if (key.equals(keyArray[i])) {
                return (Integer) valueArray[i];
            }
        }
        return null;
    }

    public int size() {
        return size;
    }

    public Object[] getKeyArray() {
        return keyArray;
    }

    public Object[] getValueArray() {
        return valueArray;
    }
}

class MapView extends AbstractMap<Object, Object> {
    private final Object[] keyArray;
    private final Object[] valueArray;
    private final int size;
    private int writeIndex;

    // Constructor for serialization (wrapping existing CustomMapLike)
    public MapView(CustomMapLike mapLike) {
        this.size = mapLike.size();
        this.keyArray = mapLike.getKeyArray();
        this.valueArray = mapLike.getValueArray();
    }

    // Constructor for deserialization
    public MapView(int size) {
        this.size = size;
        this.keyArray = new Object[size];
        this.valueArray = new Object[size];
    }

    @Override
    public Set<Entry<Object, Object>> entrySet() {
        return new AbstractSet<Entry<Object, Object>>() {
            @Override
            public Iterator<Entry<Object, Object>> iterator() {
                return new Iterator<Entry<Object, Object>>() {
                    private int index = 0;

                    @Override
                    public boolean hasNext() {
                        return index < size;
                    }

                    @Override
                    public Entry<Object, Object> next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        final int currentIndex = index++;
                        return new SimpleEntry<>(
                            keyArray[currentIndex],
                            valueArray[currentIndex]
                        );
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
    }

    @Override
    public Object put(Object key, Object value) {
        if (writeIndex >= size) {
            throw new IllegalStateException("Map is full");
        }
        keyArray[writeIndex] = key;
        valueArray[writeIndex] = value;
        writeIndex++;
        return null;
    }

    public Object[] getKeyArray() {
        return keyArray;
    }

    public Object[] getValueArray() {
        return valueArray;
    }

    public int size() {
        return size;
    }
}

class CustomMapLikeSerializer extends AbstractMapSerializer<CustomMapLike> {
    public CustomMapLikeSerializer(Fory fory) {
        super(fory, CustomMapLike.class, true);
    }

    @Override
    public Map onMapWrite(MemoryBuffer buffer, CustomMapLike value) {
        buffer.writeVarUint32Small7(value.size());
        // Return a zero-copy view using the same underlying arrays
        return new MapView(value);
    }

    @Override
    public Map newMap(MemoryBuffer buffer) {
        int numElements = buffer.readVarUint32Small7();
        setNumElements(numElements);
        // Create a view with new arrays for deserialization
        return new MapView(numElements);
    }

    @Override
    public CustomMapLike onMapRead(Map map) {
        MapView view = (MapView) map;
        // Just pass the arrays directly - no copying needed
        return new CustomMapLike(view.getKeyArray(), view.getValueArray(), view.size());
    }

    @Override
    public CustomMapLike onMapCopy(Map map) {
        MapView view = (MapView) map;
        // Just pass the arrays directly - no copying needed
        return new CustomMapLike(view.getKeyArray(), view.getValueArray(), view.size());
    }
}
```

### Register Custom Serializers

After implementing your custom serializer, register it with Fory:

```java
Fory fory = Fory.builder()
    .withLanguage(Language.JAVA)
    .build();

// Register map serializer
fory.registerSerializer(CustomMap.class, new CustomMapSerializer<>(fory, CustomMap.class));

// Register collection serializer
fory.registerSerializer(CustomCollection.class, new CustomCollectionSerializer<>(fory, CustomCollection.class));
```

Note that when implementing custom map or collection serializers:

1. Always extend the appropriate base class (`MapSerializer`/`AbstractMapSerializer` for maps, `CollectionSerializer`/`AbstractCollectionSerializer` for collections)
2. Consider the impact of `supportCodegenHook` on performance and functionality
3. Properly handle reference tracking if needed
4. Implement proper size management using `setNumElements` and `getAndClearNumElements` when `supportCodegenHook` is `true`

Besides registering serializes, one can also implement `java.io.Externalizable` for a class to customize serialization logic, such type will be serialized by fory `ExternalizableSerializer`.

### Security & Class Registration

`ForyBuilder#requireClassRegistration` can be used to disable class registration, this will allow to deserialize objects
unknown types,
more flexible but **may be insecure if the classes contains malicious code**.

**Do not disable class registration unless you can ensure your environment is secure**.
Malicious code in `init/equals/hashCode` can be executed when deserializing unknown/untrusted types when this option
disabled.

Class registration can not only reduce security risks, but also avoid classname serialization cost.

You can register class with API `Fory#register`.

Note that class registration order is important, serialization and deserialization peer
should have same registration order.

```java
Fory fory = xxx;
fory.register(SomeClass.class);
fory.register(SomeClass1.class, 200);
```

If you invoke `ForyBuilder#requireClassRegistration(false)` to disable class registration check,
you can set `org.apache.fory.resolver.ClassChecker` by `ClassResolver#setClassChecker` to control which classes are
allowed
for serialization. For example, you can allow classes started with `org.example.*` by:

```java
Fory fory = xxx;
fory.getClassResolver().setClassChecker(
  (classResolver, className) -> className.startsWith("org.example."));
```

```java
AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
ThreadSafeFory fory = new ThreadLocalFory(classLoader -> {
  Fory f = Fory.builder().requireClassRegistration(true).withClassLoader(classLoader).build();
  f.getClassResolver().setClassChecker(checker);
  checker.addListener(f.getClassResolver());
  return f;
});
checker.allowClass("org.example.*");
```

Fory also provided a `org.apache.fory.resolver.AllowListChecker` which is allowed/disallowed list based checker to
simplify
the customization of class check mechanism. You can use this checker or implement more sophisticated checker by
yourself.

### Register class by name

Register class by id will have better performance and smaller space overhead. But in some cases, management for a bunch
of type id is complex. In such cases, registering class by name using API
`register(Class<?> cls, String namespace, String typeName)` is recommended.

```java
fory.register(Foo.class, "demo", "Foo");
```

If there are no duplicate name for type, `namespace` can be left as empty to reduce serialized size.

**Do not use this API to register class since it will increase serialized size a lot compared to register
class by id**

### Zero-Copy Serialization

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
    .withLanguage(Language.JAVA)
    .build();

  // mvn exec:java -Dexec.mainClass="io.ray.fory.examples.ZeroCopyExample"
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

### Meta Sharing

Fory supports share type metadata (class name, field name, final field type information, etc.) between multiple
serializations in a context (ex. TCP connection), and this information will be sent to the peer during the first
serialization in the context. Based on this metadata, the peer can rebuild the same deserializer, which avoids
transmitting metadata for subsequent serializations and reduces network traffic pressure and supports type
forward/backward compatibility automatically.

```java
// Fory.builder()
//   .withLanguage(Language.JAVA)
//   .withRefTracking(false)
//   // share meta across serialization.
//   .withMetaContextShare(true)
// Not thread-safe fory.
MetaContext context = xxx;
fory.getSerializationContext().setMetaContext(context);
byte[] bytes = fory.serialize(o);
// Not thread-safe fory.
MetaContext context = xxx;
fory.getSerializationContext().setMetaContext(context);
fory.deserialize(bytes);

// Thread-safe fory
fory.setClassLoader(beanA.getClass().getClassLoader());
byte[] serialized = fory.execute(
  f -> {
    f.getSerializationContext().setMetaContext(context);
    return f.serialize(beanA);
  }
);
// thread-safe fory
fory.setClassLoader(beanA.getClass().getClassLoader());
Object newObj = fory.execute(
  f -> {
    f.getSerializationContext().setMetaContext(context);
    return f.deserialize(serialized);
  }
);
```

Note that `MetaContext` is not thread-safe and cannot be reused across instances of fory or multiple threads.
In cases of multi-threading, a separate `MetaContext` must be created for each fory instance.

### Deserialize non-existent classes

Fory support deserializing non-existent classes, this feature can be enabled
by `ForyBuilder#deserializeNonexistentClass(true)`. When enabled, and metadata sharing enabled, Fory will store
the deserialized data of this type in a lazy subclass of Map. By using the lazy map implemented by Fory, the rebalance
cost of filling map during deserialization can be avoided, which further improves performance. If this data is sent to
another process and the class exists in this process, the data will be deserialized into the object of this type without
losing any information.

If metadata sharing is not enabled, the new class data will be skipped and an `NonexistentSkipClass` stub object will be
returned.

### Copy/Map object from one type to another type

Fory support mapping object from one type to another type.

> Notes:
>
> 1. This mapping will execute a deep copy, all mapped fields are serialized into binary and
>    deserialized from that binary to map into another type.
> 2. All struct types must be registered with same ID, otherwise Fory can not mapping to correct struct type.
>    Be careful when you use `Fory#register(Class)`, because fory will allocate an auto-grown ID which might be
>    inconsistent if you register classes with different order between Fory instance.

```java
public class StructMappingExample {
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

  static ThreadSafeFory fory1 = Fory.builder()
    .withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();
  static ThreadSafeFory fory2 = Fory.builder()
    .withCompatibleMode(CompatibleMode.COMPATIBLE).buildThreadSafeFory();

  static {
    fory1.register(Struct1.class);
    fory2.register(Struct2.class);
  }

  public static void main(String[] args) {
    Struct1 struct1 = new Struct1(10, "abc");
    Struct2 struct2 = (Struct2) fory2.deserialize(fory1.serialize(struct1));
    Assert.assertEquals(struct2.f1, struct1.f1);
    Assert.assertEquals(struct2.f2, struct1.f2);
    struct1 = (Struct1) fory1.deserialize(fory2.serialize(struct2));
    Assert.assertEquals(struct1.f1, struct2.f1);
    Assert.assertEquals(struct1.f2, struct2.f2);
  }
}
```

## Migration

### JDK migration

If you use jdk serialization before, and you can't upgrade your client and server at the same time, which is common for
online application. Fory provided an util method `org.apache.fory.serializer.JavaSerializer.serializedByJDK` to check
whether
the binary are generated by jdk serialization, you use following pattern to make exiting serialization protocol-aware,
then upgrade serialization to fory in an async rolling-up way:

```java
if (JavaSerializer.serializedByJDK(bytes)) {
  ObjectInputStream objectInputStream=xxx;
  return objectInputStream.readObject();
} else {
  return fory.deserialize(bytes);
}
```

### Upgrade fory

Currently binary compatibility is ensured for minor versions only. For example, if you are using fory`v0.2.0`, binary
compatibility will
be provided if you upgrade to fory `v0.2.1`. But if upgrade to fory `v0.4.1`, no binary compatibility are ensured.
Most of the time there is no need to upgrade fory to newer major version, the current version is fast and compact
enough,
and we provide some minor fix for recent older versions.

But if you do want to upgrade fory for better performance and smaller size, you need to write fory version as header to
serialized data
using code like following to keep binary compatibility:

```java
MemoryBuffer buffer = xxx;
buffer.writeVarInt32(2);
fory.serialize(buffer, obj);
```

Then for deserialization, you need:

```java
MemoryBuffer buffer = xxx;
int foryVersion = buffer.readVarInt32();
Fory fory = getFory(foryVersion);
fory.deserialize(buffer);
```

`getFory` is a method to load corresponding fory, you can shade and relocate different version of fory to different
package, and load fory by version.

If you upgrade fory by minor version, or you won't have data serialized by older fory, you can upgrade fory directly,
no need to `versioning` the data.

## Trouble shooting

### Class inconsistency and class version check

If you create fory without setting `CompatibleMode` to `org.apache.fory.config.CompatibleMode.COMPATIBLE`, and you got a
strange
serialization error, it may be caused by class inconsistency between serialization peer and deserialization peer.

In such cases, you can invoke `ForyBuilder#withClassVersionCheck` to create fory to validate it, if deserialization
throws `org.apache.fory.exception.ClassNotCompatibleException`, it shows class are inconsistent, and you should create
fory with
`ForyBuilder#withCompaibleMode(CompatibleMode.COMPATIBLE)`.

`CompatibleMode.COMPATIBLE` has more performance and space cost, do not set it by default if your classes are always
consistent between serialization and deserialization.

### Deserialize POJO into another type

Fory allows you to serialize one POJO and deserialize it into a different POJO. The different POJO means the schema inconsistency. Users must to configure Fory with
`CompatibleMode` set to `org.apache.fory.config.CompatibleMode.COMPATIBLE`.

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
    Struct2 struct2 = (Struct2) fory.deserializeJavaObject(bytes, Struct2.class);
  }
}
```

### Use wrong API for deserialization

If you serialize an object by invoking `Fory#serialize`, you should invoke `Fory#deserialize` for deserialization
instead of
`Fory#deserializeJavaObject`.

If you serialize an object by invoking `Fory#serializeJavaObject`, you should invoke `Fory#deserializeJavaObject` for
deserialization instead of `Fory#deserializeJavaObjectAndClass`/`Fory#deserialize`.

If you serialize an object by invoking `Fory#serializeJavaObjectAndClass`, you should
invoke `Fory#deserializeJavaObjectAndClass` for deserialization instead
of `Fory#deserializeJavaObject`/`Fory#deserialize`.
