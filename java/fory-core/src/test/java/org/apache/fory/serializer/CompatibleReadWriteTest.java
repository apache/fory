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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.builder.Generated;
import org.apache.fory.context.ReadContext;
import org.apache.fory.context.WriteContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.resolver.TypeInfoHolder;
import org.apache.fory.serializer.collection.CollectionSerializers;
import org.testng.Assert;
import org.testng.annotations.Test;

public class CompatibleReadWriteTest extends ForyTestBase {
  private static final int ITEM_ID = 100;
  private static final int CONTAINER_ID = 101;
  private static final int BOX_ID = 102;

  public static class ItemV1 {
    public String name;
  }

  public static class ItemV2 {
    public String name;
    public String tag;
  }

  public static class ArrayBoxV1 {
    public ItemV1[] items;
  }

  public static class ArrayBoxV2 {
    public ItemV2[] items;
  }

  public static class ListBox {
    public List<Object> items;
  }

  public static class CustomListV1 extends ArrayList<Object> {
    public String name;
  }

  public static class CustomListV2 extends ArrayList<Object> {
    public String name;
    public String tag;
  }

  @Test(dataProvider = "twoBoolOptions")
  public void testArrayReadThenWrite(boolean referenceTracking, boolean codegen) {
    Fory oldWriter = compatibleFory(referenceTracking, codegen);
    oldWriter.register(ItemV1.class, ITEM_ID);
    oldWriter.register(ItemV1[].class, CONTAINER_ID);
    oldWriter.register(ArrayBoxV1.class, BOX_ID);
    ArrayBoxV1 oldBox = new ArrayBoxV1();
    oldBox.items = new ItemV1[] {oldItem()};

    Fory fory = compatibleFory(referenceTracking, codegen);
    Fory fresh = compatibleFory(referenceTracking, codegen);
    for (Fory current : new Fory[] {fory, fresh}) {
      current.register(ItemV2.class, ITEM_ID);
      current.register(ItemV2[].class, CONTAINER_ID);
      current.register(ArrayBoxV2.class, BOX_ID);
    }
    ArrayBoxV2 newBox = new ArrayBoxV2();
    newBox.items = new ItemV2[] {newItem()};

    byte[] expected = fresh.serialize(newBox);
    fory.deserialize(oldWriter.serialize(oldBox));
    ItemV2[] copy = fory.copy(newBox.items);
    Assert.assertNotSame(copy, newBox.items);
    Assert.assertNotSame(copy[0], newBox.items[0]);
    Assert.assertEquals(copy[0].tag, "item-v2");

    byte[] actual = fory.serialize(newBox);
    Assert.assertEquals(actual, expected);
    Assert.assertEquals(((ArrayBoxV2) fresh.deserialize(actual)).items[0].tag, "item-v2");
  }

  @Test(dataProvider = "twoBoolOptions")
  public void testCollectionReadThenWrite(boolean referenceTracking, boolean codegen) {
    Fory oldWriter = compatibleFory(referenceTracking, codegen);
    oldWriter.register(ItemV1.class, ITEM_ID);
    ArrayList<ItemV1> oldItems = new ArrayList<>();
    oldItems.add(oldItem());

    Fory fory = compatibleFory(referenceTracking, codegen);
    Fory fresh = compatibleFory(referenceTracking, codegen);
    for (Fory current : new Fory[] {fory, fresh}) {
      current.register(ItemV2.class, ITEM_ID);
    }
    ArrayList<ItemV2> newItems = new ArrayList<>();
    newItems.add(newItem());

    byte[] expected = fresh.serialize(newItems);
    fory.deserialize(oldWriter.serialize(oldItems));
    ArrayList<ItemV2> copy = fory.copy(newItems);
    Assert.assertNotSame(copy, newItems);
    Assert.assertNotSame(copy.get(0), newItems.get(0));
    Assert.assertEquals(copy.get(0).tag, "item-v2");

    byte[] actual = fory.serialize(newItems);
    Assert.assertEquals(actual, expected);
    ArrayList<ItemV2> decoded = (ArrayList<ItemV2>) fresh.deserialize(actual);
    Assert.assertEquals(decoded.get(0).tag, "item-v2");
  }

  @Test(dataProvider = "oneBoolOption")
  public void testGeneratedCollectionReadThenWrite(boolean referenceTracking) {
    Fory oldWriter = compatibleFory(referenceTracking, true);
    oldWriter.register(ItemV1.class, ITEM_ID);
    oldWriter.register(ListBox.class, BOX_ID);
    ListBox oldBox = new ListBox();
    oldBox.items = new ArrayList<>();
    oldBox.items.add(oldItem());

    Fory fory = compatibleFory(referenceTracking, true);
    Fory fresh = compatibleFory(referenceTracking, true);
    for (Fory current : new Fory[] {fory, fresh}) {
      current.register(ItemV2.class, ITEM_ID);
      current.register(ListBox.class, BOX_ID);
    }
    Assert.assertTrue(
        Generated.GeneratedSerializer.class.isAssignableFrom(
            fory.getTypeResolver().getSerializerClass(ListBox.class)));
    List<String> holderNames =
        Arrays.stream(fory.getSerializer(ListBox.class).getClass().getDeclaredFields())
            .filter(field -> field.getType() == TypeInfoHolder.class)
            .map(field -> field.getName())
            .collect(Collectors.toList());
    Assert.assertTrue(
        holderNames.stream().anyMatch(name -> name.contains("WriteTypeInfoHolder")),
        holderNames.toString());
    ListBox newBox = new ListBox();
    newBox.items = new ArrayList<>();
    newBox.items.add(newItem());

    byte[] expected = fresh.serialize(newBox);
    Assert.assertEquals(fory.serialize(newBox), expected);
    fory.deserialize(oldWriter.serialize(oldBox));
    byte[] actual = fory.serialize(newBox);
    Assert.assertEquals(actual, expected);
    ListBox decoded = (ListBox) fresh.deserialize(actual);
    Assert.assertEquals(((ItemV2) decoded.items.get(0)).tag, "item-v2");
  }

