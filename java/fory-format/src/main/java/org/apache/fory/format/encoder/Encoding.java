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
import java.util.Set;
import org.apache.fory.format.row.binary.BinaryArray;
import org.apache.fory.format.row.binary.BinaryMap;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;

interface Encoding {
  BaseBinaryRowWriter newWriter(Schema schema);

  BaseBinaryRowWriter newWriter(Schema schema, MemoryBuffer buffer);

  BinaryArrayWriter newArrayWriter(Field field);

  BinaryArrayWriter newArrayWriter(Field field, MemoryBuffer buffer);

  RowEncoderBuilder newRowEncoder(TypeRef<?> beanType);

  /**
   * Construct a projection codec builder for an older version of {@code beanType}, reading the
   * supplied historical schema and producing instances of the current bean class. The {@code
   * nestedSuffixes} map directs codegen to embed a specific projection codec class for each
   * nested-bean type (used when a nested versioned bean was on the wire at an older version). An
   * empty map means all nested beans use their current-version codecs.
   */
  RowEncoderBuilder newProjectionRowEncoder(
      TypeRef<?> beanType,
      Schema historicalSchema,
      Set<String> liveNames,
      String classSuffix,
      Map<Class<?>, String> nestedSuffixes);

  ArrayEncoderBuilder newArrayEncoder(
      TypeRef<? extends Collection<?>> collectionType, TypeRef<?> elementType);

  /**
   * Construct an array encoder builder whose generated code references the row codec class for the
   * element bean with the supplied suffix. Used by schema-evolution paths to generate one array
   * codec per historical version of the element bean.
   */
  ArrayEncoderBuilder newProjectionArrayEncoder(
      TypeRef<? extends Collection<?>> collectionType,
      TypeRef<?> elementType,
      String rowCodecSuffix);

  MapEncoderBuilder newMapEncoder(TypeRef<? extends Map<?, ?>> mapType, TypeRef<?> beanToken);

  /**
   * Construct a map encoder builder whose generated code references the bean row codec class with
   * the supplied suffix. Used by schema-evolution paths to generate one map codec per historical
   * version of the bean.
   */
  MapEncoderBuilder newProjectionMapEncoder(
      TypeRef<? extends Map<?, ?>> mapType, TypeRef<?> beanToken, String rowCodecSuffix);

  /**
   * Build a {@link RowFactory} for {@code schema}, precomputing any schema-derived layout once.
   * Used by the schema-evolution decode path to allocate rows for a historical schema without
   * re-deriving the layout on every decode.
   */
  RowFactory newRowFactory(Schema schema);

  BinaryArray newArray(Field field);

  BinaryMap newMap(Field field);
}
