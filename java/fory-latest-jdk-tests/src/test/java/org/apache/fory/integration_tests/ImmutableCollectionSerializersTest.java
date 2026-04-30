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

package org.apache.fory.integration_tests;

import static org.apache.fory.integration_tests.TestUtils.serDeCheck;

import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.test.bean.CollectionFields;
import org.apache.fory.test.bean.MapFields;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ImmutableCollectionSerializersTest {

  @DataProvider
  public static Object[][] codegen() {
    return new Object[][] {{false}, {true}};
  }

  @Test(dataProvider = "codegen")
  public void testImmutableCollections(boolean codegen) {
    Fory fory = Fory.builder().withCodegen(codegen).build();
    serDeCheck(fory, List.of());
    serDeCheck(fory, List.of("A"));
    serDeCheck(fory, List.of("A", "B"));
    serDeCheck(fory, List.of("A", "B", "C"));
    serDeCheck(fory, List.of("A", "B", "C", "D"));
    serDeCheck(fory, List.of("A", "B", 1, 2));
    serDeCheck(fory, Set.of());
    serDeCheck(fory, Set.of("A"));
    serDeCheck(fory, Set.of("A", "B"));
    serDeCheck(fory, Set.of("A", "B", "C"));
    serDeCheck(fory, Set.of("A", "B", "C", "D"));
    serDeCheck(fory, Set.of("A", "B", 1, 2));
    serDeCheck(fory, Map.of());
    serDeCheck(fory, Map.of("A", "B"));
    serDeCheck(fory, Map.of("A", "B", 1, 2));
  }

  @Test(dataProvider = "codegen")
  public void testImmutableCollectionStruct(boolean codegen) {
    Fory fory = Fory.builder().withCodegen(codegen).build();
    fory.register(MapFields.class);
    MapFields mapFields = new MapFields();
    mapFields.map = Map.of();
    mapFields.map2 = Map.of("k", 1);
    mapFields.mapKeyFinal = Map.of("k", 1);
    mapFields.mapValueFinal = Map.of("k", 2, "k2", 2, "k3", 3);
    mapFields.emptyMap = Map.of();
    mapFields.singletonMap = Map.of(1, 2);
    serDeCheck(fory, mapFields);
  }

  @Test
  public void testImmutableMapStruct() {
    Fory fory = Fory.builder().build();
    fory.register(CollectionFields.class);
    CollectionFields collectionFields = new CollectionFields();
    collectionFields.collection = List.of();
    collectionFields.collection2 = List.of(1);
    collectionFields.collection3 = List.of(1, 2, 3, 4);
    collectionFields.randomAccessList = List.of("1", "2");
    collectionFields.randomAccessList2 = List.of("1", "2", "3", "4");
    collectionFields.set = Set.of();
    collectionFields.set2 = Set.of("1", "2");
    collectionFields.set3 = Set.of("1", "2", "3", "4");
    collectionFields.map = Map.of("1", "2");
    collectionFields.map2 = Map.of("1", "2", "3", "4");
    serDeCheck(fory, collectionFields);
  }

  @Data
  @AllArgsConstructor
  public static class Pojo {
    List<List<Object>> data;
  }

  @DataProvider
  public static Object[][] refTrackingAndCodegen() {
    return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
  }

  @Test(dataProvider = "refTrackingAndCodegen")
  void testNestedRefTracking(boolean trackingRef, boolean codegen) {
    Pojo pojo = new Pojo(List.of(List.of(1, 2), List.of(2, 2)));
    ThreadSafeFory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCodegen(codegen)
            .withRefTracking(trackingRef)
            .buildThreadSafeFory();

    byte[] bytes = fory.serialize(pojo);
    Pojo deserializedPojo = (Pojo) fory.deserialize(bytes);
    Assert.assertEquals(deserializedPojo, pojo);
  }
}
