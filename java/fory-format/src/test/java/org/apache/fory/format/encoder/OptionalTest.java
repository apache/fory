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

package org.apache.fory.format.encoder;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import lombok.Data;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class OptionalTest {

  @Data
  public static class OptionalType {
    public Optional<Integer> f1;
    public Optional<String> f2;

    public OptionalType() {}
  }

  @Test
  public void testOptionalEmpty() {
    final OptionalType bean = new OptionalType();
    bean.f1 = Optional.empty();
    bean.f2 = Optional.empty();
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testOptionalNull() {
    final OptionalType bean = new OptionalType();
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean.f1, Optional.empty());
    Assert.assertEquals(deserializedBean.f2, Optional.empty());
  }

  @Test
  public void testOptionalPresent() {
    final OptionalType bean = new OptionalType();
    bean.f1 = Optional.of(42);
    bean.f2 = Optional.of("Indubitably");
    final RowEncoder<OptionalType> encoder = Encoders.bean(OptionalType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Data
  public static class OptionalIntType {
    public OptionalInt f1;

    public OptionalIntType() {}
  }

  @Test
  public void testIntEmpty() {
    final OptionalIntType bean = new OptionalIntType();
    bean.f1 = OptionalInt.empty();
    final RowEncoder<OptionalIntType> encoder = Encoders.bean(OptionalIntType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalIntType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testIntPresent() {
    final OptionalIntType bean = new OptionalIntType();
    bean.f1 = OptionalInt.of(42);
    final RowEncoder<OptionalIntType> encoder = Encoders.bean(OptionalIntType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalIntType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Data
  public static class OptionalLongType {
    public OptionalLong f1;

    public OptionalLongType() {}
  }

  @Test
  public void testLongEmpty() {
    final OptionalLongType bean = new OptionalLongType();
    bean.f1 = OptionalLong.empty();
    final RowEncoder<OptionalLongType> encoder = Encoders.bean(OptionalLongType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalLongType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testLongPresent() {
    final OptionalLongType bean = new OptionalLongType();
    bean.f1 = OptionalLong.of(42);
    final RowEncoder<OptionalLongType> encoder = Encoders.bean(OptionalLongType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalLongType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Data
  public static class OptionalDoubleType {
    public OptionalDouble f1;

    public OptionalDoubleType() {}
  }

  @Test
  public void testDoubleEmpty() {
    final OptionalDoubleType bean = new OptionalDoubleType();
    bean.f1 = OptionalDouble.empty();
    final RowEncoder<OptionalDoubleType> encoder = Encoders.bean(OptionalDoubleType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalDoubleType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }

  @Test
  public void testDoublePresent() {
    final OptionalDoubleType bean = new OptionalDoubleType();
    bean.f1 = OptionalDouble.of(42);
    final RowEncoder<OptionalDoubleType> encoder = Encoders.bean(OptionalDoubleType.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final OptionalDoubleType deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }
}
