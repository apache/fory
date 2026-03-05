---
title: Custom Serializers
sidebar_position: 4
id: custom_serializers
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

This page covers how to implement custom serializers for your types.

## Basic Custom Serializer

In some cases, you may want to implement a serializer for your type, especially for classes that customize serialization using JDK `writeObject/writeReplace/readObject/readResolve`, which is very inefficient.

For example, if you don't want the following `Foo#writeObject` to be invoked, you can implement a custom serializer:

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

### Register the Serializer

```java
Fory fory = getFory();
fory.registerSerializer(Foo.class, new FooSerializer(fory));
```

Besides registering serializers, you can also implement `java.io.Externalizable` for a class to customize serialization logic. Such types will be serialized by Fory's `ExternalizableSerializer`.

## Collection Serializer

When implementing a serializer for a custom Collection type, you must extend `CollectionSerializer` or `CollectionLikeSerializer`. The key difference is that `CollectionLikeSerializer` can serialize a class which has a collection-like structure but is not a Java Collection subtype.

### supportCodegenHook Parameter

This special parameter controls serialization behavior:

**When `true`:**

- Enables optimized access to collection elements and JIT compilation for better performance
- Direct serialization invocation and inline for collection items without dynamic serializer dispatch cost
- Better performance for standard collection types
- Recommended for most collections

**When `false`:**

- Uses interface-based element access and dynamic serializer dispatch for elements (higher cost)
- More flexible for custom collection types
- Required when collection has special serialization needs
- Handles complex collection implementations

### Collection Serializer with JIT Support

When implementing a Collection serializer with JIT support, leverage Fory's existing binary format and collection serialization infrastructure:

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

Note: Invoke `setNumElements` when implementing `newCollection` to let Fory know how many elements to deserialize.

### Custom Collection Serializer without JIT

For collections that use primitive arrays or have special requirements, implement a serializer with JIT disabled:

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

class IntListSerializer extends CollectionLikeSerializer<IntList> {
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

**When to use this approach:**

- Working with primitive types
- Need maximum performance
- Want to minimize memory overhead
- Have special serialization requirements

### Collection-like Type Serializer

For types that behave like collections but aren't standard Java Collections:

```java
class CustomCollectionLike {
    private final Object[] elements;
    private final int size;

    public CustomCollectionLike(int size) {
        this.elements = new Object[size];
        this.size = 0;
    }

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

class CollectionView extends AbstractCollection<Object> {
    private final Object[] elements;
    private final int size;
    private int writeIndex;

    public CollectionView(CustomCollectionLike collection) {
        this.elements = collection.getElements();
        this.size = collection.size();
    }

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

class CustomCollectionSerializer extends CollectionLikeSerializer<CustomCollectionLike> {
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

## Map Serializer

When implementing a serializer for a custom Map type, extend `MapSerializer` or `MapLikeSerializer`. The key difference is that `MapLikeSerializer` can serialize a class which has a map-like structure but is not a Java Map subtype.

### Map Serializer with JIT Support

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

Note: Invoke `setNumElements` when implementing `newMap` to let Fory know how many elements to deserialize.

### Custom Map Serializer without JIT

For complete control over the serialization process:

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

class FixedValueMapSerializer extends MapLikeSerializer<FixedValueMap> {
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

### Map-like Type Serializer

For types that behave like maps but aren't standard Java Maps:

```java
class CustomMapLike {
    private final Object[] keyArray;
    private final Object[] valueArray;
    private final int size;

    public CustomMapLike(int initialCapacity) {
        this.keyArray = new Object[initialCapacity];
        this.valueArray = new Object[initialCapacity];
        this.size = 0;
    }

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

    public MapView(CustomMapLike mapLike) {
        this.size = mapLike.size();
        this.keyArray = mapLike.getKeyArray();
        this.valueArray = mapLike.getValueArray();
    }

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

class CustomMapLikeSerializer extends MapLikeSerializer<CustomMapLike> {
    public CustomMapLikeSerializer(Fory fory) {
        super(fory, CustomMapLike.class, true);
    }

    @Override
    public Map onMapWrite(MemoryBuffer buffer, CustomMapLike value) {
        buffer.writeVarUint32Small7(value.size());
        return new MapView(value);
    }

    @Override
    public Map newMap(MemoryBuffer buffer) {
        int numElements = buffer.readVarUint32Small7();
        setNumElements(numElements);
        return new MapView(numElements);
    }

    @Override
    public CustomMapLike onMapRead(Map map) {
        MapView view = (MapView) map;
        return new CustomMapLike(view.getKeyArray(), view.getValueArray(), view.size());
    }

    @Override
    public CustomMapLike onMapCopy(Map map) {
        MapView view = (MapView) map;
        return new CustomMapLike(view.getKeyArray(), view.getValueArray(), view.size());
    }
}
```

## Registering Custom Serializers

```java
Fory fory = Fory.builder()
    .withLanguage(Language.JAVA)
    .build();

// Register map serializer
fory.registerSerializer(CustomMap.class, new CustomMapSerializer<>(fory, CustomMap.class));

// Register collection serializer
fory.registerSerializer(CustomCollection.class, new CustomCollectionSerializer<>(fory, CustomCollection.class));
```

## Key Points

When implementing custom map or collection serializers:

1. Always extend the appropriate base class (`MapSerializer`/`MapLikeSerializer` for maps, `CollectionSerializer`/`CollectionLikeSerializer` for collections)
2. Consider the impact of `supportCodegenHook` on performance and functionality
3. Properly handle reference tracking if needed
4. Implement proper size management using `setNumElements` and `getAndClearNumElements` when `supportCodegenHook` is `true`

## Related Topics

- [Type Registration](type-registration.md) - Register serializers
- [Schema Evolution](schema-evolution.md) - Compatible mode considerations
- [Configuration](configuration.md) - Serialization options
