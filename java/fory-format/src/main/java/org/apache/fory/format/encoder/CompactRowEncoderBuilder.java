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

import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.codegen.Expression;
import org.apache.fory.codegen.Expression.Invoke;
import org.apache.fory.codegen.Expression.ListExpression;
import org.apache.fory.codegen.Expression.Reference;
import org.apache.fory.codegen.Expression.StaticInvoke;
import org.apache.fory.format.row.binary.writer.BaseBinaryRowWriter;
import org.apache.fory.format.row.binary.writer.BinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryArrayWriter;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.reflect.TypeRef;

/** Expression builder for building compact row encoder class. */
class CompactRowEncoderBuilder extends RowEncoderBuilder {
  public CompactRowEncoderBuilder(final TypeRef<?> beanType) {
    super(beanType);
  }

  @Override
  protected Schema inferSchema(final TypeRef<?> beanType) {
    return CompactBinaryRowWriter.sortSchema(super.inferSchema(beanType));
  }

  @Override
  protected String codecSuffix() {
    return "CompactCodec";
  }

  @Override
  protected TypeRef<? extends BaseBinaryRowWriter> rowWriterType() {
    return TypeRef.of(CompactBinaryRowWriter.class);
  }

  @Override
  protected TypeRef<? extends BinaryArrayWriter> arrayWriterType() {
    return TypeRef.of(CompactBinaryArrayWriter.class);
  }

  @Override
  protected Expression serializeForNotNullBean(
      final Expression ordinal,
      final Expression writer,
      final Expression inputObject,
      final Field fieldIfKnown,
      final Reference rowWriter,
      final Reference beanEncoder) {
    if (fieldIfKnown == null || CompactBinaryRowWriter.fixedWidthFor(fieldIfKnown) == -1) {
      return super.serializeForNotNullBean(
          ordinal, writer, inputObject, fieldIfKnown, rowWriter, beanEncoder);
    }
    // resetFor will change writerIndex. must call reset and toRow in pair.
    final Invoke reset = beanWriterReset(writer, rowWriter, ordinal);
    final Invoke toRow = new Invoke(beanEncoder, "toRow", inputObject);
    return new ListExpression(reset, toRow);
  }

  @Override
  protected Invoke beanWriterReset(
      final Expression writer, final Reference rowWriter, final Expression ordinal) {
    return new Invoke(writer, "resetFor", rowWriter, ordinal);
  }

  @Override
  protected Expression serializeForArrayByWriter(
      final Expression inputObject,
      final Expression arrayWriter,
      final TypeRef<?> typeRef,
      final Field fieldIfKnown,
      final Expression arrowField) {
    final Expression result =
        super.serializeForArrayByWriter(
            inputObject, arrayWriter, typeRef, fieldIfKnown, arrowField);
    if (fieldIfKnown == null
        || CompactBinaryRowWriter.fixedWidthFor(itemType(fieldIfKnown)) == -1) {
      return result;
    }
    return new ListExpression(
        result,
        new Invoke(
            Invoke.inlineInvoke(arrayWriter, "getBuffer", TypeRef.of(MemoryBuffer.class)),
            "writerIndex"),
        arrayWriter);
  }

  private static Field itemType(final Field fieldIfKnown) {
    return fieldIfKnown.getChildren().get(0);
  }

  @Override
  protected Expression createSchemaFromStructField(final Expression structField) {
    return sortSchema(super.createSchemaFromStructField(structField));
  }

  static Expression sortSchema(final Expression schema) {
    return new StaticInvoke(
        CompactBinaryRowWriter.class,
        "sortSchema",
        "sortedSchema",
        TypeRef.of(Schema.class),
        false,
        true,
        schema);
  }
}
