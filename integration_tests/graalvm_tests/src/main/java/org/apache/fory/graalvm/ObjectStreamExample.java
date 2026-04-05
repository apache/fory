/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.fory.graalvm;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.fory.Fory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.serializer.ObjectStreamSerializer;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.apache.fory.serializer.collection.MapSerializers;

public class ObjectStreamExample extends AbstractMap<Integer, Integer> {
  private static final Fory FORY =
      Fory.builder()
          .withName(ObjectStreamExample.class.getName() + "_compatible_async")
          .registerGuavaTypes(false)
          .withRefTracking(true)
          .withCodegen(true)
          .withCompatibleMode(CompatibleMode.COMPATIBLE)
          .withAsyncCompilation(true)
          .build();

  static {
    FORY.register(AsyncLayerJitContainer.class);
    FORY.register(AsyncTreeSetSubclass.class);
    FORY.register(AsyncTreeMapSubclass.class);
    FORY.registerSerializer(
        AsyncLayerJitContainer.class,
        new ObjectStreamSerializer(FORY, AsyncLayerJitContainer.class));
    FORY.registerSerializer(
        AsyncTreeSetSubclass.class,
        new CollectionSerializers.JDKCompatibleCollectionSerializer<>(
            FORY, AsyncTreeSetSubclass.class));
    FORY.registerSerializer(
        AsyncTreeMapSubclass.class,
        new MapSerializers.JDKCompatibleMapSerializer<>(FORY, AsyncTreeMapSubclass.class));
    assertSerializerClass(
        AsyncTreeSetSubclass.class, CollectionSerializers.JDKCompatibleCollectionSerializer.class);
    assertSerializerClass(
        AsyncTreeMapSubclass.class, MapSerializers.JDKCompatibleMapSerializer.class);
    FORY.register(ObjectStreamExample.class);
    FORY.ensureSerializersCompiled();
  }

  final int[] ints = new int[10];

  public static void main(String[] args) {
    AsyncTreeSetSubclass values = new AsyncTreeSetSubclass();
    values.add("one");
    values.add("two");
    AsyncTreeMapSubclass attributes = new AsyncTreeMapSubclass();
    attributes.put("alpha", "A");
    attributes.put("beta", "B");
    roundTrip(new AsyncLayerJitContainer("container", values, attributes));
    roundTrip(values);
    roundTrip(attributes);

    FORY.reset();
    byte[] bytes = FORY.serialize(new ObjectStreamExample());
    FORY.reset();
    ObjectStreamExample o = (ObjectStreamExample) FORY.deserialize(bytes);
    System.out.println(Arrays.toString(o.ints));
  }

  private static void roundTrip(Object value) {
    FORY.reset();
    byte[] bytes = FORY.serialize(value);
    FORY.reset();
    Object result = FORY.deserialize(bytes);
    if (!value.equals(result)) {
      throw new IllegalStateException(
          "ObjectStreamExample round-trip mismatch: " + value + " != " + result);
    }
  }

  private static void assertSerializerClass(
      Class<?> type, Class<? extends org.apache.fory.serializer.Serializer> serializerClass) {
    Class<?> actual = FORY.getTypeResolver().getSerializerClass(type);
    if (actual != serializerClass) {
      throw new IllegalStateException(
          "Unexpected serializer for " + type.getName() + ": " + actual.getName());
    }
  }

  @Override
  public Set<Entry<Integer, Integer>> entrySet() {
    HashSet<Entry<Integer, Integer>> set = new HashSet<>();
    for (int i = 0; i < ints.length; i++) {
      set.add(new AbstractMap.SimpleEntry<>(i, ints[i]));
    }
    return set;
  }

  public static class AsyncTreeSetSubclass extends TreeSet<String> {
    public AsyncTreeSetSubclass() {}
  }

  public static class AsyncTreeMapSubclass extends TreeMap<String, String> {
    public AsyncTreeMapSubclass() {}
  }

  public static class AsyncLayerJitContainer implements Serializable {
    private final String name;
    private final AsyncTreeSetSubclass values;
    private final AsyncTreeMapSubclass attributes;

    public AsyncLayerJitContainer(
        String name, AsyncTreeSetSubclass values, AsyncTreeMapSubclass attributes) {
      this.name = name;
      this.values = values;
      this.attributes = attributes;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof AsyncLayerJitContainer)) {
        return false;
      }
      AsyncLayerJitContainer other = (AsyncLayerJitContainer) obj;
      return name.equals(other.name)
          && values.equals(other.values)
          && attributes.equals(other.attributes);
    }

    @Override
    public int hashCode() {
      return name.hashCode() * 31 * 31 + values.hashCode() * 31 + attributes.hashCode();
    }
  }
}
