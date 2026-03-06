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

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GuavaCollectionSerializersTest extends ForyTestBase {

  @Test(dataProvider = "trackingRefFory")
  public void testImmutableListSerializer(Fory fory) {
    serDe(fory, ImmutableList.of(1));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableList.of(1).getClass()),
        GuavaCollectionSerializers.ImmutableListSerializer.class);
    serDe(fory, ImmutableList.of(1, 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableList.of(1, 2).getClass()),
        GuavaCollectionSerializers.RegularImmutableListSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutableListSerializerCopy(Fory fory) {
    copyCheck(fory, ImmutableList.of(1));
    copyCheck(fory, ImmutableList.of(1, 2));
  }

  @Test(dataProvider = "trackingRefFory")
  public void testImmutableSetSerializer(Fory fory) {
    serDe(fory, ImmutableSet.of(1));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableSet.of(1).getClass()),
        GuavaCollectionSerializers.ImmutableSetSerializer.class);
    serDe(fory, ImmutableSet.of(1, 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableSet.of(1, 2).getClass()),
        GuavaCollectionSerializers.ImmutableSetSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutableSetSerializerCopy(Fory fory) {
    copyCheck(fory, ImmutableSet.of(1));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableSet.of(1).getClass()),
        GuavaCollectionSerializers.ImmutableSetSerializer.class);
    copyCheck(fory, ImmutableSet.of(1, 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableSet.of(1, 2).getClass()),
        GuavaCollectionSerializers.ImmutableSetSerializer.class);
  }

  @Test(dataProvider = "trackingRefFory")
  public void testImmutableSortedSetSerializer(Fory fory) {
    serDe(fory, ImmutableSortedSet.of(1, 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableSortedSet.of(1, 2).getClass()),
        GuavaCollectionSerializers.ImmutableSortedSetSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutableSortedSetSerializerCopy(Fory fory) {
    copyCheck(fory, ImmutableSortedSet.of(1, 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableSortedSet.of(1, 2).getClass()),
        GuavaCollectionSerializers.ImmutableSortedSetSerializer.class);
  }

  @Test(dataProvider = "trackingRefFory")
  public void testImmutableMapSerializer(Fory fory) {
    serDe(fory, ImmutableMap.of("k1", 1, "k2", 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableMap.of("k1", 1, "k2", 2).getClass()),
        GuavaCollectionSerializers.ImmutableMapSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutableMapSerializerCopy(Fory fory) {
    copyCheck(fory, ImmutableMap.of("k1", 1, "k2", 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableMap.of("k1", 1, "k2", 2).getClass()),
        GuavaCollectionSerializers.ImmutableMapSerializer.class);
  }

  @Test(dataProvider = "trackingRefFory")
  public void testImmutableBiMapSerializer(Fory fory) {
    serDe(fory, ImmutableBiMap.of("k1", 1));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableBiMap.of("k1", 1).getClass()),
        GuavaCollectionSerializers.ImmutableBiMapSerializer.class);
    serDe(fory, ImmutableBiMap.of("k1", 1, "k2", 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableBiMap.of("k1", 1, "k2", 2).getClass()),
        GuavaCollectionSerializers.ImmutableBiMapSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutableBiMapSerializerCopy(Fory fory) {
    copyCheck(fory, ImmutableBiMap.of("k1", 1));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableBiMap.of("k1", 1).getClass()),
        GuavaCollectionSerializers.ImmutableBiMapSerializer.class);
    copyCheck(fory, ImmutableBiMap.of("k1", 1, "k2", 2));
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(ImmutableBiMap.of("k1", 1, "k2", 2).getClass()),
        GuavaCollectionSerializers.ImmutableBiMapSerializer.class);
  }

  @Test(dataProvider = "trackingRefFory")
  public void testImmutableSortedMapSerializer(Fory fory) {
    serDe(fory, ImmutableSortedMap.of("k1", 1, "k2", 2));
    Assert.assertEquals(
        fory.getTypeResolver()
            .getSerializerClass(ImmutableSortedMap.of("k1", 1, "k2", 2).getClass()),
        GuavaCollectionSerializers.ImmutableSortedMapSerializer.class);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutableSortedMapSerializerCopy(Fory fory) {
    copyCheck(fory, ImmutableSortedMap.of("k1", 1, "k2", 2));
    Assert.assertEquals(
        fory.getTypeResolver()
            .getSerializerClass(ImmutableSortedMap.of("k1", 1, "k2", 2).getClass()),
        GuavaCollectionSerializers.ImmutableSortedMapSerializer.class);
  }

  @Test
  public void tesXlangSerialize() {
    Fory fory = Fory.builder().withLanguage(Language.XLANG).build();
    serDe(fory, ImmutableBiMap.of());
    serDe(fory, ImmutableBiMap.of(1, 2));
    serDe(fory, ImmutableBiMap.of(1, 2, 3, 4));

    serDe(fory, ImmutableList.of());
    serDe(fory, ImmutableList.of(1));
    serDe(fory, ImmutableList.of(1, 2));

    serDe(fory, ImmutableSet.of());
    serDe(fory, ImmutableSet.of(1));
    serDe(fory, ImmutableSet.of(1, 2, 3, 4));
  }

  @Data
  @AllArgsConstructor
  public static class Pojo {
    List<List<Object>> data;
  }

  @Test(dataProvider = "javaFory")
  void testNestedRefTracking(Fory fory) {
    Pojo pojo = new Pojo(ImmutableList.of(ImmutableList.of(1, 2), ImmutableList.of(2, 2)));
    byte[] bytes = fory.serialize(pojo);
    Pojo deserializedPojo = (Pojo) fory.deserialize(bytes);
    System.out.println(deserializedPojo);
  }

  @Test(dataProvider = "foryCopyConfig")
  void testNestedRefTrackingCopy(Fory fory) {
    Pojo pojo = new Pojo(ImmutableList.of(ImmutableList.of(1, 2), ImmutableList.of(2, 2)));
    copyCheck(fory, pojo);
  }
}
