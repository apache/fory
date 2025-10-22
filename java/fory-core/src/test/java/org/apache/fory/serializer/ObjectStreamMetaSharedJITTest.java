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

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Objects;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.config.Config;
import org.apache.fory.resolver.MetaContext;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for ObjectStreamMetaSharedSerializerAdapter's readAndSetFields method
 * with both interpreter and JIT modes.
 */
public class ObjectStreamMetaSharedJITTest extends ForyTestBase {

  /**
   * Test class with custom writeObject/readObject and various field types.
   */
  static class CustomSerializable implements Serializable {
    private static final long serialVersionUID = 1L;
    private int intValue;
    private String stringValue;
    private boolean boolValue;
    private double doubleValue;

    public CustomSerializable() {}

    public CustomSerializable(int intValue, String stringValue, boolean boolValue, double doubleValue) {
      this.intValue = intValue;
      this.stringValue = stringValue;
      this.boolValue = boolValue;
      this.doubleValue = doubleValue;
    }

    private void writeObject(ObjectOutputStream out) throws IOException {
      out.defaultWriteObject();
    }

    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
      in.defaultReadObject();
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof CustomSerializable)) return false;
      CustomSerializable that = (CustomSerializable) o;
      return intValue == that.intValue 
          && boolValue == that.boolValue 
          && Double.compare(that.doubleValue, doubleValue) == 0 
          && Objects.equals(stringValue, that.stringValue);
    }

    @Override
    public int hashCode() {
      return Objects.hash(intValue, stringValue, boolValue, doubleValue);
    }

    @Override
    public String toString() {
      return "CustomSerializable{intValue=" + intValue 
          + ", stringValue='" + stringValue + '\'' 
          + ", boolValue=" + boolValue 
          + ", doubleValue=" + doubleValue + '}';
    }
  }

  @Test
  public void testReadAndSetFieldsWithInterpreterMode() {
    // Test with JIT disabled (interpreter mode)
    Fory fory = Fory.builder()
        .withMetaShare(true)
        .withMetaShare(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(false)  // Disable JIT
        .build();

    CustomSerializable original = new CustomSerializable(42, "test", true, 3.14);
    MetaContext context = new MetaContext();
    
    // Serialize
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes = fory.serialize(original);
    
    // Deserialize
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized = (CustomSerializable) fory.deserialize(bytes);
    
    Assert.assertEquals(deserialized, original);
    Assert.assertEquals(deserialized.intValue, 42);
    Assert.assertEquals(deserialized.stringValue, "test");
    Assert.assertEquals(deserialized.boolValue, true);
    Assert.assertEquals(deserialized.doubleValue, 3.14, 0.0001);
  }

  @Test
  public void testReadAndSetFieldsWithJITMode() {
    // Test with JIT enabled
    Fory fory = Fory.builder()
        .withMetaShare(true)
        .withMetaShare(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(true)  // Enable JIT
        .build();

    CustomSerializable original = new CustomSerializable(100, "jit-test", false, 2.718);
    MetaContext context = new MetaContext();
    
    // Serialize
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes = fory.serialize(original);
    
    // Deserialize
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized = (CustomSerializable) fory.deserialize(bytes);
    
    Assert.assertEquals(deserialized, original);
    Assert.assertEquals(deserialized.intValue, 100);
    Assert.assertEquals(deserialized.stringValue, "jit-test");
    Assert.assertEquals(deserialized.boolValue, false);
    Assert.assertEquals(deserialized.doubleValue, 2.718, 0.0001);
  }

  @Test
  public void testReadAndSetFieldsWithMultipleObjects() {
    // Test serializing multiple objects with meta context
    Fory fory = Fory.builder()
        .withMetaShare(true)
        .withMetaShare(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(true)
        .build();

    MetaContext context = new MetaContext();
    
    CustomSerializable obj1 = new CustomSerializable(1, "first", true, 1.1);
    CustomSerializable obj2 = new CustomSerializable(2, "second", false, 2.2);
    CustomSerializable obj3 = new CustomSerializable(3, "third", true, 3.3);

    // Serialize
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes1 = fory.serialize(obj1);
    
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes2 = fory.serialize(obj2);
    
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes3 = fory.serialize(obj3);

    // Deserialize
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized1 = (CustomSerializable) fory.deserialize(bytes1);
    
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized2 = (CustomSerializable) fory.deserialize(bytes2);
    
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized3 = (CustomSerializable) fory.deserialize(bytes3);

    Assert.assertEquals(deserialized1, obj1);
    Assert.assertEquals(deserialized2, obj2);
    Assert.assertEquals(deserialized3, obj3);
  }

  @Test
  public void testReadAndSetFieldsWithNullValues() {
    // Test with null string value
    Fory fory = Fory.builder()
        .withMetaShare(true)
        .withMetaShare(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(true)
        .build();

    CustomSerializable original = new CustomSerializable(99, null, true, 0.0);
    MetaContext context = new MetaContext();
    
    // Serialize
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes = fory.serialize(original);
    
    // Deserialize
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized = (CustomSerializable) fory.deserialize(bytes);
    
    Assert.assertEquals(deserialized, original);
    Assert.assertEquals(deserialized.intValue, 99);
    Assert.assertNull(deserialized.stringValue);
    Assert.assertEquals(deserialized.boolValue, true);
    Assert.assertEquals(deserialized.doubleValue, 0.0, 0.0001);
  }

  @Test
  public void testInterpreterAndJITCompatibility() {
    // Test that data serialized with interpreter mode can be read with JIT mode
    CustomSerializable original = new CustomSerializable(123, "compat", true, 1.23);
    MetaContext context = new MetaContext();

    // Serialize with interpreter mode
    Fory interpreterFory = Fory.builder()
        .withMetaShare(true)
        .withMetaShare(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(false)
        .build();
    
    interpreterFory.getSerializationContext().setMetaContext(context);
    byte[] bytes = interpreterFory.serialize(original);

    // Deserialize with JIT mode
    Fory jitFory = Fory.builder()
        .withMetaShare(true)
        .withMetaShare(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .withCodegen(true)
        .build();
    
    jitFory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized = (CustomSerializable) jitFory.deserialize(bytes);
    
    Assert.assertEquals(deserialized, original);
  }
}
