/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.fory.format.vectorized;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.stream.IntStream;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.TimeStampMicroTZVector;
import org.apache.arrow.vector.TinyIntVector;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.complex.impl.UnionMapWriter;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.row.Getters;
import org.apache.fory.format.row.MapData;
import org.apache.fory.format.row.Row;
import org.apache.fory.format.type.DefaultTypeVisitor;

// Derived from
// https://github.com/apache/spark/blob/921fb289f003317d89120faa6937e4abd359195c/sql/catalyst/src/main/scala/org/apache/spark/sql/execution/arrow/ArrowWriter.scala.

/** Converter between fory {@link Row} and arrow {@link ArrowRecordBatch}. */
public class ArrowWriter {
  private int rowCount = 0;
  private final VectorSchemaRoot root;
  private final VectorUnloader unloader;
  private final ArrowArrayWriter[] fieldWriters;

  public ArrowWriter(VectorSchemaRoot root) {
    this.root = root;
    this.unloader = new VectorUnloader(root);
    this.fieldWriters =
        root.getFieldVectors().stream()
            .map(
                valueVector -> {
                  valueVector.allocateNew();
                  return createFieldWriter(valueVector);
                })
            .toArray(ArrowArrayWriter[]::new);
  }

  public void write(Row row) {
    for (int i = 0; i < fieldWriters.length; i++) {
      fieldWriters[i].write(row, i);
    }
    rowCount++;
  }

  public VectorSchemaRoot finish() {
    // Should set child vector count before set root count, otherwise we may got
    // `Array length did not match record batch length` when read ipc message
    Arrays.stream(fieldWriters).forEach(ArrowArrayWriter::finish);
    root.setRowCount(rowCount);
    return root;
  }

  public ArrowRecordBatch finishAsRecordBatch() {
    Arrays.stream(fieldWriters).forEach(ArrowArrayWriter::finish);
    root.setRowCount(rowCount);
    return unloader.getRecordBatch();
  }

  public void reset() {
    Arrays.stream(fieldWriters).forEach(ArrowArrayWriter::reset);
    root.setRowCount(0);
    rowCount = 0;
  }

  private static ArrowArrayWriter createFieldWriter(ValueVector vector) {
    DefaultTypeVisitor<ArrowArrayWriter> typeVisitor =
        new DefaultTypeVisitor<ArrowArrayWriter>() {

          @Override
          public ArrowArrayWriter visit(ArrowType.Bool type) {
            return new BooleanWriter((BitVector) vector);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Int type) {
            if (type.getIsSigned()) {
              int byteWidth = type.getBitWidth() / 8;
              switch (byteWidth) {
                case 1:
                  return new ByteWriter((TinyIntVector) vector);
                case 2:
                  return new ShortWriter((SmallIntVector) vector);
                case 4:
                  return new IntWriter((IntVector) vector);
                case 8:
                  return new LongWriter((BigIntVector) vector);
                default:
                  return unsupported(type);
              }
            }
            return unsupported(type);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.FloatingPoint type) {
            switch (type.getPrecision()) {
              case SINGLE:
                return new FloatWriter((Float4Vector) vector);
              case DOUBLE:
                return new DoubleWriter((Float8Vector) vector);
              default:
                return unsupported(type);
            }
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Date type) {
            if (type.getUnit() == DateUnit.DAY) {
              return new DateWriter((DateDayVector) vector);
            }
            return unsupported(type);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Timestamp type) {
            return new TimestampWriter((TimeStampMicroTZVector) vector);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Binary type) {
            return new BinaryWriter((VarBinaryVector) vector);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Decimal type) {
            return new DecimalWriter((DecimalVector) vector);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Utf8 type) {
            return new StringWriter((VarCharVector) vector);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Struct type) {
            StructVector structVector = (StructVector) (vector);
            ArrowArrayWriter[] childWriters =
                IntStream.range(0, structVector.size())
                    .mapToObj(i -> createFieldWriter(structVector.getChildByOrdinal(i)))
                    .toArray(ArrowArrayWriter[]::new);
            return new StructWriter(structVector, childWriters);
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.List type) {
            ListVector listVector = (ListVector) (vector);
            return new ListWriter(listVector, createFieldWriter(listVector.getDataVector()));
          }

          @Override
          public ArrowArrayWriter visit(ArrowType.Map type) {
            MapVector mapVector = (MapVector) (vector);
            StructVector structVector = (StructVector) (mapVector.getDataVector());
            ArrowArrayWriter keyWriter =
                createFieldWriter(structVector.getChild(MapVector.KEY_NAME));
            ArrowArrayWriter valueWriter =
                createFieldWriter(structVector.getChild(MapVector.VALUE_NAME));
            return new MapWriter(mapVector, keyWriter, valueWriter);
          }
        };
    try {
      return vector.getField().getType().accept(typeVisitor);
    } catch (RuntimeException e) {
      throw e;
    }
  }
}

abstract class ArrowArrayWriter {
  int rowCount = 0;

  void write(Getters getters, int index) {
    if (getters.isNullAt(index)) {
      appendNull();
    } else {
      appendValue(getters, index);
    }
    rowCount += 1;
  }

  abstract void appendValue(Getters getters, int index);

  abstract void appendNull();

  abstract ValueVector valueVector();

  void finish() {
    valueVector().setValueCount(rowCount);
  }

  void reset() {
    valueVector().reset();
    rowCount = 0;
  }
}

class BooleanWriter extends ArrowArrayWriter {
  private final BitVector valueVector;

