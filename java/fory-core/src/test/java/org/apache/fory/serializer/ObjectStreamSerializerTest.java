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
import static org.testng.Assert.assertSame;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.Preconditions;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class ObjectStreamSerializerTest extends ForyTestBase {

  @EqualsAndHashCode
  public static class WriteObjectTestClass implements Serializable {
    int count;
    char[] value;

    public WriteObjectTestClass(char[] value) {
      this.count = value.length;
      this.value = value;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
      s.writeInt(count);
      s.writeObject(value);
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
      count = s.readInt();
      value = (char[]) s.readObject();
    }
  }

  @Test(dataProvider = "javaFory")
  public void testJDKCompatibleCommon(Fory fory) {
    fory.registerSerializer(
        WriteObjectTestClass.class, new ObjectStreamSerializer(fory, WriteObjectTestClass.class));
    fory.registerSerializer(
        StringBuilder.class, new ObjectStreamSerializer(fory, StringBuilder.class));

    WriteObjectTestClass o = new WriteObjectTestClass(new char[] {'a', 'b'});
    serDeCheckSerializer(fory, o, "ObjectStreamSerializer");
    assertSame(
        fory.getTypeResolver().getSerializerClass(StringBuilder.class),
        ObjectStreamSerializer.class);
    StringBuilder buf = (StringBuilder) serDe(fory, new StringBuilder("abc"));
    assertEquals(buf.toString(), "abc");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJDKCompatibleCommonCopy(Fory fory) {
    fory.registerSerializer(
        StringBuilder.class, new ObjectStreamSerializer(fory, StringBuilder.class));
    StringBuilder sb = fory.copy(new StringBuilder("abc"));
    assertEquals(sb.toString(), "abc");
  }

  @Test(dataProvider = "javaFory")
  public void testDispatch(Fory fory) {
    WriteObjectTestClass o = new WriteObjectTestClass(new char[] {'a', 'b'});
    serDeCheckSerializer(fory, o, "ObjectStreamSerializer");
  }

  @EqualsAndHashCode(callSuper = true)
  public static class WriteObjectTestClass2 extends WriteObjectTestClass {
    private final String data;

    public WriteObjectTestClass2(char[] value, String data) {
      super(value);
      this.data = data;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
      s.writeInt(100);
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      // defaultReadObject compatible with putFields
      s.defaultReadObject();
      Preconditions.checkArgument(s.readInt() == 100);
    }
  }

  @EqualsAndHashCode(callSuper = true)
  public static class WriteObjectTestClass3 extends WriteObjectTestClass {
    private String data;

    private static final ObjectStreamField[] serialPersistentFields = {
      new ObjectStreamField("notExist1", Integer.TYPE),
      new ObjectStreamField("notExist2", String.class)
    };

    public WriteObjectTestClass3(char[] value, String data) {
      super(value);
      this.data = data;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.putFields().put("notExist1", 100);
      s.putFields().put("notExist2", "abc");
      s.writeFields();
      s.writeUTF(data);
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      // defaultReadObject compatible with putFields
      s.defaultReadObject();
      data = s.readUTF();
    }
  }

  @Test(dataProvider = "javaFory")
  public void testJDKCompatiblePutFields(Fory fory) {
    fory.registerSerializer(
        StringBuffer.class, new ObjectStreamSerializer(fory, StringBuffer.class));
    fory.registerSerializer(BigInteger.class, new ObjectStreamSerializer(fory, BigInteger.class));
    fory.registerSerializer(InetAddress.class, new ObjectStreamSerializer(fory, InetAddress.class));
    fory.registerSerializer(
        Inet4Address.class, new ObjectStreamSerializer(fory, Inet4Address.class));
    fory.registerSerializer(
        WriteObjectTestClass2.class, new ObjectStreamSerializer(fory, WriteObjectTestClass2.class));
    fory.registerSerializer(
        WriteObjectTestClass3.class, new ObjectStreamSerializer(fory, WriteObjectTestClass3.class));

    assertSame(
        fory.getTypeResolver().getSerializerClass(StringBuffer.class),
        ObjectStreamSerializer.class);
    // test `putFields`
    StringBuffer newStringBuffer = (StringBuffer) serDe(fory, new StringBuffer("abc"));
    assertEquals(newStringBuffer.toString(), "abc");
    BigInteger bigInteger = BigInteger.valueOf(1000);
    serDeCheck(fory, bigInteger);
    InetAddress inetAddress = InetAddress.getLoopbackAddress();
    serDeCheck(fory, inetAddress);
    WriteObjectTestClass2 testClassObj2 = new WriteObjectTestClass2(new char[] {'a', 'b'}, "abc");
    serDeCheck(fory, testClassObj2);
    // test defaultReadObject compatible with putFields.
    WriteObjectTestClass3 testClassObj3 = new WriteObjectTestClass3(new char[] {'a', 'b'}, "abc");
    serDeCheck(fory, testClassObj3);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJDKCompatiblePutFieldsCopy(Fory fory) {
    fory.registerSerializer(
        StringBuffer.class, new ObjectStreamSerializer(fory, StringBuffer.class));
    StringBuffer newStringBuffer = fory.copy(new StringBuffer("abc"));
    assertEquals(newStringBuffer.toString(), "abc");
    BigInteger bigInteger = BigInteger.valueOf(1000);
    fory.registerSerializer(BigInteger.class, new ObjectStreamSerializer(fory, BigInteger.class));
    copyCheck(fory, bigInteger);
    fory.registerSerializer(InetAddress.class, new ObjectStreamSerializer(fory, InetAddress.class));
    fory.registerSerializer(
        Inet4Address.class, new ObjectStreamSerializer(fory, Inet4Address.class));
    InetAddress inetAddress = InetAddress.getLoopbackAddress();
    copyCheck(fory, inetAddress);
    WriteObjectTestClass2 testClassObj2 = new WriteObjectTestClass2(new char[] {'a', 'b'}, "abc");
    fory.registerSerializer(
        WriteObjectTestClass2.class, new ObjectStreamSerializer(fory, WriteObjectTestClass2.class));
    copyCheck(fory, testClassObj2);
    // test defaultReadObject compatible with putFields.
    WriteObjectTestClass3 testClassObj3 = new WriteObjectTestClass3(new char[] {'a', 'b'}, "abc");
    fory.registerSerializer(
        WriteObjectTestClass3.class, new ObjectStreamSerializer(fory, WriteObjectTestClass3.class));
    copyCheck(fory, testClassObj3);
  }

  @Test(dataProvider = "javaFory")
  public void testJDKCompatibleMap(Fory fory) {
    ImmutableMap<String, Integer> mapData = ImmutableMap.of("k1", 1, "k2", 2);
    fory.registerSerializer(
        ConcurrentHashMap.class, new ObjectStreamSerializer(fory, ConcurrentHashMap.class));
    Map<String, Integer> hashMap = new HashMap<>(mapData);
    fory.registerSerializer(
        hashMap.getClass(), new ObjectStreamSerializer(fory, hashMap.getClass()));

    {
      ObjectStreamSerializer serializer = new ObjectStreamSerializer(fory, ConcurrentHashMap.class);
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
      ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>(mapData);
      fory.getRefResolver().writeRefOrNull(buffer, map);
      serializer.write(buffer, map);
      fory.getRefResolver().tryPreserveRefId(buffer);
      Object newMap = serializer.read(buffer);
      assertEquals(buffer.writerIndex(), buffer.readerIndex());
      assertEquals(newMap, map);
      // ConcurrentHashMap internal structure may use jdk serialization, which will update
      // SerializationContext.
      fory.reset();
    }
    {
      serDeCheck(fory, new ConcurrentHashMap<>(mapData));
      assertSame(
          fory.getTypeResolver().getSerializer(ConcurrentHashMap.class).getClass(),
          ObjectStreamSerializer.class);
    }
    {
      // ImmutableMap use writeReplace, which needs special handling.
      serDeCheck(fory, hashMap);
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJDKCompatibleMapCopy(Fory fory) {
    ImmutableMap<String, Integer> mapData = ImmutableMap.of("k1", 1, "k2", 2);
    {
      ObjectStreamSerializer serializer = new ObjectStreamSerializer(fory, ConcurrentHashMap.class);
      ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>(mapData);
      Object copy = serializer.copy(map);
      assertEquals(copy, map);
    }
    {
      fory.registerSerializer(
          ConcurrentHashMap.class, new ObjectStreamSerializer(fory, ConcurrentHashMap.class));
      copyCheck(fory, new ConcurrentHashMap<>(mapData));
    }
    {
      Map<String, Integer> map = new HashMap<>(mapData);
      fory.registerSerializer(map.getClass(), new ObjectStreamSerializer(fory, map.getClass()));
      copyCheck(fory, map);
    }
  }

  @Test(dataProvider = "javaFory")
  public void testJDKCompatibleList(Fory fory) {
    fory.registerSerializer(ArrayList.class, new ObjectStreamSerializer(fory, ArrayList.class));
    fory.registerSerializer(LinkedList.class, new ObjectStreamSerializer(fory, LinkedList.class));
    fory.registerSerializer(Vector.class, new ObjectStreamSerializer(fory, Vector.class));

    List<String> list = new ArrayList<>(ImmutableList.of("a", "b", "c", "d"));
    serDeCheck(fory, list);
    serDeCheck(fory, new LinkedList<>(list));
    serDeCheck(fory, new Vector<>(list));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJDKCompatibleListCopy(Fory fory) {
    fory.registerSerializer(ArrayList.class, new ObjectStreamSerializer(fory, ArrayList.class));
    List<String> list = new ArrayList<>(ImmutableList.of("a", "b", "c", "d"));
    copyCheck(fory, list);
    fory.registerSerializer(LinkedList.class, new ObjectStreamSerializer(fory, LinkedList.class));
    copyCheck(fory, new LinkedList<>(list));
    fory.registerSerializer(Vector.class, new ObjectStreamSerializer(fory, Vector.class));
    copyCheck(fory, new Vector<>(list));
  }

  @Test(dataProvider = "enableCodegen")
  public void testJDKCompatibleCircularReference(boolean enableCodegen) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCodegen(enableCodegen)
            .build();
    fory.registerSerializer(
        ConcurrentHashMap.class, new ObjectStreamSerializer(fory, ConcurrentHashMap.class));
    {
      ObjectStreamSerializer serializer = new ObjectStreamSerializer(fory, ConcurrentHashMap.class);
      MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
      ConcurrentHashMap<String, Object> map =
          new ConcurrentHashMap<>(
              ImmutableMap.of(
                  "k1", 1,
                  "k2", 2));
      map.put("k3", map);
      fory.getRefResolver().writeRefOrNull(buffer, map);
      serializer.write(buffer, map);
      fory.getRefResolver().tryPreserveRefId(buffer);
      @SuppressWarnings("unchecked")
      ConcurrentHashMap<String, Object> newMap =
          (ConcurrentHashMap<String, Object>) serializer.read(buffer);
      assertEquals(buffer.writerIndex(), buffer.readerIndex());
      assertSame(newMap.get("k3"), newMap);
      assertEquals(newMap.get("k2"), map.get("k2"));
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJDKCompatibleCircularReference(Fory fory) {
    {
      ObjectStreamSerializer serializer = new ObjectStreamSerializer(fory, ConcurrentHashMap.class);
      ConcurrentHashMap<String, Object> map =
          new ConcurrentHashMap<>(
              ImmutableMap.of(
                  "k1", 1,
                  "k2", 2));
      map.put("k3", map);
      @SuppressWarnings("unchecked")
      ConcurrentHashMap<String, Object> newMap =
          (ConcurrentHashMap<String, Object>) serializer.copy(map);
      assertSame(newMap.get("k3"), newMap);
      assertEquals(newMap.get("k2"), map.get("k2"));
    }
  }

  public abstract static class ValidationTestClass1 implements Serializable {
    transient int state;
    String str;

    public ValidationTestClass1(String str) {
      this.str = str;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
      s.registerValidation(() -> ValidationTestClass1.this.state = getStateInternal(), 0);
    }

    protected abstract int getStateInternal();
  }

  public static class ValidationTestClass2 extends ValidationTestClass1 {
    int realState;

    public ValidationTestClass2(String str, int realState) {
      super(str);
      this.realState = realState;
    }

    @Override
    protected int getStateInternal() {
      return realState;
    }
  }

  @Test(dataProvider = "javaFory")
  public void testObjectInputValidation(Fory fory) {
    // ObjectStreamSerializer serializer = new ObjectStreamSerializer(fory, HTMLDocument.class);
    // MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(32);
    // HTMLDocument document = new HTMLDocument();
    // fory.getRefResolver().writeRefOrNull(buffer, document);
    // serializer.write(buffer, document);
    // fory.getRefResolver().tryPreserveRefId(buffer);
    // HTMLDocument newDocument = (HTMLDocument) serializer.read(buffer);
    fory.registerSerializer(
        ValidationTestClass2.class, new ObjectStreamSerializer(fory, ValidationTestClass2.class));
    int realState = 100;
    String str = "abc";
    ValidationTestClass2 obj = new ValidationTestClass2(str, realState);
    ValidationTestClass2 obj2 = (ValidationTestClass2) serDe(fory, obj);
    assertEquals(obj2.realState, realState);
    assertEquals(obj2.str, str);
    // assert validation callback work.
    assertEquals(obj2.state, realState);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testObjectInputValidationCopy(Fory fory) {
    fory.registerSerializer(
        ValidationTestClass2.class, new ObjectStreamSerializer(fory, ValidationTestClass2.class));
    int realState = 100;
    String str = "abc";
    ValidationTestClass2 obj = new ValidationTestClass2(str, realState);
    ValidationTestClass2 obj2 = fory.copy(obj);
    assertEquals(obj2.realState, realState);
    assertEquals(obj2.str, str);
  }

  @EqualsAndHashCode(callSuper = true)
  public static class WriteObjectTestClass4 extends WriteObjectTestClass {

    public WriteObjectTestClass4(char[] value) {
      super(value);
    }

    private Object writeReplace() {
      return this;
    }

    private Object readResolve() {
      return this;
    }
  }

  @Test(dataProvider = "javaFory")
  public void testWriteObjectReplace(Fory fory) throws MalformedURLException {
    fory.registerSerializer(
        WriteObjectTestClass4.class, new ObjectStreamSerializer(fory, WriteObjectTestClass4.class));

    Assert.assertEquals(
        serDeCheckSerializer(fory, new URL("http://test"), "ReplaceResolve"),
        new URL("http://test"));
    WriteObjectTestClass4 testClassObj4 = new WriteObjectTestClass4(new char[] {'a', 'b'});
    serDeCheckSerializer(fory, testClassObj4, "ObjectStreamSerializer");
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testWriteObjectReplaceCopy(Fory fory) throws MalformedURLException {
    copyCheck(fory, new URL("http://test"));
    WriteObjectTestClass4 testClassObj4 = new WriteObjectTestClass4(new char[] {'a', 'b'});
    fory.registerSerializer(
        WriteObjectTestClass4.class, new ObjectStreamSerializer(fory, WriteObjectTestClass4.class));
    copyCheck(fory, testClassObj4);
  }

  // TODO(chaokunyang) add `readObjectNoData` test for class inheritance change.
  // @Test
  public void testReadObjectNoData() {}

  // ==================== Schema Evolution Tests ====================

  @DataProvider
  public static Object[][] compatibleModeProvider() {
    return new Object[][] {{CompatibleMode.COMPATIBLE}, {CompatibleMode.SCHEMA_CONSISTENT}};
  }

  // ==================== Layer Count Evolution Tests ====================

  /** Base class for layer count tests - single layer. */
  @EqualsAndHashCode
  public static class SingleLayerClass implements Serializable {
    private String name;
    private int value;

    public SingleLayerClass() {}

    public SingleLayerClass(String name, int value) {
      this.name = name;
      this.value = value;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  /** Two-layer class hierarchy - parent. */
  @EqualsAndHashCode
  public static class TwoLayerParent implements Serializable {
    protected String parentName;
    protected int parentValue;

    public TwoLayerParent() {}

    public TwoLayerParent(String parentName, int parentValue) {
      this.parentName = parentName;
      this.parentValue = parentValue;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  /** Two-layer class hierarchy - child. */
  @EqualsAndHashCode(callSuper = true)
  public static class TwoLayerChild extends TwoLayerParent {
    private String childName;
    private double childValue;

    public TwoLayerChild() {}

    public TwoLayerChild(String parentName, int parentValue, String childName, double childValue) {
      super(parentName, parentValue);
      this.childName = childName;
      this.childValue = childValue;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  /** Three-layer class hierarchy - grandchild. */
  @EqualsAndHashCode(callSuper = true)
  public static class ThreeLayerGrandchild extends TwoLayerChild {
    private String grandchildName;
    private long grandchildValue;

    public ThreeLayerGrandchild() {}

    public ThreeLayerGrandchild(
        String parentName,
        int parentValue,
        String childName,
        double childValue,
        String grandchildName,
        long grandchildValue) {
      super(parentName, parentValue, childName, childValue);
      this.grandchildName = grandchildName;
      this.grandchildValue = grandchildValue;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  @Test(dataProvider = "javaFory")
  public void testSingleLayerSerialization(Fory fory) {
    fory.registerSerializer(
        SingleLayerClass.class, new ObjectStreamSerializer(fory, SingleLayerClass.class));
    SingleLayerClass obj = new SingleLayerClass("test", 42);
    serDeCheckSerializer(fory, obj, "ObjectStreamSerializer");
  }

  @Test(dataProvider = "javaFory")
  public void testTwoLayerSerialization(Fory fory) {
    fory.registerSerializer(
        TwoLayerChild.class, new ObjectStreamSerializer(fory, TwoLayerChild.class));
    TwoLayerChild obj = new TwoLayerChild("parent", 10, "child", 3.14);
    serDeCheckSerializer(fory, obj, "ObjectStreamSerializer");
  }

  @Test(dataProvider = "javaFory")
  public void testThreeLayerSerialization(Fory fory) {
    fory.registerSerializer(
        ThreeLayerGrandchild.class, new ObjectStreamSerializer(fory, ThreeLayerGrandchild.class));
    ThreeLayerGrandchild obj =
        new ThreeLayerGrandchild("parent", 10, "child", 3.14, "grandchild", 100L);
    serDeCheckSerializer(fory, obj, "ObjectStreamSerializer");
  }

  // ==================== Field Schema Evolution Tests ====================

  /** Class with fields that may evolve - version 1. */
  @EqualsAndHashCode
  public static class FieldEvolutionV1 implements Serializable {
    private String name;
    private int oldField;

    public FieldEvolutionV1() {}

    public FieldEvolutionV1(String name, int oldField) {
      this.name = name;
      this.oldField = oldField;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  /** Class with additional fields - version 2 (reader has more fields). */
  @EqualsAndHashCode
  public static class FieldEvolutionV2 implements Serializable {
    private String name;
    private int oldField;
    private String newField; // Added field
    private double extraField; // Added field

    public FieldEvolutionV2() {}

    public FieldEvolutionV2(String name, int oldField, String newField, double extraField) {
      this.name = name;
      this.oldField = oldField;
      this.newField = newField;
      this.extraField = extraField;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  @Test(dataProvider = "javaFory")
  public void testFieldEvolutionSameSchema(Fory fory) {
    fory.registerSerializer(
        FieldEvolutionV1.class, new ObjectStreamSerializer(fory, FieldEvolutionV1.class));
    FieldEvolutionV1 obj = new FieldEvolutionV1("test", 42);
    serDeCheckSerializer(fory, obj, "ObjectStreamSerializer");
  }

  // ==================== PutFields/DefaultReadObject Tests ====================

  /** Class that uses putFields for writing. */
  @EqualsAndHashCode
  public static class PutFieldsWriter implements Serializable {
    private String actualField;
    private int actualValue;

    // Define serialPersistentFields to control serialization format
    private static final ObjectStreamField[] serialPersistentFields = {
      new ObjectStreamField("serializedName", String.class),
      new ObjectStreamField("serializedValue", Integer.TYPE)
    };

    public PutFieldsWriter() {}

    public PutFieldsWriter(String actualField, int actualValue) {
      this.actualField = actualField;
      this.actualValue = actualValue;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      ObjectOutputStream.PutField fields = s.putFields();
      fields.put("serializedName", actualField);
      fields.put("serializedValue", actualValue);
      s.writeFields();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      // Using defaultReadObject - expects fields named in serialPersistentFields
      s.defaultReadObject();
    }
  }

  /** Class that uses defaultWriteObject and getFields for reading. */
  @EqualsAndHashCode
  public static class DefaultWriteGetFieldsReader implements Serializable {
    private String name;
    private int value;
    private transient String computed;

    public DefaultWriteGetFieldsReader() {}

    public DefaultWriteGetFieldsReader(String name, int value) {
      this.name = name;
      this.value = value;
      this.computed = name + ":" + value;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      ObjectInputStream.GetField fields = s.readFields();
      name = (String) fields.get("name", null);
      value = fields.get("value", 0);
      computed = name + ":" + value;
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testPutFieldsWithDefaultReadObject(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        PutFieldsWriter.class, new ObjectStreamSerializer(fory, PutFieldsWriter.class));

    PutFieldsWriter obj = new PutFieldsWriter("testName", 123);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    // Deserialize - defaultReadObject should handle the putFields format
    buffer.readerIndex(0);
    PutFieldsWriter result = (PutFieldsWriter) fory.deserialize(buffer);
    // Note: actualField/actualValue won't be populated directly since
    // serialPersistentFields maps to different names
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testDefaultWriteObjectWithGetFields(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        DefaultWriteGetFieldsReader.class,
        new ObjectStreamSerializer(fory, DefaultWriteGetFieldsReader.class));

    DefaultWriteGetFieldsReader obj = new DefaultWriteGetFieldsReader("test", 42);
    assertEquals(obj.computed, "test:42");

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    DefaultWriteGetFieldsReader result = (DefaultWriteGetFieldsReader) fory.deserialize(buffer);
    assertEquals(result.name, "test");
    assertEquals(result.value, 42);
    assertEquals(result.computed, "test:42");
  }

  // ==================== SerialPersistentFields Inconsistency Tests ====================

  /** Writer class with specific serialPersistentFields. */
  @EqualsAndHashCode
  public static class SerialFieldsWriter implements Serializable {
    private String data;
    private int number;

    // Define custom serialPersistentFields
    private static final ObjectStreamField[] serialPersistentFields = {
      new ObjectStreamField("customData", String.class),
      new ObjectStreamField("customNumber", Integer.TYPE),
      new ObjectStreamField("extraField", Long.TYPE) // Extra field not in actual class
    };

    public SerialFieldsWriter() {}

    public SerialFieldsWriter(String data, int number) {
      this.data = data;
      this.number = number;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      ObjectOutputStream.PutField fields = s.putFields();
      fields.put("customData", data);
      fields.put("customNumber", number);
      fields.put("extraField", 999L);
      s.writeFields();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      ObjectInputStream.GetField fields = s.readFields();
      data = (String) fields.get("customData", null);
      number = fields.get("customNumber", 0);
      // extraField is read but not stored
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testSerialPersistentFieldsInconsistentWithReader(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        SerialFieldsWriter.class, new ObjectStreamSerializer(fory, SerialFieldsWriter.class));

    SerialFieldsWriter obj = new SerialFieldsWriter("testData", 42);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    SerialFieldsWriter result = (SerialFieldsWriter) fory.deserialize(buffer);
    assertEquals(result.data, "testData");
    assertEquals(result.number, 42);
  }

  /** Class that writes with defaultWriteObject but has serialPersistentFields defined. */
  @EqualsAndHashCode
  public static class MixedSerializationClass implements Serializable {
    private String name;
    private int value;

    // serialPersistentFields different from actual fields
    private static final ObjectStreamField[] serialPersistentFields = {
      new ObjectStreamField("name", String.class),
      new ObjectStreamField("value", Integer.TYPE),
      new ObjectStreamField("nonExistentField", Double.TYPE)
    };

    public MixedSerializationClass() {}

    public MixedSerializationClass(String name, int value) {
      this.name = name;
      this.value = value;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      ObjectOutputStream.PutField fields = s.putFields();
      fields.put("name", name);
      fields.put("value", value);
      fields.put("nonExistentField", 3.14);
      s.writeFields();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      ObjectInputStream.GetField fields = s.readFields();
      name = (String) fields.get("name", null);
      value = fields.get("value", 0);
      // nonExistentField is ignored
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testMixedSerialization(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        MixedSerializationClass.class,
        new ObjectStreamSerializer(fory, MixedSerializationClass.class));

    MixedSerializationClass obj = new MixedSerializationClass("test", 42);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    MixedSerializationClass result = (MixedSerializationClass) fory.deserialize(buffer);
    assertEquals(result.name, "test");
    assertEquals(result.value, 42);
  }

  // ==================== Complex Hierarchy Schema Evolution Tests ====================

  /** Parent class with putFields writer. */
  public static class HierarchyParentPutFields implements Serializable {
    protected String parentData;

    private static final ObjectStreamField[] serialPersistentFields = {
      new ObjectStreamField("parentData", String.class),
      new ObjectStreamField("extraParentField", Integer.TYPE)
    };

    public HierarchyParentPutFields() {}

    public HierarchyParentPutFields(String parentData) {
      this.parentData = parentData;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      ObjectOutputStream.PutField fields = s.putFields();
      fields.put("parentData", parentData);
      fields.put("extraParentField", 100);
      s.writeFields();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      ObjectInputStream.GetField fields = s.readFields();
      parentData = (String) fields.get("parentData", null);
    }
  }

  /** Child class with defaultWriteObject. */
  @EqualsAndHashCode(callSuper = false)
  public static class HierarchyChildDefault extends HierarchyParentPutFields {
    private String childData;
    private int childValue;

    public HierarchyChildDefault() {}

    public HierarchyChildDefault(String parentData, String childData, int childValue) {
      super(parentData);
      this.childData = childData;
      this.childValue = childValue;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      HierarchyChildDefault that = (HierarchyChildDefault) o;
      return childValue == that.childValue
          && java.util.Objects.equals(parentData, that.parentData)
          && java.util.Objects.equals(childData, that.childData);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(parentData, childData, childValue);
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testHierarchyMixedSerialization(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        HierarchyChildDefault.class, new ObjectStreamSerializer(fory, HierarchyChildDefault.class));

    HierarchyChildDefault obj = new HierarchyChildDefault("parent", "child", 42);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    HierarchyChildDefault result = (HierarchyChildDefault) fory.deserialize(buffer);
    assertEquals(result.parentData, "parent");
    assertEquals(result.childData, "child");
    assertEquals(result.childValue, 42);
  }

  // ==================== Cross-Fory Instance Schema Tests ====================

  @Test(dataProvider = "compatibleModeProvider")
  public void testCrossForyInstanceSerialization(CompatibleMode compatible) {
    // Writer Fory instance
    Fory writerFory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    writerFory.registerSerializer(
        MixedSerializationClass.class,
        new ObjectStreamSerializer(writerFory, MixedSerializationClass.class));

    // Reader Fory instance (separate instance)
    Fory readerFory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    readerFory.registerSerializer(
        MixedSerializationClass.class,
        new ObjectStreamSerializer(readerFory, MixedSerializationClass.class));

    // Serialize with writer
    MixedSerializationClass obj = new MixedSerializationClass("crossTest", 99);
    byte[] bytes = writerFory.serialize(obj);

    // Deserialize with reader
    MixedSerializationClass result = (MixedSerializationClass) readerFory.deserialize(bytes);
    assertEquals(result.name, "crossTest");
    assertEquals(result.value, 99);
  }

  // ==================== Default Value Tests ====================

  /** Class to test default values when fields are missing. */
  @EqualsAndHashCode
  public static class DefaultValueClass implements Serializable {
    private String stringField;
    private int intField;
    private boolean boolField;
    private double doubleField;
    private Object objectField;

    public DefaultValueClass() {}

    public DefaultValueClass(
        String stringField,
        int intField,
        boolean boolField,
        double doubleField,
        Object objectField) {
      this.stringField = stringField;
      this.intField = intField;
      this.boolField = boolField;
      this.doubleField = doubleField;
      this.objectField = objectField;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      ObjectInputStream.GetField fields = s.readFields();
      stringField = (String) fields.get("stringField", "defaultString");
      intField = fields.get("intField", -1);
      boolField = fields.get("boolField", true);
      doubleField = fields.get("doubleField", 99.9);
      objectField = fields.get("objectField", "defaultObject");
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testDefaultValues(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        DefaultValueClass.class, new ObjectStreamSerializer(fory, DefaultValueClass.class));

    DefaultValueClass obj = new DefaultValueClass("test", 42, false, 3.14, "objValue");
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    DefaultValueClass result = (DefaultValueClass) fory.deserialize(buffer);
    assertEquals(result.stringField, "test");
    assertEquals(result.intField, 42);
    assertEquals(result.boolField, false);
    assertEquals(result.doubleField, 3.14, 0.001);
    assertEquals(result.objectField, "objValue");
  }

  // ==================== Nested Object Tests ====================

  /** Nested serializable class. */
  @EqualsAndHashCode
  public static class NestedClass implements Serializable {
    private String nestedValue;

    public NestedClass() {}

    public NestedClass(String nestedValue) {
      this.nestedValue = nestedValue;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  /** Container class with nested objects. */
  @EqualsAndHashCode
  public static class ContainerClass implements Serializable {
    private String containerName;
    private NestedClass nested;
    private List<NestedClass> nestedList;

    public ContainerClass() {}

    public ContainerClass(String containerName, NestedClass nested, List<NestedClass> nestedList) {
      this.containerName = containerName;
      this.nested = nested;
      this.nestedList = nestedList;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testNestedObjectSerialization(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(NestedClass.class, new ObjectStreamSerializer(fory, NestedClass.class));
    fory.registerSerializer(
        ContainerClass.class, new ObjectStreamSerializer(fory, ContainerClass.class));

    NestedClass nested = new NestedClass("nestedValue");
    List<NestedClass> nestedList = new ArrayList<>();
    nestedList.add(new NestedClass("list1"));
    nestedList.add(new NestedClass("list2"));
    ContainerClass obj = new ContainerClass("container", nested, nestedList);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    ContainerClass result = (ContainerClass) fory.deserialize(buffer);
    assertEquals(result.containerName, "container");
    assertEquals(result.nested.nestedValue, "nestedValue");
    assertEquals(result.nestedList.size(), 2);
    assertEquals(result.nestedList.get(0).nestedValue, "list1");
    assertEquals(result.nestedList.get(1).nestedValue, "list2");
  }

  // ==================== Circular Reference in Custom Serialization ====================

  /** Class with potential circular reference. */
  public static class CircularRefClass implements Serializable {
    private String name;
    private CircularRefClass reference;

    public CircularRefClass() {}

    public CircularRefClass(String name) {
      this.name = name;
    }

    public void setReference(CircularRefClass reference) {
      this.reference = reference;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      s.defaultReadObject();
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testCircularReferenceInCustomSerialization(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        CircularRefClass.class, new ObjectStreamSerializer(fory, CircularRefClass.class));

    CircularRefClass obj1 = new CircularRefClass("obj1");
    CircularRefClass obj2 = new CircularRefClass("obj2");
    obj1.setReference(obj2);
    obj2.setReference(obj1); // Circular reference

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);
    fory.serialize(buffer, obj1);

    buffer.readerIndex(0);
    CircularRefClass result = (CircularRefClass) fory.deserialize(buffer);
    assertEquals(result.name, "obj1");
    assertEquals(result.reference.name, "obj2");
    assertSame(result.reference.reference, result); // Verify circular reference preserved
  }

  // ==================== All Primitive Types Test ====================

  /** Class with all primitive types using putFields/getFields. */
  @EqualsAndHashCode
  public static class AllPrimitivesClass implements Serializable {
    private byte byteVal;
    private short shortVal;
    private int intVal;
    private long longVal;
    private float floatVal;
    private double doubleVal;
    private char charVal;
    private boolean boolVal;

    public AllPrimitivesClass() {}

    public AllPrimitivesClass(
        byte byteVal,
        short shortVal,
        int intVal,
        long longVal,
        float floatVal,
        double doubleVal,
        char charVal,
        boolean boolVal) {
      this.byteVal = byteVal;
      this.shortVal = shortVal;
      this.intVal = intVal;
      this.longVal = longVal;
      this.floatVal = floatVal;
      this.doubleVal = doubleVal;
      this.charVal = charVal;
      this.boolVal = boolVal;
    }

    private void writeObject(ObjectOutputStream s) throws IOException {
      ObjectOutputStream.PutField fields = s.putFields();
      fields.put("byteVal", byteVal);
      fields.put("shortVal", shortVal);
      fields.put("intVal", intVal);
      fields.put("longVal", longVal);
      fields.put("floatVal", floatVal);
      fields.put("doubleVal", doubleVal);
      fields.put("charVal", charVal);
      fields.put("boolVal", boolVal);
      s.writeFields();
    }

    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
      ObjectInputStream.GetField fields = s.readFields();
      byteVal = fields.get("byteVal", (byte) 0);
      shortVal = fields.get("shortVal", (short) 0);
      intVal = fields.get("intVal", 0);
      longVal = fields.get("longVal", 0L);
      floatVal = fields.get("floatVal", 0.0f);
      doubleVal = fields.get("doubleVal", 0.0);
      charVal = fields.get("charVal", '\0');
      boolVal = fields.get("boolVal", false);
    }
  }

  @Test(dataProvider = "compatibleModeProvider")
  public void testAllPrimitiveTypes(CompatibleMode compatible) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .withCompatibleMode(compatible)
            .build();
    fory.registerSerializer(
        AllPrimitivesClass.class, new ObjectStreamSerializer(fory, AllPrimitivesClass.class));

    AllPrimitivesClass obj =
        new AllPrimitivesClass((byte) 1, (short) 2, 3, 4L, 5.5f, 6.6, 'A', true);

    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(256);
    fory.serialize(buffer, obj);

    buffer.readerIndex(0);
    AllPrimitivesClass result = (AllPrimitivesClass) fory.deserialize(buffer);
    assertEquals(result.byteVal, (byte) 1);
    assertEquals(result.shortVal, (short) 2);
    assertEquals(result.intVal, 3);
    assertEquals(result.longVal, 4L);
    assertEquals(result.floatVal, 5.5f, 0.001f);
    assertEquals(result.doubleVal, 6.6, 0.001);
    assertEquals(result.charVal, 'A');
    assertEquals(result.boolVal, true);
  }
}
