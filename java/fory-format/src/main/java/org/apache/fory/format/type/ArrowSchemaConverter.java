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

package org.apache.fory.format.type;

import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.FieldType;

/**
 * Converter between Fory schema types and Apache Arrow schema types.
 *
 * <p>This class provides bidirectional conversion between the Fory type system (DataType, Field,
 * Schema) and Apache Arrow type system (ArrowType, Field, Schema).
 */
public class ArrowSchemaConverter {

  // ============================================================================
  // Fory to Arrow conversion
  // ============================================================================

  /**
   * Converts a Fory DataType to an Apache Arrow ArrowType.
   *
   * @param foryType the Fory data type
   * @return the corresponding Arrow type
   */
  public static ArrowType toArrowType(DataType foryType) {
    if (foryType instanceof DataTypes.BooleanType) {
      return ArrowType.Bool.INSTANCE;
    } else if (foryType instanceof DataTypes.Int8Type) {
      return new ArrowType.Int(8, true);
    } else if (foryType instanceof DataTypes.Int16Type) {
      return new ArrowType.Int(16, true);
    } else if (foryType instanceof DataTypes.Int32Type) {
      return new ArrowType.Int(32, true);
    } else if (foryType instanceof DataTypes.Int64Type) {
      return new ArrowType.Int(64, true);
    } else if (foryType instanceof DataTypes.Float16Type) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.HALF);
    } else if (foryType instanceof DataTypes.Float32Type) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);
    } else if (foryType instanceof DataTypes.Float64Type) {
      return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
    } else if (foryType instanceof DataTypes.StringType) {
      return ArrowType.Utf8.INSTANCE;
    } else if (foryType instanceof DataTypes.BinaryType) {
      return ArrowType.Binary.INSTANCE;
    } else if (foryType instanceof DataTypes.DurationType) {
      return new ArrowType.Duration(TimeUnit.NANOSECOND);
    } else if (foryType instanceof DataTypes.TimestampType) {
      return new ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
    } else if (foryType instanceof DataTypes.LocalDateType) {
      return new ArrowType.Date(DateUnit.DAY);
    } else if (foryType instanceof DataTypes.DecimalType) {
      DataTypes.DecimalType decimalType = (DataTypes.DecimalType) foryType;
      return new ArrowType.Decimal(decimalType.precision(), decimalType.scale());
    } else if (foryType instanceof DataTypes.ListType) {
      return ArrowType.List.INSTANCE;
    } else if (foryType instanceof DataTypes.StructType) {
      return ArrowType.Struct.INSTANCE;
    } else if (foryType instanceof DataTypes.MapType) {
      return new ArrowType.Map(false);
    } else {
      throw new UnsupportedOperationException("Unsupported Fory type: " + foryType);
    }
  }

  /**
   * Converts a Fory Field to an Apache Arrow Field.
   *
   * @param foryField the Fory field
   * @return the corresponding Arrow field
   */
  public static org.apache.arrow.vector.types.pojo.Field toArrowField(Field foryField) {
    DataType foryType = foryField.type();
    ArrowType arrowType = toArrowType(foryType);
    FieldType fieldType = new FieldType(foryField.nullable(), arrowType, null);

    List<org.apache.arrow.vector.types.pojo.Field> children = new ArrayList<>();
    if (foryType instanceof DataTypes.ListType) {
      DataTypes.ListType listType = (DataTypes.ListType) foryType;
      children.add(toArrowField(listType.valueField()));
    } else if (foryType instanceof DataTypes.StructType) {
      DataTypes.StructType structType = (DataTypes.StructType) foryType;
      for (Field child : structType.fields()) {
        children.add(toArrowField(child));
      }
    } else if (foryType instanceof DataTypes.MapType) {
      DataTypes.MapType mapType = (DataTypes.MapType) foryType;
      // Arrow maps have a single child: a struct with key and value fields
      List<org.apache.arrow.vector.types.pojo.Field> kvFields = new ArrayList<>();
      kvFields.add(toArrowField(mapType.keyField()));
      kvFields.add(toArrowField(mapType.itemField()));
      org.apache.arrow.vector.types.pojo.Field entriesField =
          new org.apache.arrow.vector.types.pojo.Field(
              "entries", new FieldType(false, ArrowType.Struct.INSTANCE, null), kvFields);
      children.add(entriesField);
    }

    return new org.apache.arrow.vector.types.pojo.Field(
        foryField.name(), fieldType, children.isEmpty() ? null : children);
  }

  /**
   * Converts a Fory Schema to an Apache Arrow Schema.
   *
   * @param forySchema the Fory schema
   * @return the corresponding Arrow schema
   */
  public static org.apache.arrow.vector.types.pojo.Schema toArrowSchema(Schema forySchema) {
    List<org.apache.arrow.vector.types.pojo.Field> arrowFields = new ArrayList<>();
    for (Field foryField : forySchema.fields()) {
      arrowFields.add(toArrowField(foryField));
    }
    return new org.apache.arrow.vector.types.pojo.Schema(arrowFields, forySchema.metadata());
  }

  // ============================================================================
  // Arrow to Fory conversion
  // ============================================================================

  /**
   * Converts an Apache Arrow ArrowType to a Fory DataType.
   *
   * @param arrowType the Arrow type
   * @return the corresponding Fory data type
   */
  public static DataType fromArrowType(ArrowType arrowType) {
    if (arrowType instanceof ArrowType.Bool) {
      return DataTypes.bool();
    } else if (arrowType instanceof ArrowType.Int) {
      ArrowType.Int intType = (ArrowType.Int) arrowType;
      if (intType.getIsSigned()) {
        switch (intType.getBitWidth()) {
          case 8:
            return DataTypes.int8();
          case 16:
            return DataTypes.int16();
          case 32:
            return DataTypes.int32();
          case 64:
            return DataTypes.int64();
          default:
            break;
        }
      }
      throw new UnsupportedOperationException("Unsupported integer type: " + arrowType);
    } else if (arrowType instanceof ArrowType.FloatingPoint) {
      ArrowType.FloatingPoint fpType = (ArrowType.FloatingPoint) arrowType;
      switch (fpType.getPrecision()) {
        case HALF:
          return DataTypes.float16();
        case SINGLE:
          return DataTypes.float32();
        case DOUBLE:
          return DataTypes.float64();
        default:
          throw new UnsupportedOperationException(
              "Unsupported floating point precision: " + fpType.getPrecision());
      }
    } else if (arrowType instanceof ArrowType.Utf8) {
      return DataTypes.utf8();
    } else if (arrowType instanceof ArrowType.Binary) {
      return DataTypes.binary();
    } else if (arrowType instanceof ArrowType.Duration) {
      return DataTypes.duration();
    } else if (arrowType instanceof ArrowType.Timestamp) {
      return DataTypes.timestamp();
    } else if (arrowType instanceof ArrowType.Date) {
      return DataTypes.date32();
    } else if (arrowType instanceof ArrowType.Decimal) {
      ArrowType.Decimal decimalType = (ArrowType.Decimal) arrowType;
      return DataTypes.decimal(decimalType.getPrecision(), decimalType.getScale());
    } else if (arrowType instanceof ArrowType.List) {
      // ListType needs child field, will be handled in fromArrowField
      return null;
    } else if (arrowType instanceof ArrowType.Struct) {
      // StructType needs child fields, will be handled in fromArrowField
      return null;
    } else if (arrowType instanceof ArrowType.Map) {
      // MapType needs child fields, will be handled in fromArrowField
      return null;
    }
    throw new UnsupportedOperationException("Unsupported Arrow type: " + arrowType);
  }

  /**
   * Converts an Apache Arrow Field to a Fory Field.
   *
   * @param arrowField the Arrow field
   * @return the corresponding Fory field
   */
  public static Field fromArrowField(org.apache.arrow.vector.types.pojo.Field arrowField) {
    ArrowType arrowType = arrowField.getType();
    DataType foryType;

    if (arrowType instanceof ArrowType.List) {
      org.apache.arrow.vector.types.pojo.Field valueField = arrowField.getChildren().get(0);
      Field foryValueField = fromArrowField(valueField);
      foryType = new DataTypes.ListType(foryValueField);
    } else if (arrowType instanceof ArrowType.Struct) {
      List<Field> childFields = new ArrayList<>();
      for (org.apache.arrow.vector.types.pojo.Field child : arrowField.getChildren()) {
        childFields.add(fromArrowField(child));
      }
      foryType = new DataTypes.StructType(childFields);
    } else if (arrowType instanceof ArrowType.Map) {
      // Arrow map has a single child struct with key and value
      org.apache.arrow.vector.types.pojo.Field entriesField = arrowField.getChildren().get(0);
      org.apache.arrow.vector.types.pojo.Field keyField = entriesField.getChildren().get(0);
      org.apache.arrow.vector.types.pojo.Field valueField = entriesField.getChildren().get(1);
      Field foryKeyField = fromArrowField(keyField);
      Field foryValueField = fromArrowField(valueField);
      foryType = new DataTypes.MapType(foryKeyField.type(), foryValueField.type());
    } else {
      foryType = fromArrowType(arrowType);
    }

    return new Field(arrowField.getName(), foryType, arrowField.isNullable());
  }

  /**
   * Converts an Apache Arrow Schema to a Fory Schema.
   *
   * @param arrowSchema the Arrow schema
   * @return the corresponding Fory schema
   */
  public static Schema fromArrowSchema(org.apache.arrow.vector.types.pojo.Schema arrowSchema) {
    List<Field> foryFields = new ArrayList<>();
    for (org.apache.arrow.vector.types.pojo.Field arrowField : arrowSchema.getFields()) {
      foryFields.add(fromArrowField(arrowField));
    }
    return new Schema(foryFields, arrowSchema.getCustomMetadata());
  }
}
