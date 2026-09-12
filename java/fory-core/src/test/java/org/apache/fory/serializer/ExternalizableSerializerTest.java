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
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import lombok.EqualsAndHashCode;
import org.apache.fory.Fory;
import org.apache.fory.ForyTestBase;
import org.apache.fory.exception.ForyException;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.test.Factory;
import org.testng.annotations.Test;

public class ExternalizableSerializerTest extends ForyTestBase {

  @Test
  public void testInaccessibleExternalizable() {
    Externalizable e = Factory.newInstance(1, 1, "bytes".getBytes());

    Fory fory =
        Fory.builder()
            .withXlang(false)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
    assertEquals(e, fory.deserialize(fory.serialize(e)));
  }

  @Test(dataProvider = "foryCopyConfig")
  public void testInaccessibleExternalizable(Fory fory) {
    Externalizable e = Factory.newInstance(1, 1, "bytes".getBytes());
    copyCheck(fory, e);
  }

  @Test(dataProvider = "twoBoolOptions")
  public void testNestedExternalizable(boolean refTracking, boolean primitiveTail) {
    Fory fory = newFory(refTracking);
    Container child = new Container(1, 1, 2, 3);
    Container value = primitiveTail ? new Container(2, child) : new Container(2, child, child, 4);
    Container result = (Container) fory.deserialize(fory.serialize(value));
    assertEquals(result, value);
    if (refTracking && !primitiveTail) {
      assertSame(result.items[0], result.items[1]);
    }
    assertAdaptersCleared(fory);

    Container deeper = new Container(3, value, new Container(4, value));
    assertEquals(fory.deserialize(fory.serialize(deeper)), deeper);
    assertAdaptersCleared(fory);
  }

  @Test(dataProvider = "oneBoolOption")
  public void testExternalizableFailure(boolean refTracking) {
    Fory fory = newFory(refTracking);
    Container invalid = new Container(2, new Container(-1, 1));
    assertThrows(ForyException.class, () -> fory.serialize(invalid));
    assertAdaptersCleared(fory);

    Container valid = new Container(2, new Container(1, 1), 2);
    byte[] bytes = fory.serialize(valid);
    assertEquals(fory.deserialize(bytes), valid);
    assertAdaptersCleared(fory);

    Container unreadable = new Container(2, new Container(-2, 1));
    byte[] unreadableBytes = fory.serialize(unreadable);
    assertThrows(ForyException.class, () -> fory.deserialize(unreadableBytes));
    assertAdaptersCleared(fory);
    assertEquals(fory.deserialize(bytes), valid);
    assertAdaptersCleared(fory);
  }

  private Fory newFory(boolean refTracking) {
    Fory fory =
        Fory.builder().withXlang(false).withCompatible(false).withRefTracking(refTracking).build();
    fory.register(Container.class);
    return fory;
  }

  private void assertAdaptersCleared(Fory fory) {
    Serializer<?> serializer = fory.getTypeResolver().getSerializer(Container.class);
    Object output = ReflectionUtils.getObjectFieldValue(serializer, "objectOutput");
    Object input = ReflectionUtils.getObjectFieldValue(serializer, "objectInput");
    assertNull(ReflectionUtils.getObjectFieldValue(output, "writeContext"));
    assertNull(ReflectionUtils.getObjectFieldValue(output, "buffer"));
    assertNull(ReflectionUtils.getObjectFieldValue(input, "readContext"));
    assertNull(ReflectionUtils.getObjectFieldValue(input, "buffer"));
  }

  @EqualsAndHashCode
  public static class Container implements Externalizable {
    private int marker;
    private Object[] items;

    public Container() {}

    Container(int marker, Object... items) {
      this.marker = marker;
      this.items = items;
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
      out.writeInt(items.length);
      for (Object item : items) {
        out.writeObject(item);
      }
      out.writeInt(marker);
      out.writeUTF("end");
      if (marker == -1) {
        throw new IOException("writeExternal failed");
      }
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
      items = new Object[in.readInt()];
      for (int i = 0; i < items.length; i++) {
        items[i] = in.readObject();
      }
      marker = in.readInt();
      assertEquals(in.readUTF(), "end");
      if (marker == -2) {
        throw new IOException("readExternal failed");
      }
    }
  }
}
