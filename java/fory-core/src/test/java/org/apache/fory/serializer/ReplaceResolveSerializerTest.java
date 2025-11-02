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
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.Language;
import org.apache.fory.util.Preconditions;
import org.testng.annotations.Test;

public class ReplaceResolveSerializerTest extends ForyTestBase {

  @Data
  public static class CustomReplaceClass1 implements Serializable {
    public transient String name;

    public CustomReplaceClass1(String name) {
      this.name = name;
    }

    private Object writeReplace() {
      return new Replaced(name);
    }

    private static final class Replaced implements Serializable {
      public String name;

      public Replaced(String name) {
        this.name = name;
      }

      private Object readResolve() {
        return new CustomReplaceClass1(name);
      }
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testCommonReplace(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fory.registerSerializer(CustomReplaceClass1.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(CustomReplaceClass1.Replaced.class, ReplaceResolveSerializer.class);
    ImmutableList<Integer> list1 = ImmutableList.of(1, 2, 3, 4);
    fory.registerSerializer(list1.getClass(), new ReplaceResolveSerializer(fory, list1.getClass()));
    ImmutableMap<String, Integer> map1 = ImmutableMap.of("k1", 1, "k2", 2);
    fory.registerSerializer(map1.getClass(), new ReplaceResolveSerializer(fory, map1.getClass()));

    CustomReplaceClass1 o1 = new CustomReplaceClass1("abc");
    serDeCheck(fory, o1);
    assertTrue(
        fory.getClassResolver().getSerializer(o1.getClass()) instanceof ReplaceResolveSerializer);

    serDeCheck(fory, list1);

    serDeCheck(fory, map1);
    assertTrue(
        fory.getClassResolver().getSerializer(list1.getClass())
            instanceof ReplaceResolveSerializer);
    assertTrue(
        fory.getClassResolver().getSerializer(map1.getClass()) instanceof ReplaceResolveSerializer);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCommonReplace(Fory fory) {
    CustomReplaceClass1 o1 = new CustomReplaceClass1("abc");
    fory.registerSerializer(CustomReplaceClass1.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(CustomReplaceClass1.Replaced.class, ReplaceResolveSerializer.class);
    copyCheck(fory, o1);

    ImmutableList<Integer> list1 = ImmutableList.of(1, 2, 3, 4);
    fory.registerSerializer(list1.getClass(), new ReplaceResolveSerializer(fory, list1.getClass()));
    copyCheck(fory, list1);

    ImmutableMap<String, Integer> map1 = ImmutableMap.of("k1", 1, "k2", 2);
    fory.registerSerializer(map1.getClass(), new ReplaceResolveSerializer(fory, map1.getClass()));
    copyCheck(fory, map1);
  }

  @Data
  public static class CustomReplaceClass2 implements Serializable {
    public boolean copy;
    public transient int age;

    public CustomReplaceClass2(boolean copy, int age) {
      this.copy = copy;
      this.age = age;
    }

    // private `writeReplace` is not available to subclass and will be ignored by
    // `java.io.ObjectStreamClass.getInheritableMethod`
    Object writeReplace() {
      if (age > 5) {
        return new Object[] {copy, age};
      } else {
        if (copy) {
          return new CustomReplaceClass2(copy, age);
        } else {
          return this;
        }
      }
    }

    Object readResolve() {
      if (copy) {
        return new CustomReplaceClass2(copy, age);
      }
      return this;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testWriteReplaceCircularClass(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fory.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new CustomReplaceClass2(false, 2), new CustomReplaceClass2(true, 2),
        }) {
      assertEquals(jdkDeserialize(jdkSerialize(o)), o);
      serDeCheck(fory, o);
    }
    CustomReplaceClass2 o = new CustomReplaceClass2(false, 6);
    Object[] newObj = (Object[]) serDe(fory, (Object) o);
    assertEquals(newObj, new Object[] {o.copy, o.age});
    assertTrue(
        fory.getClassResolver().getSerializer(CustomReplaceClass2.class)
            instanceof ReplaceResolveSerializer);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testCopyReplaceCircularClass(Fory fory) {
    fory.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new CustomReplaceClass2(false, 2), new CustomReplaceClass2(true, 2),
        }) {
      fory.registerSerializer(o.getClass(), ReplaceResolveSerializer.class);
      copyCheck(fory, o);
    }
  }

  public static class CustomReplaceClass3 implements Serializable {
    public Object ref;

    private Object writeReplace() {
      // JDK serialization will update reference table, which change deserialized object
      //  graph, `ref` and `this` will be same.
      return ref;
    }

    private Object readResolve() {
      return ref;
    }
  }

  @Test
  public void testWriteReplaceSameClassCircularRef() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fory.registerSerializer(CustomReplaceClass3.class, ReplaceResolveSerializer.class);
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      o1.ref = o1;
      CustomReplaceClass3 o2 = (CustomReplaceClass3) jdkDeserialize(jdkSerialize(o1));
      assertSame(o2.ref, o2);
      CustomReplaceClass3 o3 = (CustomReplaceClass3) serDe(fory, o1);
      assertSame(o3.ref, o3);
    }
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      CustomReplaceClass3 o2 = new CustomReplaceClass3();
      o1.ref = o2;
      o2.ref = o1;
      {
        CustomReplaceClass3 newObj1 = (CustomReplaceClass3) jdkDeserialize(jdkSerialize(o1));
        // reference relationship updated by `CustomReplaceClass4.writeReplace`.
        assertSame(newObj1.ref, newObj1);
        assertSame(((CustomReplaceClass3) newObj1.ref).ref, newObj1);
      }
      {
        CustomReplaceClass3 newObj1 = (CustomReplaceClass3) serDe(fory, o1);
        // reference relationship updated by `CustomReplaceClass4.writeReplace`.
        assertSame(newObj1.ref, newObj1);
        assertSame(((CustomReplaceClass3) newObj1.ref).ref, newObj1);
      }
    }
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testWriteReplaceSameClassCircularRef(Fory fory) {
    fory.registerSerializer(CustomReplaceClass3.class, ReplaceResolveSerializer.class);
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      o1.ref = o1;
      CustomReplaceClass3 copy = fory.copy(o1);
      assertSame(copy, copy.ref);
    }
    {
      CustomReplaceClass3 o1 = new CustomReplaceClass3();
      CustomReplaceClass3 o2 = new CustomReplaceClass3();
      o1.ref = o2;
      o2.ref = o1;
      {
        CustomReplaceClass3 newObj1 = fory.copy(o1);
        assertNotSame(newObj1.ref, o2);
      }
    }
  }

