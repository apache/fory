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

import static org.apache.fory.util.Preconditions.checkArgument;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.arrow.vector.complex.MapVector;
import org.apache.arrow.vector.ipc.ReadChannel;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.DateUnit;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.TimeUnit;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.fory.exception.ForyException;
import org.apache.fory.io.MemoryBufferOutputStream;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.util.DecimalUtils;
import org.apache.fory.util.Preconditions;

/** Arrow data type utils. */
public class DataTypes {
  public static Field PRIMITIVE_BOOLEAN_ARRAY_FIELD =
      primitiveArrayField(org.apache.arrow.vector.types.pojo.ArrowType.Bool.INSTANCE);
  public static Field PRIMITIVE_BYTE_ARRAY_FIELD = primitiveArrayField(intType(8));
  public static Field PRIMITIVE_SHORT_ARRAY_FIELD = primitiveArrayField(intType(16));
  public static Field PRIMITIVE_INT_ARRAY_FIELD = primitiveArrayField(intType(32));

  public static Field PRIMITIVE_LONG_ARRAY_FIELD = primitiveArrayField(intType(64));
  public static Field PRIMITIVE_FLOAT_ARRAY_FIELD =
      primitiveArrayField(
          new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
              FloatingPointPrecision.SINGLE));
  public static Field PRIMITIVE_DOUBLE_ARRAY_FIELD =
      primitiveArrayField(
          new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
              FloatingPointPrecision.DOUBLE));
  // Array item field default name
  public static final String ARRAY_ITEM_NAME = "item";

  private static final org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeVisitor<Integer>
      typeWidthVisitor =
          new DefaultTypeVisitor<Integer>() {

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Struct type) {
              return -1;
            }

            @Override
            public Integer visit(
                org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeBinary type) {
              return type.getByteWidth();
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.List type) {
              return -1;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Map type) {
              return -1;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Bool type) {
              return 1;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Int type) {
              return (type.getBitWidth() + 7) / 8;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint type) {
              switch (type.getPrecision()) {
                case SINGLE:
                  return 4;
                case DOUBLE:
                  return 8;
                default:
                  return unsupported(type);
              }
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Date type) {
              return 4;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Timestamp type) {
              return 8;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Binary type) {
              return -1;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Decimal type) {
              return -1;
            }

            @Override
            public Integer visit(org.apache.arrow.vector.types.pojo.ArrowType.Utf8 type) {
              return -1;
            }
          };

  private static final org.apache.arrow.vector.types.pojo.ArrowType.ArrowTypeVisitor<ArrowType>
      typeIdVisitor =
          new DefaultTypeVisitor<ArrowType>() {

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Bool type) {
              return ArrowType.BOOL;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Int type) {
              if (type.getIsSigned()) {
                int byteWidth = type.getBitWidth() / 8;
                switch (byteWidth) {
                  case 1:
                    return ArrowType.INT8;
                  case 2:
                    return ArrowType.INT16;
                  case 4:
                    return ArrowType.INT32;
                  case 8:
                    return ArrowType.INT64;
                  default:
                    return unsupported(type);
                }
              }
              return unsupported(type);
            }

            @Override
            public ArrowType visit(
                org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint type) {
              switch (type.getPrecision()) {
                case SINGLE:
                  return ArrowType.FLOAT;
                case DOUBLE:
                  return ArrowType.DOUBLE;
                default:
                  return unsupported(type);
              }
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Date type) {
              switch (type.getUnit()) {
                case DAY:
                  return ArrowType.DATE32;
                case MILLISECOND:
                  return ArrowType.DATE64;
                default:
                  return unsupported(type);
              }
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Timestamp type) {
              return ArrowType.TIMESTAMP;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Binary type) {
              return ArrowType.BINARY;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Decimal type) {
              return ArrowType.DECIMAL;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Utf8 type) {
              return ArrowType.STRING;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Struct type) {
              return ArrowType.STRUCT;
            }

            @Override
            public ArrowType visit(
                org.apache.arrow.vector.types.pojo.ArrowType.FixedSizeBinary type) {
              return ArrowType.FIXED_SIZE_BINARY;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.List type) {
              return ArrowType.LIST;
            }

            @Override
            public ArrowType visit(org.apache.arrow.vector.types.pojo.ArrowType.Map type) {
              return ArrowType.MAP;
            }
          };

  public static int getTypeWidth(org.apache.arrow.vector.types.pojo.ArrowType type) {
    return type.accept(typeWidthVisitor);
  }

  public static ArrowType getTypeId(org.apache.arrow.vector.types.pojo.ArrowType type) {
    return type.accept(typeIdVisitor);
  }

  public static short getTypeIdValue(org.apache.arrow.vector.types.pojo.ArrowType type) {
    return type.accept(typeIdVisitor).getId();
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Bool bool() {
    return org.apache.arrow.vector.types.pojo.ArrowType.Bool.INSTANCE;
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Int intType(int bitWidth) {
    return new org.apache.arrow.vector.types.pojo.ArrowType.Int(bitWidth, true);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Int int8() {
    return intType(8);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Int int16() {
    return intType(16);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Int int32() {
    return intType(32);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Int int64() {
    return intType(64);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint float32() {
    return new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
        FloatingPointPrecision.SINGLE);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint float64() {
    return new org.apache.arrow.vector.types.pojo.ArrowType.FloatingPoint(
        FloatingPointPrecision.DOUBLE);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Date date32() {
    return new org.apache.arrow.vector.types.pojo.ArrowType.Date(DateUnit.DAY);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Date date64() {
    return new org.apache.arrow.vector.types.pojo.ArrowType.Date(DateUnit.MILLISECOND);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Timestamp timestamp() {
    return new org.apache.arrow.vector.types.pojo.ArrowType.Timestamp(TimeUnit.MICROSECOND, null);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Binary binary() {
    return org.apache.arrow.vector.types.pojo.ArrowType.Binary.INSTANCE;
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Utf8 utf8() {
    return org.apache.arrow.vector.types.pojo.ArrowType.Utf8.INSTANCE;
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Decimal decimal() {
    return decimal(DecimalUtils.MAX_PRECISION, DecimalUtils.MAX_SCALE);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Decimal decimal(
      int precision, int scale) {
    return new org.apache.arrow.vector.types.pojo.ArrowType.Decimal(precision, scale);
  }

  public static org.apache.arrow.vector.types.pojo.ArrowType.Decimal bigintDecimal() {
    return decimal(DecimalUtils.MAX_PRECISION, 0);
  }

  /* ========================= field utils ========================= */
  public static Field field(String name, FieldType fieldType) {
    return field(name, fieldType, Collections.emptyList());
  }

  public static Field field(
      String name,
      boolean nullable,
      org.apache.arrow.vector.types.pojo.ArrowType type,
      Field... children) {
    return field(name, new FieldType(nullable, type, null), children);
  }

  public static Field field(String name, FieldType fieldType, Field... children) {
    return field(name, fieldType, Arrays.asList(children));
  }

  public static Field field(
      String name,
      boolean nullable,
      org.apache.arrow.vector.types.pojo.ArrowType type,
      List<Field> children) {
    return field(name, new FieldType(nullable, type, null), children);
  }

  public static Field field(
      String name, org.apache.arrow.vector.types.pojo.ArrowType type, Field... children) {
    return field(name, true, type, children);
  }

  public static Field field(String name, FieldType fieldType, List<Field> children) {
    return new Field(name, fieldType, children);
  }

  public static Field notNullField(
      String name, org.apache.arrow.vector.types.pojo.ArrowType type, Field... children) {
    return field(name, false, type, children);
  }

  public static FieldType notNullFieldType(org.apache.arrow.vector.types.pojo.ArrowType type) {
    return new FieldType(false, type, null);
  }

  /* ========================= array field utils ========================= */
  public static Field primitiveArrayField(org.apache.arrow.vector.types.pojo.ArrowType type) {
    return primitiveArrayField("", type);
  }

  public static Field primitiveArrayField(
      String name, org.apache.arrow.vector.types.pojo.ArrowType type) {
    return field(
        name,
        FieldType.nullable(org.apache.arrow.vector.types.pojo.ArrowType.List.INSTANCE),
        Collections.singletonList(field(ARRAY_ITEM_NAME, false, type)));
  }

  public static Field arrayField(org.apache.arrow.vector.types.pojo.ArrowType type) {
    return arrayField("", type);
  }

  public static Field arrayField(String name, org.apache.arrow.vector.types.pojo.ArrowType type) {
    return field(
        name,
        FieldType.nullable(org.apache.arrow.vector.types.pojo.ArrowType.List.INSTANCE),
        Collections.singletonList(field(ARRAY_ITEM_NAME, true, type)));
  }

  public static Field arrayField(FieldType valueType) {
    return arrayField("", valueType);
  }

  public static Field arrayField(String name, FieldType valueType) {
    return field(
        name,
        FieldType.nullable(org.apache.arrow.vector.types.pojo.ArrowType.List.INSTANCE),
        Collections.singletonList(field(ARRAY_ITEM_NAME, valueType)));
  }

  public static Field arrayField(Field valueField) {
    return arrayField("", valueField);
  }

  public static Field arrayField(String name, Field valueField) {
    return field(
        name,
        FieldType.nullable(org.apache.arrow.vector.types.pojo.ArrowType.List.INSTANCE),
        Collections.singletonList(valueField));
  }

  public static Field arrayElementField(Field field) {
    return field.getChildren().get(0);
  }

  /* ========================= map field utils start ========================= */
  public static Field mapField(
      org.apache.arrow.vector.types.pojo.ArrowType keyType,
      org.apache.arrow.vector.types.pojo.ArrowType itemType) {
    return mapField("", keyType, itemType);
  }

  public static Field mapField(
      String name,
      org.apache.arrow.vector.types.pojo.ArrowType keyType,
      org.apache.arrow.vector.types.pojo.ArrowType itemType) {
    return mapField(
        name,
        field(MapVector.KEY_NAME, false, keyType),
        field(MapVector.VALUE_NAME, true, itemType));
  }

  /**
   * Map data is nested data where each value is a variable number of key-item pairs. Maps can be
   * recursively nested, for example map(utf8, map(utf8, int32)). see more about MapType in type.h
   */
  public static Field mapField(String name, Field keyField, Field itemField) {
    Preconditions.checkArgument(!keyField.isNullable(), "Map's keys must be non-nullable");
    // Map's key-item pairs must be non-nullable structs
    Field valueField = structField(false, keyField, itemField);
    return field(
        name, true, new org.apache.arrow.vector.types.pojo.ArrowType.Map(false), valueField);
  }

  public static Field keyFieldForMap(Field mapField) {
    return mapField.getChildren().get(0).getChildren().get(0);
  }

  public static Field itemFieldForMap(Field mapField) {
    return mapField.getChildren().get(0).getChildren().get(1);
  }

  public static Field keyArrayFieldForMap(Field mapField) {
    return arrayField("keys", keyFieldForMap(mapField));
  }

  public static Field itemArrayFieldForMap(Field mapField) {
    return arrayField("items", itemFieldForMap(mapField));
  }

  /* ========================= struct field utils start ========================= */
  public static Schema schemaFromStructField(Field structField) {
    return new Schema(structField.getChildren(), structField.getMetadata());
  }

  public static Schema createSchema(Field field) {
    return new Schema(field.getChildren(), field.getMetadata());
  }

  public static Field structField(boolean nullable, Field... fields) {
    return structField("", nullable, fields);
  }

  public static Field structField(String name, boolean nullable, Field... fields) {
    return field(
        name, nullable, org.apache.arrow.vector.types.pojo.ArrowType.Struct.INSTANCE, fields);
  }

  public static Field structField(String name, boolean nullable, List<Field> fields) {
    return field(
        name, nullable, org.apache.arrow.vector.types.pojo.ArrowType.Struct.INSTANCE, fields);
  }

  /* ========================= struct field utils end ========================= */

  public static Field fieldOfSchema(Schema schema, int index) {
    return schema.getFields().get(index);
  }

  public static void serializeSchema(Schema schema, MemoryBuffer buffer) {
    try (MemoryBufferOutputStream outputStream = new MemoryBufferOutputStream(buffer);
        WriteChannel writeChannel = new WriteChannel(Channels.newChannel(outputStream))) {
      MessageSerializer.serialize(writeChannel, schema);
    } catch (IOException e) {
      throw new ForyException(String.format("Write schema %s failed", schema), e);
    }
  }

  public static byte[] serializeSchema(Schema schema) {
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        WriteChannel writeChannel = new WriteChannel(Channels.newChannel(outputStream))) {
      MessageSerializer.serialize(writeChannel, schema);
      return outputStream.toByteArray();
    } catch (IOException e) {
      throw new ForyException(String.format("Write schema %s failed", schema), e);
    }
  }

  public static Schema deserializeSchema(byte[] bytes) {
    try (ReadChannel readChannel =
        new ReadChannel(Channels.newChannel(new ByteArrayInputStream(bytes)))) {
      return MessageSerializer.deserializeSchema(readChannel);
    } catch (IOException e) {
      throw new ForyException("Deserialize schema failed", e);
    }
  }

  public static long computeSchemaHash(Schema schema) {
    long hash = 17;
    for (Field field : schema.getFields()) {
      hash = computeHash(hash, field);
    }
    return hash;
  }

  private static long computeHash(long hash, Field field) {
    ArrowType typeID = getTypeId(field.getType());
    while (true) {
      try {
        hash = Math.addExact(Math.multiplyExact(hash, 31), (long) typeID.getId());
        break;
      } catch (ArithmeticException e) {
        hash = hash >> 2;
      }
    }
    List<Field> fields = new ArrayList<>();
    switch (typeID) {
      case LIST:
        fields.add(arrayElementField(field));
        break;
      case MAP:
        {
          fields.add(keyFieldForMap(field));
          fields.add(itemFieldForMap(field));
          break;
        }
      case STRUCT:
        {
          fields.addAll(field.getChildren());
          break;
        }
      default:
        checkArgument(
            field.getChildren().size() == 0,
            "field type should not be nested, but got type id %s field %s.",
            typeID,
            field);
    }
    for (Field child : fields) {
      hash = computeHash(hash, child);
    }
    return hash;
  }
}
