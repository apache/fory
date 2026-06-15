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

package org.apache.fory.xlang;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.annotation.ArrayType;
import org.apache.fory.annotation.Int32Type;
import org.apache.fory.collection.Int32List;
import org.apache.fory.config.Int32Encoding;
import org.apache.fory.exception.DeserializationException;
import org.apache.fory.test.bean.BeanB;
import org.apache.fory.xlang.PyCrossLanguageTest.Bar;
import org.apache.fory.xlang.PyCrossLanguageTest.Foo;
import org.testng.annotations.Test;

public class MetaShareXlangTest extends ForyTestBase {

  @Test
  public void testMetaShareBasic() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(false).build();
    fory.register(Foo.class, "example.foo");
    fory.register(Bar.class, "example.bar");
    serDeCheck(fory, Bar.create());
    serDeCheck(fory, Foo.create());
  }

  @Test
  public void testMetaShareComplex1() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(false).build();
    fory.register(BeanB.class, "example.b");
    serDeCheck(fory, BeanB.createBeanB(2));
  }

  @Data
  static class MDArrayFieldStruct {
    int[][] arr;
  }

  @Test
  public void testMDArrayField() {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(false).build();
    fory.register(MDArrayFieldStruct.class, "example.a");
    MDArrayFieldStruct s = new MDArrayFieldStruct();
    s.arr = new int[][] {{1, 2}, {3, 4}};
    serDeCheck(fory, s);
  }

  @Data
  static class DirectListField {
    @Int32Type(encoding = Int32Encoding.FIXED)
    Int32List values;
  }

  @Data
  static class DirectNullableListField {
    List<Integer> values;
  }

  @Data
  static class DirectArrayField {
    int[] values;
  }

  @Data
  static class DirectAnnotatedArrayField {
    @ArrayType List<Integer> values;
  }

  @Data
  static class DirectCollectionField {
    Collection<@Int32Type(encoding = Int32Encoding.FIXED) Integer> values;
  }

  @Data
  static class NestedListField {
    List<List<Integer>> values;
  }

  @Data
  static class NestedArrayElementField {
    List<int[]> values;
  }

  @Data
  static class NestedSetListField {
    Set<List<Integer>> values;
  }

  @Data
  static class NestedSetArrayElementField {
    Set<int[]> values;
  }

  @Test
  public void testTopLevelListArrayCompatibleRead() {
    Fory listFory = compatibleFory(DirectListField.class);
    DirectListField listStruct = new DirectListField();
    listStruct.values = new Int32List(new int[] {1, 2, 3});
    byte[] listBytes = listFory.serialize(listStruct);

    Fory arrayFory = compatibleFory(DirectArrayField.class);
    DirectArrayField arrayStruct = (DirectArrayField) arrayFory.deserialize(listBytes);
    assertTrue(Arrays.equals(arrayStruct.values, new int[] {1, 2, 3}));

    DirectListField emptyListStruct = new DirectListField();
    emptyListStruct.values = new Int32List();
    DirectArrayField emptyArrayStruct =
        (DirectArrayField) arrayFory.deserialize(listFory.serialize(emptyListStruct));
    assertEquals(emptyArrayStruct.values.length, 0);

    DirectArrayField peerArrayStruct = new DirectArrayField();
    peerArrayStruct.values = new int[] {4, 5, 6};
    byte[] arrayBytes = arrayFory.serialize(peerArrayStruct);
    DirectListField readListStruct = (DirectListField) listFory.deserialize(arrayBytes);
    assertEquals(readListStruct.values, Arrays.asList(4, 5, 6));

    DirectArrayField emptyPeerArrayStruct = new DirectArrayField();
    emptyPeerArrayStruct.values = new int[0];
    DirectListField emptyReadListStruct =
        (DirectListField) listFory.deserialize(arrayFory.serialize(emptyPeerArrayStruct));
    assertEquals(emptyReadListStruct.values, java.util.Collections.emptyList());
  }

  @Test
  public void testTopLevelListAnnotatedArrayCompatibleRead() {
    Fory listFory = compatibleFory(DirectListField.class);
    DirectListField listStruct = new DirectListField();
    listStruct.values = new Int32List(new int[] {7, 8});

    Fory annotatedArrayFory = compatibleFory(DirectAnnotatedArrayField.class);
    DirectAnnotatedArrayField annotatedArrayStruct =
        (DirectAnnotatedArrayField) annotatedArrayFory.deserialize(listFory.serialize(listStruct));
    assertEquals(annotatedArrayStruct.values, Arrays.asList(7, 8));
  }

  @Test
  public void testTopLevelArrayCompatibleReadToCollection() {
    for (boolean codegen : new boolean[] {false, true}) {
      Fory arrayFory = compatibleFory(DirectArrayField.class, codegen);
      DirectArrayField peerArrayStruct = new DirectArrayField();
      peerArrayStruct.values = new int[] {9, 10};

      Fory collectionFory = compatibleFory(DirectCollectionField.class, codegen);
      DirectCollectionField collectionStruct =
          (DirectCollectionField) collectionFory.deserialize(arrayFory.serialize(peerArrayStruct));
      assertEquals(collectionStruct.values, Arrays.asList(9, 10));
    }
  }

  @Test
  public void testTopLevelListArrayCompatibleReadWithoutCodegen() {
    Fory listFory = compatibleFory(DirectListField.class, false);
    DirectListField listStruct = new DirectListField();
    listStruct.values = new Int32List(new int[] {1, 2, 3});

    Fory arrayFory = compatibleFory(DirectArrayField.class, false);
    DirectArrayField arrayStruct =
        (DirectArrayField) arrayFory.deserialize(listFory.serialize(listStruct));
    assertTrue(Arrays.equals(arrayStruct.values, new int[] {1, 2, 3}));
  }

  @Test
  public void testNullableListCompatibleReadToArray() {
    for (boolean codegen : new boolean[] {false, true}) {
      Fory listFory = compatibleFory(DirectNullableListField.class, codegen);
      DirectNullableListField listStruct = new DirectNullableListField();
      listStruct.values = Arrays.asList(1, 2, 3);
      byte[] listBytes = listFory.serialize(listStruct);

      Fory arrayFory = compatibleFory(DirectArrayField.class, codegen);
      DirectArrayField arrayStruct = (DirectArrayField) arrayFory.deserialize(listBytes);
      assertTrue(Arrays.equals(arrayStruct.values, new int[] {1, 2, 3}));

      DirectNullableListField nullElementStruct = new DirectNullableListField();
      nullElementStruct.values = Arrays.asList(1, null, 3);
      byte[] nullElementBytes = listFory.serialize(nullElementStruct);
      assertThrows(DeserializationException.class, () -> arrayFory.deserialize(nullElementBytes));
    }
  }

  @Test
  public void testNestedListArrayRejected() {
    Fory nestedListFory = compatibleFory(NestedListField.class);
    NestedListField nestedListStruct = new NestedListField();
    nestedListStruct.values = Arrays.asList(Arrays.asList(1, 2));
    byte[] nestedListBytes = nestedListFory.serialize(nestedListStruct);

    Fory nestedArrayFory = compatibleFory(NestedArrayElementField.class);
    assertThrows(
        DeserializationException.class, () -> nestedArrayFory.deserialize(nestedListBytes));

    NestedArrayElementField nestedArrayStruct = new NestedArrayElementField();
    nestedArrayStruct.values = Arrays.asList(new int[] {1, 2});
    byte[] nestedArrayBytes = nestedArrayFory.serialize(nestedArrayStruct);
    assertThrows(
        DeserializationException.class, () -> nestedListFory.deserialize(nestedArrayBytes));

    Fory nestedSetListFory = compatibleFory(NestedSetListField.class, false);
    NestedSetListField nestedSetListStruct = new NestedSetListField();
    nestedSetListStruct.values = new LinkedHashSet<>(Arrays.asList(Arrays.asList(1, 2)));
    byte[] nestedSetListBytes = nestedSetListFory.serialize(nestedSetListStruct);

    Fory nestedSetArrayFory = compatibleFory(NestedSetArrayElementField.class, false);
    assertThrows(
        DeserializationException.class, () -> nestedSetArrayFory.deserialize(nestedSetListBytes));

    NestedSetArrayElementField nestedSetArrayStruct = new NestedSetArrayElementField();
    nestedSetArrayStruct.values = new LinkedHashSet<>(Arrays.asList(new int[] {1, 2}));
    byte[] nestedSetArrayBytes = nestedSetArrayFory.serialize(nestedSetArrayStruct);
    assertThrows(
        DeserializationException.class, () -> nestedSetListFory.deserialize(nestedSetArrayBytes));
  }

  private static Fory compatibleFory(Class<?> type) {
    return compatibleFory(type, true);
  }

  private static Fory compatibleFory(Class<?> type, boolean codegen) {
    Fory fory = Fory.builder().withXlang(true).withCompatible(true).withCodegen(codegen).build();
    fory.register(type, "example.list_array_compatible");
    return fory;
  }
}
