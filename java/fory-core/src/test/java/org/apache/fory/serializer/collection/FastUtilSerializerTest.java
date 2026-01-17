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

package org.apache.fory.serializer.collection;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

public class FastUtilSerializerTest extends ForyTestBase {

  @Test(dataProvider = "referenceTrackingConfig")
  public void testShortOpenHashSet(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.register(fory, ShortOpenHashSet.class);

    ShortOpenHashSet set = new ShortOpenHashSet();
    set.add((short) 1);
    set.add((short) 2);
    set.add((short) 3);
    set.add((short) 100);
    set.add((short) 200);

    byte[] bytes = fory.serialize(set);
    ShortOpenHashSet deserialized = fory.deserialize(bytes, ShortOpenHashSet.class);

    Assert.assertEquals(deserialized, set);
    Assert.assertEquals(deserialized.size(), set.size());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testShortArrayList(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.register(fory, ShortArrayList.class);

    ShortArrayList list = new ShortArrayList();
    list.add((short) 1);
    list.add((short) 2);
    list.add((short) 3);
    list.add((short) 100);
    list.add((short) 200);

    byte[] bytes = fory.serialize(list);
    ShortArrayList deserialized = fory.deserialize(bytes, ShortArrayList.class);

    Assert.assertEquals(deserialized, list);
    Assert.assertEquals(deserialized.size(), list.size());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testIntArrayList(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.register(fory, IntArrayList.class);

    IntArrayList list = new IntArrayList();
    list.add(1);
    list.add(2);
    list.add(3);
    list.add(100);
    list.add(200);

    byte[] bytes = fory.serialize(list);
    IntArrayList deserialized = fory.deserialize(bytes, IntArrayList.class);

    Assert.assertEquals(deserialized, list);
    Assert.assertEquals(deserialized.size(), list.size());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testLongArrayList(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.register(fory, LongArrayList.class);

    LongArrayList list = new LongArrayList();
    list.add(1L);
    list.add(2L);
    list.add(3L);
    list.add(100L);
    list.add(200L);

    byte[] bytes = fory.serialize(list);
    LongArrayList deserialized = fory.deserialize(bytes, LongArrayList.class);

    Assert.assertEquals(deserialized, list);
    Assert.assertEquals(deserialized.size(), list.size());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testByteArrayList(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.register(fory, ByteArrayList.class);

    ByteArrayList list = new ByteArrayList();
    list.add((byte) 1);
    list.add((byte) 2);
    list.add((byte) 3);
    list.add((byte) 100);

    byte[] bytes = fory.serialize(list);
    ByteArrayList deserialized = fory.deserialize(bytes, ByteArrayList.class);

    Assert.assertEquals(deserialized, list);
    Assert.assertEquals(deserialized.size(), list.size());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testObject2ObjectOpenHashMap(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.register(fory, Object2ObjectOpenHashMap.class);

    Object2ObjectOpenHashMap<String, String> map = new Object2ObjectOpenHashMap<>();
    map.put("key1", "value1");
    map.put("key2", "value2");
    map.put("key3", "value3");

    byte[] bytes = fory.serialize(map);
    Object2ObjectOpenHashMap<String, String> deserialized =
        fory.deserialize(bytes, Object2ObjectOpenHashMap.class);

    Assert.assertEquals(deserialized, map);
    Assert.assertEquals(deserialized.size(), map.size());
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testRegisterAll(boolean referenceTrackingConfig) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(referenceTrackingConfig)
            .requireClassRegistration(false)
            .build();

    FastUtilSerializer.registerAll(fory);

    // Test that registered types can be serialized
    ShortOpenHashSet set = new ShortOpenHashSet();
    set.add((short) 1);
    set.add((short) 2);

    byte[] bytes = fory.serialize(set);
    ShortOpenHashSet deserialized = fory.deserialize(bytes, ShortOpenHashSet.class);
    Assert.assertEquals(deserialized, set);
  }

  @Test
  public void testEmptyShortOpenHashSet() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    FastUtilSerializer.register(fory, ShortOpenHashSet.class);

    ShortOpenHashSet emptySet = new ShortOpenHashSet();
    serDeCheck(fory, emptySet);
  }

  @Test
  public void testEmptyShortArrayList() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    FastUtilSerializer.register(fory, ShortArrayList.class);

    ShortArrayList emptyList = new ShortArrayList();
    serDeCheck(fory, emptyList);
  }

  @Test
  public void testLargeShortOpenHashSet() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();

    FastUtilSerializer.register(fory, ShortOpenHashSet.class);

    ShortOpenHashSet largeSet = new ShortOpenHashSet();
    for (int i = 0; i < 1000; i++) {
      largeSet.add((short) i);
    }

    serDeCheck(fory, largeSet);
  }
}
