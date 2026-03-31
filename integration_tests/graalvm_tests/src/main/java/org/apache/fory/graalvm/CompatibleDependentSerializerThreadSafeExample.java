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

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.CompatibleMode;
import org.apache.fory.util.Preconditions;

/**
 * Regression example for GraalVM compatible serializer resolution when one generated serializer
 * depends on another generated serializer.
 */
public class CompatibleDependentSerializerThreadSafeExample {
  private static final ThreadSafeFory FORY;

  static {
    FORY =
        new ThreadLocalFory(
            classLoader -> {
              Fory f =
                  Fory.builder()
                      .withName(CompatibleDependentSerializerThreadSafeExample.class.getName())
                      .requireClassRegistration(true)
                      .withCompatibleMode(CompatibleMode.COMPATIBLE)
                      .build();
              f.register(ChildPayload.class);
              f.register(ParentEnvelope.class);
              // Prime the child serializer first so the parent codec resolves a dependent
              // generated serializer during native-image build-time initialization.
              f.getTypeResolver().getSerializer(ChildPayload.class);
              f.ensureSerializersCompiled();
              return f;
            });
    System.out.println("Init dependent compatible fory at build time");
  }

  public static void main(String[] args) throws Throwable {
    test(FORY);
    System.out.println("CompatibleDependentSerializerThreadSafeExample succeed");
  }

  static void test(ThreadSafeFory fory) throws Throwable {
    CompatibleDependentSerializerThreadSafeExample example =
        new CompatibleDependentSerializerThreadSafeExample(fory);
    example.roundTrip();
    ExecutorService service = Executors.newFixedThreadPool(10);
    for (int i = 0; i < 1000; i++) {
      service.submit(
          () -> {
            try {
              example.roundTrip();
            } catch (Throwable t) {
              example.throwable = t;
            }
          });
    }
    service.shutdown();
    service.awaitTermination(10, TimeUnit.SECONDS);
    if (example.throwable != null) {
      throw example.throwable;
    }
  }

  private volatile Throwable throwable;
  private final ThreadSafeFory fory;

  private CompatibleDependentSerializerThreadSafeExample(ThreadSafeFory fory) {
    this.fory = fory;
  }

  private void roundTrip() {
    ParentEnvelope envelope = new ParentEnvelope();
    envelope.id = 42;
    envelope.child = new ChildPayload();
    envelope.child.name = "payload";
    Object result = fory.deserialize(fory.serialize(envelope));
    Preconditions.checkArgument(envelope.equals(result), "Round-trip should preserve envelope");
  }

  public static class ParentEnvelope {
    public int id;
    public ChildPayload child;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ParentEnvelope that = (ParentEnvelope) o;
      return id == that.id && Objects.equals(child, that.child);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, child);
    }
  }

  public static class ChildPayload {
    public String name;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ChildPayload that = (ChildPayload) o;
      return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name);
    }
  }
}
