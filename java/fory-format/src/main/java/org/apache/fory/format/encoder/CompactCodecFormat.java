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

import java.util.Collection;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.BinaryRow;
import org.apache.fory.format.row.binary.CompactBinaryArray;
import org.apache.fory.format.row.binary.CompactBinaryMap;
import org.apache.fory.format.row.binary.CompactBinaryRow;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;

enum CompactCodecFormat implements Encoding {
  INSTANCE;

  @Override
  public BaseBinaryRowWriter newWriter(final Schema schema) {
    return new CompactBinaryRowWriter(schema);
  }

  @Override
  public BaseBinaryRowWriter newWriter(final Schema schema, final MemoryBuffer buffer) {
    return new CompactBinaryRowWriter(schema, buffer);
  }

  @Override
  public BinaryArrayWriter newArrayWriter(final Field field) {
    return new CompactBinaryArrayWriter(field);
  }

  @Override
  public BinaryArrayWriter newArrayWriter(final Field field, final MemoryBuffer buffer) {
    return new CompactBinaryArrayWriter(field, buffer);
  }

  @Override
  public RowEncoderBuilder newRowEncoder(final TypeRef<?> beanType) {
    return new CompactRowEncoderBuilder(beanType);
  }

  @Override
  public ArrayEncoderBuilder newArrayEncoder(
      final TypeRef<? extends Collection<?>> collectionType, final TypeRef<?> elementType) {
    return new CompactArrayEncoderBuilder(collectionType, elementType);
  }

  @Override
  public MapEncoderBuilder newMapEncoder(
      final TypeRef<? extends Map<?, ?>> mapType, final TypeRef<?> beanToken) {
    return new CompactMapEncoderBuilder(mapType, beanToken);
  }

  @Override
  public BinaryRow newRow(final Schema schema) {
    return new CompactBinaryRow(schema);
  }

  @Override
  public BinaryArray newArray(final Field field) {
    return new CompactBinaryArray(field);
  }

  @Override
  public BinaryMap newMap(final Field field) {
    return new CompactBinaryMap(field);
  }
}
