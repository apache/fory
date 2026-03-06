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

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.KryoSerializable;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import lombok.Data;
import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class SerializerFactoryTest {

  @Data
  public static class A implements KryoSerializable {
    private String f1;

    @Override
    public void write(Kryo kryo, Output output) {
      output.writeString(f1);
    }

    @Override
    public void read(Kryo kryo, Input input) {
      f1 = input.readString();
    }
  }

  private static class KryoSerializer extends Serializer {
    private Kryo kryo;
    private Output output;
    private ByteBufferInput input;

    public KryoSerializer(Fory fory, Class cls) {
      super(fory, cls);
      kryo = new Kryo();
      kryo.setRegistrationRequired(false);
      output = new Output(64, Integer.MAX_VALUE);
      input = new ByteBufferInput();
    }

    @Override
    public void write(MemoryBuffer buffer, Object value) {
      output.reset();
      kryo.writeObject(output, value);
      buffer.writeBytes(output.getBuffer(), 0, output.position());
    }

    @Override
    public Object read(MemoryBuffer buffer) {
      input.setBuffer(buffer.sliceAsByteBuffer());
      Object o = kryo.readObject(input, type);
      buffer.readerIndex(buffer.readerIndex() + input.position());
      return o;
    }
  }

  @Test
  public void testSerializerFactory() {
    Fory fory =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .withRefTracking(false)
            .requireClassRegistration(false)
            .build();
    fory.setSerializerFactory(
        (f, cls) -> {
          if (KryoSerializable.class.isAssignableFrom(cls)) {
            return new KryoSerializer(fory, cls);
          } else {
            return null;
          }
        });
    Assert.assertEquals(fory.getTypeResolver().getSerializerClass(A.class), KryoSerializer.class);
    A a = new A();
    a.f1 = "f1";

    Object a2 = fory.deserialize(fory.serialize(a));
    Assert.assertEquals(a, a2);
  }
}
