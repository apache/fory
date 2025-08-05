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

package org.apache.fory.format.vectorized;

import static org.testng.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class ArrowUtilsTest {

  public static VectorSchemaRoot createVectorSchemaRoot(int size) {
    BitVector bitVector = new BitVector("boolean", ArrowUtils.allocator);
    VarCharVector varCharVector = new VarCharVector("varchar", ArrowUtils.allocator);
    for (int i = 0; i < size; i++) {
      bitVector.setSafe(i, i % 2 == 0 ? 0 : 1);
      varCharVector.setSafe(i, ("test" + i).getBytes(StandardCharsets.UTF_8));
    }
    bitVector.setValueCount(size);
    varCharVector.setValueCount(size);
    List<Field> fields = Arrays.asList(bitVector.getField(), varCharVector.getField());
    List<FieldVector> vectors = Arrays.asList(bitVector, varCharVector);
    return new VectorSchemaRoot(fields, vectors);
  }

  @Test
  public void testSerializeRecordBatch() {
    VectorSchemaRoot vectorSchemaRoot = createVectorSchemaRoot(2);
    VectorUnloader unloader = new VectorUnloader(vectorSchemaRoot);
    ArrowRecordBatch recordBatch = unloader.getRecordBatch();
    MemoryBuffer buffer = MemoryBuffer.buffer(32);
    ArrowUtils.serializeRecordBatch(recordBatch, buffer);
    try (ArrowRecordBatch batch = ArrowUtils.deserializeRecordBatch(buffer)) {
      System.out.println("newRecordBatch " + batch);
      assertEquals(batch.getLength(), recordBatch.getLength());
      assertEquals(batch.computeBodyLength(), recordBatch.computeBodyLength());
      assertEquals(batch.getMessageType(), recordBatch.getMessageType());
    }
  }
}
