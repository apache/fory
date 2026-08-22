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

package org.apache.fory.serializer;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.annotation.ForyField;
import org.apache.fory.annotation.ForyStruct;
import org.apache.fory.builder.ObjectCodecBuilder;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.resolver.TypeResolver;
import org.testng.annotations.Test;

public class UnbackedContainerItemsTest {
  static final class Empty {}

  public static final class Holder {
    public List<Empty> values;
  }

  public static final class PositiveHolder {
    public List<Integer> values;
  }

  public static final class PositiveMapHolder {
    public Map<Integer, Empty> values;
  }

  @ForyStruct
  public static class PositiveStruct {
    @ForyField(id = 1)
    public int value;
  }

  @ForyStruct
  public static class PositiveStructHolder {
    @ForyField(id = 1)
    public List<PositiveStruct> values;
  }

  @ForyStruct
  public static class PositiveContainerStruct {
    @ForyField(id = 1)
    public List<Empty> values;
  }

  public static final class MapHolder {
    public Map<Empty, Empty> values;
  }

  static final class EmptySerializer extends Serializer<Empty> {
    EmptySerializer(TypeResolver typeResolver) {
      super(typeResolver.getConfig(), Empty.class, false, false);
    }

    @Override
    public void write(WriteContext writeContext, Empty value) {}

    @Override
    public Empty read(ReadContext readContext) {
      return new Empty();
    }
  }

  @Test
  public void testConfiguration() {
    assertEquals(Fory.builder().build().getConfig().maxUnbackedContainerItems(), 8192);
    assertThrows(
        IllegalArgumentException.class, () -> Fory.builder().withMaxUnbackedContainerItems(-1));
  }

  @Test
  public void testCollectionBudgetAndRootReset() {
    Fory fory = newFory(4);
    List<Empty> allowed = emptyList(4);
    assertEquals(((List<?>) fory.deserialize(fory.serialize(allowed))).size(), 4);

    byte[] excessive = fory.serialize(emptyList(5));
    assertThrows(RuntimeException.class, () -> fory.deserialize(excessive));
    assertEquals(((List<?>) fory.deserialize(fory.serialize(allowed))).size(), 4);
  }

  @Test
  public void testMapEntryBudget() {
    Fory fory = newFory(3);
    Map<Empty, Empty> values = new LinkedHashMap<>();
    for (int i = 0; i < 4; i++) {
      values.put(new Empty(), new Empty());
    }
    byte[] bytes = fory.serialize(values);
    assertThrows(RuntimeException.class, () -> fory.deserialize(bytes));
  }

  @Test
  public void testGeneratedCollectionRead() {
    Fory fory = newFory(4);
    Holder holder = new Holder();
    holder.values = emptyList(5);
    byte[] bytes = fory.serialize(holder);
    assertThrows(RuntimeException.class, () -> fory.deserialize(bytes));
  }

  @Test
  public void testGeneratedMapRead() {
    Fory fory = newFory(3);
    MapHolder holder = new MapHolder();
    holder.values = new LinkedHashMap<>();
    for (int i = 0; i < 4; i++) {
      holder.values.put(new Empty(), new Empty());
    }
    byte[] bytes = fory.serialize(holder);
    assertThrows(RuntimeException.class, () -> fory.deserialize(bytes));
  }

  @Test
  public void testPositiveGeneratedLoopHasNoBudgetCheck() {
    Fory fory = Fory.builder().withXlang(false).requireClassRegistration(false).build();
    String collectionCode = new ObjectCodecBuilder(PositiveHolder.class, fory).genCode();
    assertFalse(collectionCode.contains("reserveUnbackedContainerItems"));
    assertTrue(collectionCode.contains(".newCollection(readContext1, true)"), collectionCode);

    String mapCode = new ObjectCodecBuilder(PositiveMapHolder.class, fory).genCode();
    assertFalse(mapCode.contains("reserveUnbackedContainerItems"));
    assertTrue(mapCode.contains(".newMap(readContext2, true)"), mapCode);
  }

  @Test
  public void testStructProgressProof() {
    Fory fory = Fory.builder().withXlang(true).requireClassRegistration(false).build();
    fory.register(Empty.class, "test.empty");
    fory.register(PositiveStruct.class, "test.positive_struct");
    fory.register(PositiveStructHolder.class, "test.positive_struct_holder");
    fory.register(PositiveContainerStruct.class, "test.positive_container_struct");

    assertTrue(
        fory.getTypeResolver().getTypeDef(PositiveStruct.class, true).readDataAlwaysAdvances());
    assertTrue(
        fory.getTypeResolver()
            .getTypeDef(PositiveContainerStruct.class, true)
            .readDataAlwaysAdvances());
    assertFalse(fory.getTypeResolver().getTypeDef(Empty.class, true).readDataAlwaysAdvances());
    String code = new ObjectCodecBuilder(PositiveStructHolder.class, fory).genCode();
    int remoteRead = code.indexOf("private void remoteSameTypeElemsRead(");
    int dynamicProgressCheck = code.indexOf(".readDataAlwaysAdvances()", remoteRead);
    int readCollection = code.indexOf("private Object readCollection(");
    int declaredRead = code.indexOf("& 4) == 4", readCollection);
    int remoteCall = code.indexOf("this.remoteSameTypeElemsRead(", declaredRead);
    assertTrue(remoteRead >= 0 && dynamicProgressCheck > remoteRead, code);
    assertTrue(
        readCollection >= 0 && declaredRead > readCollection && remoteCall > declaredRead, code);
    String declaredPath = code.substring(declaredRead, remoteCall);
    assertFalse(declaredPath.contains("readDataAlwaysAdvances"), declaredPath);
    assertFalse(declaredPath.contains("reserveUnbackedContainerItems"), declaredPath);
  }

  private static Fory newFory(int maxItems) {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .withMaxUnbackedContainerItems(maxItems)
            .build();
    fory.registerSerializer(Empty.class, new EmptySerializer(fory.getTypeResolver()));
    return fory;
  }

  private static List<Empty> emptyList(int size) {
    List<Empty> values = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      values.add(new Empty());
    }
    return values;
  }
}