  @Test
  public void testContainerFieldReadThenWrite() {
    Fory oldWriter = compatibleFory(false, false);
    oldWriter.register(ItemV1.class, ITEM_ID);
    oldWriter.register(CustomListV1.class, CONTAINER_ID);
    CustomListV1 oldItems = new CustomListV1();
    oldItems.name = "old-list";
    oldItems.add(oldItem());

    Fory fory = compatibleFory(false, false);
    Fory fresh = compatibleFory(false, false);
    for (Fory current : new Fory[] {fory, fresh}) {
      current.register(ItemV2.class, ITEM_ID);
      current.register(CustomListV2.class, CONTAINER_ID);
    }
    CustomListV2 newItems = new CustomListV2();
    newItems.name = "new-list";
    newItems.tag = "list-v2";
    newItems.add(newItem());

    FieldGroups.SerializationFieldInfo oldFieldInfo = containerFieldInfo(oldWriter);
    FieldGroups.SerializationFieldInfo fieldInfo = containerFieldInfo(fory);
    FieldGroups.SerializationFieldInfo freshFieldInfo = containerFieldInfo(fresh);
    byte[] expected = writeContainerField(fresh, freshFieldInfo, newItems);
    readContainerField(fory, fieldInfo, writeContainerField(oldWriter, oldFieldInfo, oldItems));
    Assert.assertEquals(fieldInfo.classInfoReadHolder.typeInfo.getType(), CustomListV2.class);
    Assert.assertNotEquals(fieldInfo.classInfoHolder.typeInfo.getType(), CustomListV2.class);
    byte[] actual = writeContainerField(fory, fieldInfo, newItems);
    Assert.assertEquals(actual, expected);
    CustomListV2 decoded = (CustomListV2) readContainerField(fresh, freshFieldInfo, actual);
    Assert.assertEquals(decoded.tag, "list-v2");
    Assert.assertEquals(((ItemV2) decoded.get(0)).tag, "item-v2");
  }

  @Test(dataProvider = "oneBoolOption")
  public void testKeySetViewReadThenWrite(boolean referenceTracking) {
    Fory oldWriter = compatibleFory(referenceTracking, false);
    oldWriter.register(ItemV1.class, ITEM_ID);
    ConcurrentHashMap.KeySetView<String, ItemV1> oldView =
        new ConcurrentHashMap<String, ItemV1>().keySet(oldItem());

    Fory fory = compatibleFory(referenceTracking, false);
    Fory fresh = compatibleFory(referenceTracking, false);
    for (Fory current : new Fory[] {fory, fresh}) {
      current.register(ItemV2.class, ITEM_ID);
    }
    Assert.assertEquals(
        fory.getTypeResolver().getSerializerClass(oldView.getClass()),
        CollectionSerializers.ConcurrentHashMapKeySetViewSerializer.class);
    ConcurrentHashMap.KeySetView<String, ItemV2> newView =
        new ConcurrentHashMap<String, ItemV2>().keySet(newItem());

    byte[] expected = fresh.serialize(newView);
    fory.deserialize(oldWriter.serialize(oldView));
    byte[] actual = fory.serialize(newView);
    Assert.assertEquals(actual, expected);
    ConcurrentHashMap.KeySetView<String, ItemV2> decoded =
        (ConcurrentHashMap.KeySetView<String, ItemV2>) fresh.deserialize(actual);
    Assert.assertEquals(decoded.getMappedValue().tag, "item-v2");
  }

  private static Fory compatibleFory(boolean referenceTracking, boolean codegen) {
    return Fory.builder()
        .withXlang(false)
        .withRefTracking(referenceTracking)
        .withCompatible(true)
        .withCodegen(codegen)
        .withAsyncCompilation(false)
        .requireClassRegistration(true)
        .build();
  }

  private static FieldGroups.SerializationFieldInfo containerFieldInfo(Fory fory) {
    return FieldGroups.buildFieldsInfo(
            fory.getTypeResolver(), Arrays.asList(ListBox.class.getDeclaredFields()))
        .containerFields[0];
  }

  private static byte[] writeContainerField(
      Fory fory, FieldGroups.SerializationFieldInfo fieldInfo, Object value) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    WriteContext writeContext = fory.getWriteContext();
    writeContext.prepare(buffer, null);
    try {
      AbstractObjectSerializer.writeContainerFieldValue(
          writeContext,
          fory.getTypeResolver(),
          writeContext.getRefWriter(),
          writeContext.getGenerics(),
          fieldInfo,
          buffer,
          value);
      return buffer.getBytes(0, buffer.writerIndex());
    } finally {
      writeContext.reset();
    }
  }

  private static Object readContainerField(
      Fory fory, FieldGroups.SerializationFieldInfo fieldInfo, byte[] bytes) {
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(bytes);
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(buffer, null, false);
    try {
      return AbstractObjectSerializer.readContainerFieldValue(
          readContext,
          fory.getTypeResolver(),
          readContext.getRefReader(),
          readContext.getGenerics(),
          fieldInfo,
          buffer);
    } finally {
      readContext.reset();
    }
  }

  private static ItemV1 oldItem() {
    ItemV1 item = new ItemV1();
    item.name = "item";
    return item;
  }

  private static ItemV2 newItem() {
    ItemV2 item = new ItemV2();
    item.name = "item";
    item.tag = "item-v2";
    return item;
  }
}
