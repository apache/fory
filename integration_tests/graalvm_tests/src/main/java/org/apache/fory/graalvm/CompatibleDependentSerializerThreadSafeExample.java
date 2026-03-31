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

import java.util.List;
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
              f.register(Payload.class);
              f.register(LongPayload.class);
              f.register(TextPayload.class);
              f.register(NestedPayload.class);
              f.register(ChildPayload.class);
              f.register(ParentEnvelope.class);
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
    ParentEnvelope envelope = newEnvelope();
    Object result = fory.deserialize(fory.serialize(envelope));
    Preconditions.checkArgument(envelope.equals(result), "Round-trip should preserve envelope");
  }

  private static ParentEnvelope newEnvelope() {
    LongPayload longPayload = new LongPayload();
    longPayload.label = "long";
    longPayload.value = 42L;

    TextPayload textPayload = new TextPayload();
    textPayload.label = "text";
    textPayload.text = "payload-9";

    NestedPayload nestedPayload = new NestedPayload();
    nestedPayload.code = "nested";
    nestedPayload.payload = textPayload;

    ChildPayload childPayload = new ChildPayload();
    childPayload.name = "child";
    childPayload.nested = nestedPayload;
    childPayload.payloads = List.of(longPayload, textPayload);

    ParentEnvelope envelope = new ParentEnvelope();
    envelope.id = 42;
    envelope.child = childPayload;
    envelope.primaryPayload = textPayload;
    envelope.payloads = List.of(textPayload, longPayload);
    return envelope;
  }

  /** Root object for the regression scenario with nested and polymorphic payload fields. */
  public static class ParentEnvelope {
    public int id;
    public ChildPayload child;
    public Payload primaryPayload;
    public List<Payload> payloads;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ParentEnvelope that = (ParentEnvelope) o;
      return id == that.id
          && Objects.equals(child, that.child)
          && Objects.equals(primaryPayload, that.primaryPayload)
          && Objects.equals(payloads, that.payloads);
    }

    @Override
    public int hashCode() {
      return Objects.hash(id, child, primaryPayload, payloads);
    }
  }

  public static class ChildPayload {
    public String name;
    public NestedPayload nested;
    public List<Payload> payloads;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      ChildPayload that = (ChildPayload) o;
      return Objects.equals(name, that.name)
          && Objects.equals(nested, that.nested)
          && Objects.equals(payloads, that.payloads);
    }

    @Override
    public int hashCode() {
      return Objects.hash(name, nested, payloads);
    }
  }

  public abstract static class Payload {
    public String label;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      Payload payload = (Payload) o;
      return Objects.equals(label, payload.label);
    }

    @Override
    public int hashCode() {
      return Objects.hash(label);
    }
  }

  public static final class LongPayload extends Payload {
    public long value;

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) return false;
      LongPayload that = (LongPayload) o;
      return value == that.value;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), value);
    }
  }

  public static final class TextPayload extends Payload {
    public String text;

    @Override
    public boolean equals(Object o) {
      if (!super.equals(o)) return false;
      TextPayload that = (TextPayload) o;
      return Objects.equals(text, that.text);
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), text);
    }
  }

  public static final class NestedPayload {
    public String code;
    public Payload payload;

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      NestedPayload that = (NestedPayload) o;
      return Objects.equals(code, that.code) && Objects.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
      return Objects.hash(code, payload);
    }
  }
}
