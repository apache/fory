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

import lombok.Data;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.Assert;
import org.testng.annotations.Test;

public class GenericTypeTest {

  @Data
  public static class TypedId<T> {
    int id;

    public TypedId() {}
  }

  @Data
  public static class TestEntity {
    TypedId<TestEntity> id;

    public TestEntity() {}
  }

  @Test
  public void testRecursiveGenericType() {
    final TestEntity bean = new TestEntity();
    bean.id = new TypedId<>();
    bean.id.id = 42;
    final RowEncoder<TestEntity> encoder = Encoders.bean(TestEntity.class);
    final BinaryRow row = encoder.toRow(bean);
    final MemoryBuffer buffer = MemoryBuffer.wrap(row.toBytes());
    row.pointTo(buffer, 0, buffer.size());
    final TestEntity deserializedBean = encoder.fromRow(row);
    Assert.assertEquals(deserializedBean, bean);
  }
}
