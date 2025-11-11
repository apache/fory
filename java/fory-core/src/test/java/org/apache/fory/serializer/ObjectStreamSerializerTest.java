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
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.Preconditions;
import org.testng.Assert;
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
        fory.getClassResolver().getSerializerClass(StringBuilder.class),
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
        fory.getClassResolver().getSerializerClass(StringBuffer.class),
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
          fory.getClassResolver().getSerializer(ConcurrentHashMap.class).getClass(),
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
}
