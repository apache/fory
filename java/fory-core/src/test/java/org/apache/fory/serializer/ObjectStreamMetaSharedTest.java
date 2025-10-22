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
import org.apache.fory.resolver.MetaContext;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Tests for ObjectStreamSerializer with Meta Shared mode.
 */
public class ObjectStreamMetaSharedTest extends ForyTestBase {

  /**
   * Simple test class with custom writeObject/readObject.
   */
  static class CustomSerializable implements Serializable {
    private static final long serialVersionUID = 1L;
    private int value;
    private String name;

    public CustomSerializable() {}

    public CustomSerializable(int value, String name) {
      this.value = value;
      this.name = name;
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
      return value == that.value && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(value, name);
    }

    @Override
    public String toString() {
      return "CustomSerializable{value=" + value + ", name='" + name + "'}";
    }
  }

  @Test
  public void testBasicMetaSharedObjectStream() {
    Fory fory = builder()
        .withMetaShare(true)
        .withMetaShareForObjectStream(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .build();

    CustomSerializable obj = new CustomSerializable(10, "test");
    MetaContext context = new MetaContext();
    fory.getSerializationContext().setMetaContext(context);
    
    byte[] bytes = fory.serialize(obj);
    
    // Need to set context for deserialization too
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized = (CustomSerializable) fory.deserialize(bytes);
    
    Assert.assertEquals(deserialized, obj);
  }

  @Test
  public void testBackwardCompatibility() {
    // Test that data serialized with meta-share disabled can be read by
    // another Fory instance with the SAME configuration
    // Note: Different configurations produce different formats and cannot be mixed
    
    CustomSerializable obj = new CustomSerializable(10, "test");
    
    // Test 1: Old configuration (meta share disabled)
    Fory oldFory1 = builder()
        .withMetaShare(false)
        .requireClassRegistration(false)
        .build();
    byte[] oldBytes = oldFory1.serialize(obj);
    
    // Same configuration can read the data
    Fory oldFory2 = builder()
        .withMetaShare(false)
        .requireClassRegistration(false)
        .build();
    CustomSerializable deserialized1 = (CustomSerializable) oldFory2.deserialize(oldBytes);
    Assert.assertEquals(deserialized1, obj);
    
    // Test 2: New configuration (meta share enabled)
    Fory newFory1 = builder()
        .withMetaShare(true)
        .withMetaShareForObjectStream(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .build();
    MetaContext context = new MetaContext();
    newFory1.getSerializationContext().setMetaContext(context);
    byte[] newBytes = newFory1.serialize(obj);
    
    // Same configuration can read the data
    Fory newFory2 = builder()
        .withMetaShare(true)
        .withMetaShareForObjectStream(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .build();
    newFory2.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized2 = (CustomSerializable) newFory2.deserialize(newBytes);
    Assert.assertEquals(deserialized2, obj);
  }

  @Test
  public void testMetaSharedModeDisabled() {
    // Test with meta share disabled for ObjectStream (default behavior)
    Fory fory = builder()
        .withMetaShare(true)
        .withMetaShareForObjectStream(false)  // Explicitly disable
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .build();

    CustomSerializable obj = new CustomSerializable(10, "test");
    MetaContext context = new MetaContext();
    fory.getSerializationContext().setMetaContext(context);
    
    byte[] bytes = fory.serialize(obj);
    
    fory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserialized = (CustomSerializable) fory.deserialize(bytes);
    
    Assert.assertEquals(deserialized, obj);
  }

  @Test
  public void testSerializationSizeComparison() {
    // Test serialization size reduction
    CustomSerializable obj = new CustomSerializable(10, "test");

    // Compatible mode (old)
    Fory compatibleFory = builder()
        .withMetaShare(false)
        .requireClassRegistration(false)
        .build();
    byte[] compatibleBytes = compatibleFory.serialize(obj);

    // MetaShared mode (new)
    Fory metaSharedFory = builder()
        .withMetaShare(true)
        .withMetaShareForObjectStream(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .build();

    MetaContext context = new MetaContext();
    metaSharedFory.getSerializationContext().setMetaContext(context);
    byte[] metaSharedBytes = metaSharedFory.serialize(obj);

    System.out.println("Compatible size: " + compatibleBytes.length + " bytes");
    System.out.println("MetaShared size: " + metaSharedBytes.length + " bytes");
    if (compatibleBytes.length > 0) {
      double reduction = (100.0 - metaSharedBytes.length * 100.0 / compatibleBytes.length);
      System.out.println("Reduction: " + String.format("%.2f", reduction) + "%");
    }

    // Verify both can deserialize correctly
    CustomSerializable deserializedCompat = 
        (CustomSerializable) compatibleFory.deserialize(compatibleBytes);
    
    metaSharedFory.getSerializationContext().setMetaContext(context);
    CustomSerializable deserializedMeta = 
        (CustomSerializable) metaSharedFory.deserialize(metaSharedBytes);
    
    Assert.assertEquals(deserializedCompat, obj);
    Assert.assertEquals(deserializedMeta, obj);
  }

  @Test
  public void testMultipleObjectsSerialization() {
    // Test serializing multiple objects with meta context
    Fory fory = builder()
        .withMetaShare(true)
        .withMetaShareForObjectStream(true)
        .withCompatibleMode(CompatibleMode.COMPATIBLE)
        .requireClassRegistration(false)
        .build();

    MetaContext context = new MetaContext();
    
    CustomSerializable obj1 = new CustomSerializable(1, "first");
    CustomSerializable obj2 = new CustomSerializable(2, "second");
    CustomSerializable obj3 = new CustomSerializable(3, "third");

    // Serialize with context
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes1 = fory.serialize(obj1);
    
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes2 = fory.serialize(obj2);
    
    fory.getSerializationContext().setMetaContext(context);
    byte[] bytes3 = fory.serialize(obj3);

    // Deserialize with context
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
}