  BooleanWriter(BitVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    if (getters.getBoolean(fieldIndex)) {
      valueVector.setSafe(rowCount, 1);
    } else {
      valueVector.setSafe(rowCount, 0);
    }
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class ByteWriter extends ArrowArrayWriter {
  private final TinyIntVector valueVector;

  ByteWriter(TinyIntVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getByte(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class ShortWriter extends ArrowArrayWriter {
  private final SmallIntVector valueVector;

  ShortWriter(SmallIntVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getInt16(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class IntWriter extends ArrowArrayWriter {
  private final IntVector valueVector;

  IntWriter(IntVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getInt32(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class LongWriter extends ArrowArrayWriter {
  private final BigIntVector valueVector;

  LongWriter(BigIntVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getInt64(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class FloatWriter extends ArrowArrayWriter {
  private final Float4Vector valueVector;

  FloatWriter(Float4Vector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getFloat32(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class DoubleWriter extends ArrowArrayWriter {
  private final Float8Vector valueVector;

  DoubleWriter(Float8Vector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getFloat64(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class DecimalWriter extends ArrowArrayWriter {
  private final DecimalVector valueVector;

  DecimalWriter(DecimalVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getDecimal(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class StringWriter extends ArrowArrayWriter {
  private final VarCharVector valueVector;

  StringWriter(VarCharVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    // TODO(chaokunyang) use copy if string is small.
    // Using byte buffer can avoid copy
    ByteBuffer buffer = getters.getBuffer(fieldIndex).sliceAsByteBuffer();
    valueVector.setSafe(rowCount, buffer, buffer.position(), buffer.remaining());
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class BinaryWriter extends ArrowArrayWriter {
  private final VarBinaryVector valueVector;

  BinaryWriter(VarBinaryVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    // TODO(chaokunyang) use copy if string is small.
    // Using byte buffer can avoid copy
    ByteBuffer buffer = getters.getBuffer(fieldIndex).sliceAsByteBuffer();
    valueVector.setSafe(rowCount, buffer, buffer.position(), buffer.remaining());
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class DateWriter extends ArrowArrayWriter {
  private final DateDayVector valueVector;

  DateWriter(DateDayVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getInt32(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class TimestampWriter extends ArrowArrayWriter {
  private final TimeStampMicroTZVector valueVector;

  TimestampWriter(TimeStampMicroTZVector valueVector) {
    this.valueVector = valueVector;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    valueVector.setSafe(rowCount, getters.getInt64(fieldIndex));
  }

  @Override
  void appendNull() {
    valueVector.setNull(rowCount);
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class ListWriter extends ArrowArrayWriter {
  private final ListVector valueVector;
  private final ArrowArrayWriter childWriter;

  ListWriter(ListVector valueVector, ArrowArrayWriter childWriter) {
    this.valueVector = valueVector;
    this.childWriter = childWriter;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    ArrayData array = getters.getArray(fieldIndex);
    valueVector.startNewValue(rowCount);
    for (int i = 0; i < array.numElements(); i++) {
      childWriter.write(array, i);
    }
    valueVector.endValue(rowCount, array.numElements());
  }

  @Override
  void appendNull() {}

  @Override
  void finish() {
    childWriter.finish();
    super.finish();
  }

  @Override
  void reset() {
    childWriter.reset();
    super.reset();
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class StructWriter extends ArrowArrayWriter {
  private final StructVector valueVector;
  private final ArrowArrayWriter[] childWriters;

  StructWriter(StructVector valueVector, ArrowArrayWriter[] childWriters) {
    this.valueVector = valueVector;
    this.childWriters = childWriters;
  }

  @Override
  void appendValue(Getters getters, int fieldIndex) {
    Row row = getters.getStruct(fieldIndex);
    for (int i = 0; i < childWriters.length; i++) {
      childWriters[i].write(row, i);
    }
    valueVector.setIndexDefined(rowCount);
  }

  @Override
  void appendNull() {
    for (ArrowArrayWriter childWriter : childWriters) {
      childWriter.appendNull();
      childWriter.rowCount++;
    }
    valueVector.setNull(rowCount);
  }

  @Override
  void finish() {
    Arrays.stream(childWriters).forEach(ArrowArrayWriter::finish);
    super.finish();
  }

  @Override
  void reset() {
    Arrays.stream(childWriters).forEach(ArrowArrayWriter::reset);
    super.reset();
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}

class MapWriter extends ArrowArrayWriter {
  private final MapVector valueVector;
  private final UnionMapWriter mapWriter;
  private final ArrowArrayWriter keyWriter;
  private final ArrowArrayWriter itemWriter;

  MapWriter(MapVector valueVector, ArrowArrayWriter keyWriter, ArrowArrayWriter itemWriter) {
    this.valueVector = valueVector;
    mapWriter = valueVector.getWriter();
    this.keyWriter = keyWriter;
    this.itemWriter = itemWriter;
  }

  @Override
  void appendValue(Getters getters, int index) {
    MapData map = getters.getMap(index);
    ArrayData keyArray = map.keyArray();
    ArrayData valueArray = map.valueArray();
    mapWriter.setPosition(rowCount);
    mapWriter.startMap();
    for (int i = 0; i < map.numElements(); i++) {
      mapWriter.startEntry();
      keyWriter.write(keyArray, i);
      itemWriter.write(valueArray, i);
      mapWriter.endEntry();
    }
    mapWriter.endMap();
  }

  @Override
  void appendNull() {}

  @Override
  void finish() {
    keyWriter.finish();
    itemWriter.finish();
    super.finish();
  }

  @Override
  void reset() {
    keyWriter.reset();
    itemWriter.reset();
    super.reset();
  }

  @Override
  ValueVector valueVector() {
    return valueVector;
  }
}
