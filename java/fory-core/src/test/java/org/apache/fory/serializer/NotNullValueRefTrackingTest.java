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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.testng.annotations.Test;

/**
 * Reading {@code NOT_NULL_VALUE_FLAG} payloads with a reference-tracking reader.
 *
 * <p>A peer may disable reference tracking globally or per type and legitimately send {@code
 * NOT_NULL} headers where a tracking reader preserved no read ref id. Reads that bind instances
 * through {@code ReadContext#reference} must stay balanced on such input instead of popping an
 * outer frame's id or underflowing the ref id stack.
 */
public class NotNullValueRefTrackingTest extends ForyTestBase {

  private static Fory fory(boolean trackingRef, boolean codegen) {
    return builder().withRefTracking(trackingRef).withCodegen(codegen).build();
  }

  public static class Inner implements Serializable {
    public int id;
    public String name;

    public Inner() {}

    public Inner(int id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Inner)) {
        return false;
      }
      Inner inner = (Inner) o;
      return id == inner.id && Objects.equals(name, inner.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, name);
    }
  }

  public static class Outer implements Serializable {
    public String label;
    public Inner inner;
    public List<Inner> inners;
    public Map<String, Inner> innerMap;

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Outer)) {
        return false;
      }
      Outer outer = (Outer) o;
      return Objects.equals(label, outer.label)
          && Objects.equals(inner, outer.inner)
          && Objects.equals(inners, outer.inners)
          && Objects.equals(innerMap, outer.innerMap);
    }

    @Override
    public int hashCode() {
      return Objects.hash(label, inner, inners, innerMap);
    }
  }

  public static class PolymorphicHolder implements Serializable {
    public Object stringValue;
    public Object numberValue;
    public Object structValue;

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof PolymorphicHolder)) {
        return false;
      }
      PolymorphicHolder holder = (PolymorphicHolder) o;
      return Objects.equals(stringValue, holder.stringValue)
          && Objects.equals(numberValue, holder.numberValue)
          && Objects.equals(structValue, holder.structValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(stringValue, numberValue, structValue);
    }
  }

  public static class SelfReferencing implements Serializable {
    public String name;
    public SelfReferencing self;
    public Inner shared1;
    public Inner shared2;
  }

  private static Outer newOuter() {
    Outer outer = new Outer();
    outer.label = "outer";
    outer.inner = new Inner(1, "a");
    outer.inners = new ArrayList<>(Arrays.asList(new Inner(2, "b"), new Inner(3, "c")));
    outer.innerMap = new HashMap<>();
    outer.innerMap.put("k", new Inner(4, "d"));
    return outer;
  }

  @Test(dataProvider = "enableCodegen")
  public void testStructFromUntrackedPeer(boolean codegen) {
    Fory writer = fory(false, codegen);
    Fory reader = fory(true, codegen);
    Outer outer = newOuter();
    assertEquals(reader.deserialize(writer.serialize(outer)), outer);
  }

  @Test(dataProvider = "enableCodegen")
  public void testTypedRootFromUntrackedPeer(boolean codegen) {
    Fory writer = fory(false, codegen);
    Fory reader = fory(true, codegen);
    Outer outer = newOuter();
    assertEquals(reader.deserialize(writer.serialize(outer), Outer.class), outer);
  }

  @Test(dataProvider = "enableCodegen")
  public void testContainerRootsFromUntrackedPeer(boolean codegen) {
    Fory writer = fory(false, codegen);
    Fory reader = fory(true, codegen);
    List<Inner> list = new ArrayList<>(Arrays.asList(new Inner(1, "a"), new Inner(2, "b")));
    assertEquals(reader.deserialize(writer.serialize(list)), list);
    Map<String, Inner> map = new HashMap<>();
    map.put("k1", new Inner(3, "c"));
    map.put("k2", new Inner(4, "d"));
    assertEquals(reader.deserialize(writer.serialize(map)), map);
    Object[] array = new Object[] {new Inner(5, "e"), "plain", 42};
    assertEquals(reader.deserialize(writer.serialize(array)), array);
  }

  @Test(dataProvider = "enableCodegen")
  public void testPolymorphicFieldsFromUntrackedPeer(boolean codegen) {
    Fory writer = fory(false, codegen);
    Fory reader = fory(true, codegen);
    PolymorphicHolder holder = new PolymorphicHolder();
    holder.stringValue = "value";
    holder.numberValue = 42L;
    holder.structValue = new Inner(1, "a");
    assertEquals(reader.deserialize(writer.serialize(holder)), holder);
  }

  @Test(dataProvider = "enableCodegen")
  public void testSharedAndCircularRefsStillTracked(boolean codegen) {
    Fory fory = fory(true, codegen);
    SelfReferencing bean = new SelfReferencing();
    bean.name = "root";
    bean.self = bean;
    Inner shared = new Inner(1, "shared");
    bean.shared1 = shared;
    bean.shared2 = shared;
    SelfReferencing read = (SelfReferencing) fory.deserialize(fory.serialize(bean));
    assertEquals(read.name, "root");
    assertSame(read.self, read);
    assertSame(read.shared1, read.shared2);
    assertEquals(read.shared1, shared);
  }

  @Test
  public void testXlangStructFromUntrackedPeer() {
    Fory writer = Fory.builder().withXlang(true).withRefTracking(false).build();
    writer.register(Inner.class, 101);
    Fory reader = Fory.builder().withXlang(true).withRefTracking(true).build();
    reader.register(Inner.class, 101);
    Inner inner = new Inner(7, "xlang");
    assertEquals(reader.deserialize(writer.serialize(inner)), inner);
  }
}
