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

package org.apache.fory.graalvm;

import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.Preconditions;

public class EnsureSerializerExample {
  static Fory fory;

  static {
    fory =
        Fory.builder()
            .withName(EnsureSerializerExample.class.getName())
            .requireClassRegistration(true)
            .build();
    // register and generate serializer code.
    fory.registerSerializer(Custom.class, new CustomSerializer(fory));
    fory.register(EnsureSerializerExample.class);
    fory.ensureSerializersCompiled();
  }

  public Custom custom = new Custom();

  static void test(Fory fory) {
    EnsureSerializerExample obj = new EnsureSerializerExample();
    EnsureSerializerExample out = (EnsureSerializerExample) fory.deserialize(fory.serialize(obj));
    Preconditions.checkArgument(42 == out.custom.i);
  }

  public static void main(String[] args) {
    test(fory);
    System.out.println("EnsureSerializerExample succeed");
  }

  public static class Custom {
    public int i = 42;
  }

  private static class CustomSerializer extends Serializer<Custom> {
    public CustomSerializer(Fory fory) {
      super(fory, Custom.class);
    }

    @Override
    public void write(MemoryBuffer buffer, Custom value) {}

    @Override
    public Custom read(MemoryBuffer buffer) {
      return new Custom();
    }
  }
}
