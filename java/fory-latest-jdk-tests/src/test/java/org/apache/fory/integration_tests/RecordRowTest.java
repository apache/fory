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

package org.apache.fory.integration_tests;

import java.time.Instant;
import java.time.LocalDate;
import org.apache.fory.format.annotation.ForySchema;
import org.apache.fory.format.annotation.ForyVersion;
import org.apache.fory.format.encoder.Encoders;
import org.apache.fory.format.encoder.RowEncoder;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.MemoryUtils;
import org.testng.Assert;
import org.testng.annotations.Test;

public class RecordRowTest {

  public record TestRecord(Instant f1, String f2, LocalDate f3) {}

  // Intentionally mis-ordered to ensure record component order is different from sorted field order
  public record OuterTestRecord(long f2, long f1, TestRecord f3) {}

  @Test
  public void testRecord() {
    final TestRecord bean =
        new TestRecord(Instant.ofEpochMilli(42), "Luna", LocalDate.ofEpochDay(1234));
    final RowEncoder<TestRecord> encoder = Encoders.bean(TestRecord.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final TestRecord deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testNestedRecord() {
    final TestRecord nested =
        new TestRecord(Instant.ofEpochMilli(43), "Mars", LocalDate.ofEpochDay(5678));
    final OuterTestRecord bean = new OuterTestRecord(12, 34, nested);
    final RowEncoder<OuterTestRecord> encoder = Encoders.bean(OuterTestRecord.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OuterTestRecord deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  public record TestRecordNestedInterface(NestedInterface f1) {}

  public interface NestedInterface {
    int f1();

    class Impl implements NestedInterface {
      @Override
      public int f1() {
        return 42;
      }
    }
  }

  @Test
  public void testRecordNestedInterface() {
    final TestRecordNestedInterface bean =
        new TestRecordNestedInterface(new NestedInterface.Impl());
    final RowEncoder<TestRecordNestedInterface> encoder =
        Encoders.bean(TestRecordNestedInterface.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryUtils.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final TestRecordNestedInterface deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1().f1(), bean.f1().f1());
  }

  // ---------------------------------------------------------------------------
  // Records with schema evolution. @ForyVersion on a record component propagates
  // to the backing field and the accessor (its FIELD/METHOD targets), where the
  // codec reads it, so a newer reader record can pick up older payloads and
  // default components added later. The history interface still works because the
  // bean is a record: live component names match the wire field names (record
  // short-style naming).
  // ---------------------------------------------------------------------------

  public record PersonV1(String name, int age) {}

  @ForySchema(removedFields = PersonV2.History.class)
  public record PersonV2(String name, @ForyVersion(since = 2) String email) {
    interface History {
      @ForyVersion(until = 2)
      int age();
    }
  }

  @Test
  public void recordSchemaEvolution_readsOlderPayloads() {
    RowEncoder<PersonV1> writer =
        Encoders.buildBeanCodec(PersonV1.class).withSchemaEvolution().build().get();
    RowEncoder<PersonV2> reader =
        Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();
    PersonV2 out = reader.decode(writer.encode(new PersonV1("Luna", 7)));
    Assert.assertEquals(out.name(), "Luna");
    Assert.assertNull(out.email());
  }

  @Test
  public void recordSchemaEvolution_currentRoundTrip() {
    RowEncoder<PersonV2> codec =
        Encoders.buildBeanCodec(PersonV2.class).withSchemaEvolution().build().get();
    PersonV2 in = new PersonV2("Mars", "mars@example.com");
    Assert.assertEquals(codec.decode(codec.encode(in)), in);
  }

  /** Record with a primitive added at v2: an older payload must produce the primitive default. */
  public record CounterV1(String name) {}

  public record CounterV2(String name, @ForyVersion(since = 2) int count) {}

  @Test
  public void recordSchemaEvolution_primitiveDefault() {
    RowEncoder<CounterV1> writer =
        Encoders.buildBeanCodec(CounterV1.class).withSchemaEvolution().build().get();
    RowEncoder<CounterV2> reader =
        Encoders.buildBeanCodec(CounterV2.class).withSchemaEvolution().build().get();
    CounterV2 out = reader.decode(writer.encode(new CounterV1("Luna")));
    Assert.assertEquals(out.name(), "Luna");
    Assert.assertEquals(out.count(), 0);
  }

  // A record component whose own type is a versioned record. The inner struct is
  // inline in the outer's bytes with no per-inner hash, so the reader must pick an
  // inner schema consistent with the outer's strict hash. This drives the nested
  // cross-product enumeration with record-component field naming.
  public record InnerV1(String name) {}

  public record InnerV2(String name, @ForyVersion(since = 2) String tag) {}

  public record OuterInnerV1(long id, InnerV1 inner) {}

  public record OuterInnerV2(long id, InnerV2 inner) {}

  @Test
  public void recordSchemaEvolution_nestedRecordInnerNewerThanWriter() {
    RowEncoder<OuterInnerV1> writer =
        Encoders.buildBeanCodec(OuterInnerV1.class).withSchemaEvolution().build().get();
    RowEncoder<OuterInnerV2> reader =
        Encoders.buildBeanCodec(OuterInnerV2.class).withSchemaEvolution().build().get();
    OuterInnerV2 out = reader.decode(writer.encode(new OuterInnerV1(42, new InnerV1("hello"))));
    Assert.assertEquals(out.id(), 42);
    Assert.assertEquals(out.inner().name(), "hello");
    Assert.assertNull(out.inner().tag());
  }

  // A reference component added at v2 is absent from a v1 payload, so decode supplies null
  // for it and the record's canonical constructor runs with that null. A constructor that
  // rejects null for the added component would throw during decode; the supported pattern is
  // to tolerate the missing value, e.g. by normalizing null to a default in the constructor.
  public record DefaultedV1(String name) {}

  public record DefaultedV2(String name, @ForyVersion(since = 2) String email) {
    public DefaultedV2 {
      if (email == null) {
        email = "unknown";
      }
    }
  }

  @Test
  public void recordSchemaEvolution_constructorDefaultsAddedComponent() {
    RowEncoder<DefaultedV1> writer =
        Encoders.buildBeanCodec(DefaultedV1.class).withSchemaEvolution().build().get();
    RowEncoder<DefaultedV2> reader =
        Encoders.buildBeanCodec(DefaultedV2.class).withSchemaEvolution().build().get();
    DefaultedV2 out = reader.decode(writer.encode(new DefaultedV1("Luna")));
    Assert.assertEquals(out.name(), "Luna");
    Assert.assertEquals(out.email(), "unknown");
  }
}
