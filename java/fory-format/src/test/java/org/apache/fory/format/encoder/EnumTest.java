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
import lombok.Data;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class EnumTest {
  public enum TestEnum {
    A,
    B
  }

  @Data
  public static class EnumValue {
    public TestEnum f1;
    public Optional<TestEnum> f2;

    public EnumValue() {}
  }

  @Test
  public void testEnumPresent() {
    EnumValue v = new EnumValue();
    v.f1 = TestEnum.B;
    v.f2 = Optional.of(TestEnum.A);
    RowEncoder<EnumValue> encoder = Encoders.bean(EnumValue.class);
    BinaryRow row = encoder.toRow(v);
    MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    EnumValue deserializedV = encoder.fromRow(row);
    Assert.assertEquals(v, deserializedV);
  }

  @Test
  public void testEnumAbsent() {
    EnumValue v = new EnumValue();
    v.f1 = TestEnum.A;
    v.f2 = Optional.empty();
    RowEncoder<EnumValue> encoder = Encoders.bean(EnumValue.class);
    BinaryRow row = encoder.toRow(v);
    MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    EnumValue deserializedV = encoder.fromRow(row);
    Assert.assertEquals(v, deserializedV);
  }
}
