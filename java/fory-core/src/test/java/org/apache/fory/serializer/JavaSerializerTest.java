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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamConstants;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.exception.CopyException;
import org.apache.fory.memory.BigEndian;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class JavaSerializerTest extends ForyTestBase {

  @Data
  public static class CustomClass implements Serializable {
    public String name;
    public transient int age;

    private void writeObject(java.io.ObjectOutputStream s) throws IOException {
      s.defaultWriteObject();
      s.writeInt(age);
    }

    private void readObject(java.io.ObjectInputStream s) throws Exception {
      s.defaultReadObject();
      this.age = s.readInt();
    }
  }

  public static class JavaBox implements Serializable {
    Object value;

    JavaBox(Object value) {
      this.value = value;
    }
  }

  public static class NestedValue implements Serializable {
    String value;

    NestedValue() {
      this("nested");
    }

    NestedValue(String value) {
      this.value = value;
    }
  }

  public static class JavaCopyState implements Serializable {
    String name;
    transient int nameLength;

    JavaCopyState(String name) {
      this.name = name;
      this.nameLength = name.length();
    }

    private void readObject(java.io.ObjectInputStream stream) throws Exception {
      stream.defaultReadObject();
      nameLength = name.length();
    }
  }

  @Test
  public void testWriteObject() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    serDe(fory, new CustomClass());
  }

  @Test
  public void testJdkSerializationMagicNumber() throws Exception {
    ByteArrayOutputStream bas = new ByteArrayOutputStream();
    try (ObjectOutputStream objectOutputStream = new ObjectOutputStream(bas)) {
      objectOutputStream.writeObject(1.1);
      objectOutputStream.flush();
    }
    byte[] bytes = bas.toByteArray();
    Assert.assertEquals(BigEndian.getShortB(bytes, 0), ObjectStreamConstants.STREAM_MAGIC);
    Assert.assertTrue(JavaSerializer.serializedByJDK(bytes));
    Assert.assertTrue(JavaSerializer.serializedByJDK(ByteBuffer.wrap(bytes), 0));
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    bytes = fory.serialize(1.1);
    Assert.assertFalse(JavaSerializer.serializedByJDK(bytes));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testJdkSerializationCopy(Fory fory) throws MalformedURLException {
    URL url = new URL("http://localhost:80");
    fory.registerSerializer(URL.class, JavaSerializer.class);
    copyCheck(fory, url);
  }

  @Test
  public void testCopyUsesJavaSerialization() {
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefCopy(true)
            .requireClassRegistration(false)
            .suppressClassRegistrationWarnings(true)
            .withCompatible(false)
            .build();
    fory.registerSerializer(JavaCopyState.class, JavaSerializer.class);
    JavaCopyState state = new JavaCopyState("fory");
    state.nameLength = -1;
    JavaCopyState copy = fory.copy(state);

    Assert.assertNotSame(copy, state);
    Assert.assertEquals(copy.name, state.name);
    Assert.assertEquals(copy.nameLength, 4);
  }

  @Test
  public void testJdkStreamChecksNestedClass() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    Serializer serializer = new JavaSerializer(fory.getTypeResolver(), JavaBox.class);
    fory.registerSerializer(JavaBox.class, serializer);
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(128);
    writeSerializer(fory, serializer, buffer, new JavaBox(new NestedValue()));

    Assert.assertThrows(
        InvalidClassException.class, () -> readSerializer(fory, serializer, buffer));
  }

  @Test
  public void testCopyChecksNestedClass() {
    Fory fory = Fory.builder().withXlang(false).withCompatible(false).build();
    fory.register(JavaBox.class);
    fory.registerSerializer(JavaBox.class, JavaSerializer.class);

    CopyException exception =
        Assert.expectThrows(CopyException.class, () -> fory.copy(new JavaBox(new NestedValue())));
    Assert.assertTrue(exception.getCause() instanceof InvalidClassException);
    Assert.assertTrue(exception.getCause().getMessage().contains(NestedValue.class.getName()));
  }
}
