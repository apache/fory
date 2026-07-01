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
import org.apache.fory.builder.Generated.GeneratedStaticCompatibleSerializer;
import org.apache.fory.context.ReadContext;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.apache.fory.platform.GraalvmSupport;
import org.apache.fory.resolver.TypeInfo;
import org.apache.fory.serializer.Serializer;
import org.apache.fory.util.Preconditions;

/** Native-image regression test for compatible reads from a peer with a different schema. */
public class CompatibleDifferentSchemaExample {
  private static final int SCHEMA_ID = 1001;

  private static final Fory WRITER = createWriter();
  private static final Fory READER = createReader();

  private static Fory createWriter() {
    Fory fory =
        Fory.builder()
            .withName(CompatibleDifferentSchemaExample.class.getName() + ".writer")
            .withXlang(false)
            .withCompatible(true)
            .requireClassRegistration(true)
            .build();
    fory.register(WriterSchema.class, SCHEMA_ID);
    fory.ensureSerializersCompiled();
    return fory;
  }

  private static Fory createReader() {
    Fory fory =
        Fory.builder()
            .withName(CompatibleDifferentSchemaExample.class.getName() + ".reader")
            .withXlang(false)
            .withCompatible(true)
            .requireClassRegistration(true)
            .build();
    fory.register(ReaderSchema.class, SCHEMA_ID);
    fory.ensureSerializersCompiled();
    return fory;
  }

  public static void main(String[] args) {
    WriterSchema writerValue = new WriterSchema();
    writerValue.id = 42;
    writerValue.name = "writer";
    writerValue.legacy = 99;

    byte[] bytes = WRITER.serialize(writerValue);
    if (GraalvmSupport.isGraalRuntime()) {
      Serializer<?> serializer = readSerializerForTarget(READER, bytes, ReaderSchema.class);
      Preconditions.checkArgument(
          serializer instanceof GeneratedStaticCompatibleSerializer,
          "Expected GraalVM generated compatible serializer, got %s",
          serializer.getClass());
    }

    ReaderSchema result = READER.deserialize(bytes, ReaderSchema.class);
    Preconditions.checkArgument(result.id == 42);
    Preconditions.checkArgument("writer".equals(result.name));
    Preconditions.checkArgument("reader-default".equals(result.added));
    System.out.println("CompatibleDifferentSchemaExample succeed");
  }

  private static Serializer<?> readSerializerForTarget(
      Fory fory, byte[] bytes, Class<?> targetClass) {
    MemoryBuffer buffer = MemoryUtils.wrap(bytes);
    buffer.readByte();
    ReadContext readContext = fory.getReadContext();
    readContext.prepare(buffer, null, false, buffer.remaining(), false);
    try {
      readContext.getRefReader().tryPreserveRefId(buffer);
      TypeInfo typeInfo = fory.getTypeResolver().readTypeInfo(readContext, targetClass);
      return typeInfo.getSerializer();
    } finally {
      readContext.reset();
    }
  }

  public static final class WriterSchema {
    public int id;
    public String name;
    public int legacy;
  }

  public static final class ReaderSchema {
    public int id;
    public String name;
    public String added = "reader-default";
  }
}
