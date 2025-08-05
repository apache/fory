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

package org.apache.fory.format.row.binary;

import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.BinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.memory.MemoryBuffer;
import org.testng.annotations.Test;

public class BinaryMapTest {

  @Test
  public void pointTo() {
    MemoryBuffer buffer = MemoryBuffer.buffer(1024);
    int writerIndex = 8; // preserve 8 byte for numBytes
    BinaryArrayWriter keyArrayWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()));
    keyArrayWriter.reset(2);
    keyArrayWriter.write(0, "k0");
    keyArrayWriter.write(1, "k1");
    buffer.copyFrom(
        writerIndex,
        keyArrayWriter.getBuffer(),
        keyArrayWriter.getStartIndex(),
        keyArrayWriter.size());
    writerIndex += keyArrayWriter.size();
    buffer.putInt64(0, keyArrayWriter.size());
    BinaryArrayWriter valueArrayWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()));
    valueArrayWriter.reset(2);
    valueArrayWriter.write(0, "v0");
    valueArrayWriter.write(1, "v1");
    buffer.copyFrom(
        writerIndex,
        valueArrayWriter.getBuffer(),
        valueArrayWriter.getStartIndex(),
        valueArrayWriter.size());
    writerIndex += valueArrayWriter.size();
    BinaryMap map = new BinaryMap(DataTypes.mapField(DataTypes.utf8(), DataTypes.utf8()));
    map.pointTo(buffer, 0, writerIndex);
    // System.out.println(map);
  }

  @Test
  public void testDirectlyWrite() {
    List<Field> fields =
        Collections.singletonList(DataTypes.mapField("f1", DataTypes.utf8(), DataTypes.utf8()));
    Schema schema = new Schema(fields);

    BinaryRowWriter writer = new BinaryRowWriter(schema);
    writer.reset();
    // write map
    int offset = writer.writerIndex();
    // System.out.println("start writerIndex: " + writer.writerIndex());
    // preserve 8 bytes to write the key array numBytes later
    writer.writeDirectly(-1);
    BinaryArrayWriter keyArrayWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()), writer);
    keyArrayWriter.reset(2);
    keyArrayWriter.write(0, "k0");
    keyArrayWriter.write(1, "k1");
    // System.out.println("key array: " + keyArrayWriter.toArray());
    // write key array numBytes
    writer.writeDirectly(offset, keyArrayWriter.size());
    // System.out.println("key array end writerIndex: " + writer.writerIndex());
    BinaryArrayWriter valueArrayWriter =
        new BinaryArrayWriter(DataTypes.arrayField(DataTypes.utf8()), writer);
    valueArrayWriter.reset(2);
    valueArrayWriter.write(0, "v0");
    valueArrayWriter.write(1, "v1");
    // System.out.println("value array: " + valueArrayWriter.toArray());
    // System.out.println("value array end writerIndex: " + writer.writerIndex());

    int size = writer.writerIndex() - offset;
    writer.setNotNullAt(0);
    writer.setOffsetAndSize(0, offset, size);

    writer.getRow();
    // System.out.println(row);
  }
}