  public static class CustomReplaceClass4 implements Serializable {
    public Object ref;

    private Object writeReplace() {
      // return ref will incur infinite loop in java.io.ObjectOutputStream.writeObject0
      // for jdk serialization.
      return this;
    }

    private Object readResolve() {
      return ref;
    }
  }

  @Test
  public void testWriteReplaceDifferentClassCircularRef() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fory.registerSerializer(CustomReplaceClass3.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(CustomReplaceClass4.class, ReplaceResolveSerializer.class);
    CustomReplaceClass3 o1 = new CustomReplaceClass3();
    CustomReplaceClass4 o2 = new CustomReplaceClass4();
    o1.ref = o2;
    o2.ref = o1;
    {
      CustomReplaceClass4 newObj1 = (CustomReplaceClass4) jdkDeserialize(jdkSerialize(o1));
      assertSame(newObj1.ref, newObj1);
      assertSame(((CustomReplaceClass4) newObj1.ref).ref, newObj1);
    }
    {
      CustomReplaceClass4 newObj1 = (CustomReplaceClass4) serDe(fory, (Object) o1);
      assertSame(newObj1.ref, newObj1);
      assertSame(((CustomReplaceClass4) newObj1.ref).ref, newObj1);
    }
  }

  public static class Subclass1 extends CustomReplaceClass2 {
    int state;

    public Subclass1(boolean copy, int age, int state) {
      super(copy, age);
      this.state = state;
    }

    Object writeReplace() {
      if (age > 5) {
        return new Object[] {copy, age};
      } else {
        if (copy) {
          return new Subclass1(copy, age, state);
        } else {
          return this;
        }
      }
    }

    Object readResolve() {
      if (copy) {
        return new Subclass1(copy, age, state);
      }
      return this;
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testWriteReplaceSubClass(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fory.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(Subclass1.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new Subclass1(false, 2, 10), new Subclass1(true, 2, 11),
        }) {
      assertEquals(jdkDeserialize(jdkSerialize(o)), o);
      serDeCheck(fory, o);
    }
    Subclass1 o = new Subclass1(false, 6, 12);
    Object[] newObj = (Object[]) serDe(fory, (Object) o);
    assertEquals(newObj, new Object[] {o.copy, o.age});
    assertTrue(
        fory.getClassResolver().getSerializer(Subclass1.class) instanceof ReplaceResolveSerializer);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testWriteReplaceSubClass(Fory fory) {
    fory.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(Subclass1.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new Subclass1(false, 2, 10), new Subclass1(true, 2, 11),
        }) {
      fory.registerSerializer(o.getClass(), ReplaceResolveSerializer.class);
      copyCheck(fory, o);
    }
  }

  public static class Subclass2 extends CustomReplaceClass2 {
    int state;

    public Subclass2(boolean copy, int age, int state) {
      super(copy, age);
      this.state = state;
    }

    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
      s.writeInt(state);
    }

    private void readObject(java.io.ObjectInputStream s) throws Exception {
      s.defaultReadObject();
      this.state = s.readInt();
    }
  }

  @Test(dataProvider = "referenceTrackingConfig")
  public void testWriteReplaceWithWriteObject(boolean referenceTracking) {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(referenceTracking)
            .build();
    fory.registerSerializer(CustomReplaceClass2.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(Subclass2.class, ReplaceResolveSerializer.class);
    for (Object o :
        new Object[] {
          new Subclass2(false, 2, 10), new Subclass2(true, 2, 11),
        }) {
      assertEquals(jdkDeserialize(jdkSerialize(o)), o);
      serDeCheck(fory, o);
    }
    Subclass2 o = new Subclass2(false, 6, 12);
    assertEquals(jdkDeserialize(jdkSerialize(o)), new Object[] {o.copy, o.age});
    Object[] newObj = (Object[]) serDe(fory, (Object) o);
    assertEquals(newObj, new Object[] {o.copy, o.age});
    assertTrue(
        fory.getClassResolver().getSerializer(Subclass2.class) instanceof ReplaceResolveSerializer);
  }

  public static class CustomReplaceClass5 {
    private Object writeReplace() {
      throw new RuntimeException();
    }

    private Object readResolve() {
      throw new RuntimeException();
    }
  }

  public static class Subclass3 extends CustomReplaceClass5 implements Serializable {}

  @Test
  public void testUnInheritableReplaceMethod() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fory.registerSerializer(CustomReplaceClass5.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(Subclass3.class, ReplaceResolveSerializer.class);
    assertTrue(jdkDeserialize(jdkSerialize(new Subclass3())) instanceof Subclass3);
    assertTrue(serDe(fory, new Subclass3()) instanceof Subclass3);
  }

  public static class CustomReplaceClass6 {
    Object writeReplace() {
      return 1;
    }
  }

  @Test
  public void testReplaceNotSerializable() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    fory.registerSerializer(CustomReplaceClass6.class, ReplaceResolveSerializer.class);
    assertThrows(Exception.class, () -> jdkSerialize(new CustomReplaceClass6()));
    assertEquals(serDe(fory, new CustomReplaceClass6()), 1);
  }

  @Data
  @AllArgsConstructor
  public static class SimpleCollectionTest {
    public List<Integer> integerList;
    public ImmutableList<String> strings;
  }

  @Test
  public void testImmutableListResolve() {
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    roundCheck(fory1, fory2, ImmutableList.of(1, 2));
    roundCheck(fory1, fory2, ImmutableList.of("a", "b"));
    roundCheck(
        fory1, fory2, new SimpleCollectionTest(ImmutableList.of(1, 2), ImmutableList.of("a", "b")));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testImmutable(Fory fory) {
    fory.registerSerializer(ImmutableList.of(1, 2).getClass(), ReplaceResolveSerializer.class);
    fory.registerSerializer(SimpleCollectionTest.class, ReplaceResolveSerializer.class);
    fory.registerSerializer(ImmutableMap.of("1", 2).getClass(), ReplaceResolveSerializer.class);
    fory.registerSerializer(SimpleMapTest.class, ReplaceResolveSerializer.class);
    copyCheck(fory, ImmutableList.of(1, 2));
    copyCheck(fory, ImmutableList.of("a", "b"));
    copyCheck(fory, new SimpleCollectionTest(ImmutableList.of(1, 2), ImmutableList.of("a", "b")));
    copyCheck(fory, ImmutableMap.of("1", 2));
    copyCheck(fory, ImmutableMap.of(1, 2));
    copyCheck(fory, new SimpleMapTest(ImmutableMap.of("k", 2), ImmutableMap.of(1, 2)));
  }

  @Data
  @AllArgsConstructor
  public static class SimpleMapTest {
    public Map<String, Integer> map1;
    public ImmutableMap<Integer, Integer> map2;
  }

  @Test
  public void testImmutableMapResolve() {
    Fory fory1 =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    Fory fory2 =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withRefTracking(true)
            .build();
    roundCheck(fory1, fory2, ImmutableMap.of("k", 2));
    roundCheck(fory1, fory2, ImmutableMap.of(1, 2));
    roundCheck(fory1, fory2, new SimpleMapTest(ImmutableMap.of("k", 2), ImmutableMap.of(1, 2)));
  }

  public static class InheritanceTestClass {
    private byte f1;

    public InheritanceTestClass(byte f1) {
      this.f1 = f1;
    }

    public Object writeReplace() {
      return new InheritanceTestClassProxy(f1);
    }
  }

  public static class InheritanceTestClassProxyBase implements Serializable {
    // Mark as transient to make object serializer unable to work, then only
    // `writeObject/readObject` can be used for serialization.
    transient byte[] data;

    private void writeObject(ObjectOutputStream stream) throws IOException {
      stream.write(data.length);
      stream.write(data);
    }

    private void readObject(ObjectInputStream stream) throws IOException {
      int size = stream.read();
      data = new byte[size];
      int read = stream.read(data);
      Preconditions.checkArgument(read == size);
    }
  }

  public static class InheritanceTestClassProxy extends InheritanceTestClassProxyBase {
    public InheritanceTestClassProxy(byte f1) {
      data = new byte[] {f1};
    }

    public Object readResolve() {
      return new InheritanceTestClass(data[0]);
    }
  }

  @Test
  public void testInheritance() {
    Fory fory = Fory.builder().requireClassRegistration(false).build();
    byte[] bytes = fory.serialize(new InheritanceTestClass((byte) 10));
    InheritanceTestClass o = (InheritanceTestClass) fory.deserialize(bytes);
    assertEquals(o.f1, 10);
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testInheritance(Fory fory) {
    fory.registerSerializer(InheritanceTestClass.class, ReplaceResolveSerializer.class);
    InheritanceTestClass o = fory.copy(new InheritanceTestClass((byte) 10));
    assertEquals(o.f1, 10);
  }

  static class WriteReplaceExternalizable implements Externalizable {
    private transient int f1;

    public WriteReplaceExternalizable(int f1) {
      this.f1 = f1;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      throw new RuntimeException();
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      throw new RuntimeException();
    }

    private Object writeReplace() {
      return new ReplaceExternalizableProxy(f1);
    }
  }

  static class ReplaceExternalizableProxy implements Serializable {
    private int f1;

    public ReplaceExternalizableProxy(int f1) {
      this.f1 = f1;
    }

    private Object readResolve() {
      return new WriteReplaceExternalizable(f1);
    }
  }

  @Test
  public void testWriteReplaceExternalizable() {
    WriteReplaceExternalizable o =
        serDeCheckSerializer(
            getJavaFory(),
            new WriteReplaceExternalizable(10),
            ReplaceResolveSerializer.class.getName());
    assertEquals(o.f1, 10);
  }

  static class ReplaceSelfExternalizable implements Externalizable {
    private transient int f1;
    private transient boolean newInstance;

    public ReplaceSelfExternalizable(int f1, boolean newInstance) {
      this.f1 = f1;
      this.newInstance = newInstance;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeInt(f1);
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      f1 = in.readInt();
    }

    private Object writeReplace() {
      return newInstance ? new ReplaceSelfExternalizable(f1, false) : this;
    }
  }

  @Test
  public void testWriteReplaceSelfExternalizable() {
    ReplaceSelfExternalizable o =
        serDeCheckSerializer(
            getJavaFory(),
            new ReplaceSelfExternalizable(10, false),
            ReplaceResolveSerializer.class.getName());
    assertEquals(o.f1, 10);
    ReplaceSelfExternalizable o1 =
        serDeCheckSerializer(
            getJavaFory(),
            new ReplaceSelfExternalizable(10, true),
            ReplaceResolveSerializer.class.getName());
    assertEquals(o1.f1, 10);
  }
}
