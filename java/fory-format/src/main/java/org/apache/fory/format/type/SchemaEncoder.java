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

import static org.apache.fory.meta.MetaString.Encoding.ALL_TO_LOWER_SPECIAL;
import static org.apache.fory.meta.MetaString.Encoding.LOWER_UPPER_DIGIT_SPECIAL;
import static org.apache.fory.meta.MetaString.Encoding.UTF_8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.meta.MetaString;
import org.apache.fory.meta.MetaStringDecoder;
import org.apache.fory.meta.MetaStringEncoder;
import org.apache.fory.type.Types;

/**
 * Encoder for Fory row format Schema.
 *
 * <p>Schema binary format:
 *
 * <pre>
 * | version (1 byte) | num_fields (varint) | field1 | field2 | ... |
 *
 * Field format:
 * | header (1 byte) | name_size (varint if big) | name_bytes | type_info |
 *
 * Header byte:
 * - bits 0-1: encoding (0=UTF8, 1=ALL_TO_LOWER_SPECIAL, 2=LOWER_UPPER_DIGIT_SPECIAL)
 * - bits 2-5: name size - 1 (0-15, if 15 then varint follows for larger sizes)
 * - bit 6: nullable flag
 * - bit 7: reserved
 *
 * Type info:
 * - type_id (1 byte)
 * - For DECIMAL: precision (1 byte) + scale (1 byte)
 * - For LIST: element type (recursive)
 * - For MAP: key type (recursive) + value type (recursive)
 * - For STRUCT: num_fields (varint) + fields (recursive)
 * </pre>
 */
public class SchemaEncoder {

  private static final byte SCHEMA_VERSION = 1;
  private static final int FIELD_NAME_SIZE_THRESHOLD = 15;

  private static final MetaString.Encoding[] FIELD_NAME_ENCODINGS =
      new MetaString.Encoding[] {UTF_8, ALL_TO_LOWER_SPECIAL, LOWER_UPPER_DIGIT_SPECIAL};
  private static final List<MetaString.Encoding> FIELD_NAME_ENCODINGS_LIST =
      Arrays.asList(FIELD_NAME_ENCODINGS);

  private static final MetaStringEncoder FIELD_NAME_ENCODER = new MetaStringEncoder('$', '_');
  private static final MetaStringDecoder FIELD_NAME_DECODER = new MetaStringDecoder('$', '_');

  /**
   * Serializes a Schema to a byte array.
   *
   * @param schema the schema to serialize
   * @return serialized bytes
   */
  public static byte[] toBytes(Schema schema) {
    MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(64);
    toBytes(schema, buffer);
    return buffer.getBytes(0, buffer.writerIndex());
  }

  /**
   * Serializes a Schema to a MemoryBuffer.
   *
   * @param schema the schema to serialize
   * @param buffer the buffer to write to
   */
  public static void toBytes(Schema schema, MemoryBuffer buffer) {
    buffer.writeByte(SCHEMA_VERSION);
    buffer.writeVarUint32Small7(schema.numFields());
    for (Field field : schema.fields()) {
      writeField(buffer, field);
    }
  }

  /**
   * Deserializes a Schema from a byte array.
   *
   * @param bytes the serialized bytes
   * @return the deserialized schema
   */
  public static Schema fromBytes(byte[] bytes) {
    MemoryBuffer buffer = MemoryBuffer.fromByteArray(bytes);
    return fromBytes(buffer);
  }

  /**
   * Deserializes a Schema from a MemoryBuffer.
   *
   * @param buffer the buffer to read from
   * @return the deserialized schema
   */
  public static Schema fromBytes(MemoryBuffer buffer) {
    byte version = buffer.readByte();
    if (version != SCHEMA_VERSION) {
      throw new IllegalArgumentException(
          "Unsupported schema version: " + version + ", expected: " + SCHEMA_VERSION);
    }
    int numFields = buffer.readVarUint32Small7();
    List<Field> fields = new ArrayList<>(numFields);
    for (int i = 0; i < numFields; i++) {
      fields.add(readField(buffer));
    }
    return new Schema(fields);
  }

  private static void writeField(MemoryBuffer buffer, Field field) {
    MetaString metaString = FIELD_NAME_ENCODER.encode(field.name(), FIELD_NAME_ENCODINGS);
    int encodingIndex = FIELD_NAME_ENCODINGS_LIST.indexOf(metaString.getEncoding());
    byte[] nameBytes = metaString.getBytes();
    int nameSize = nameBytes.length;

    // Build header byte
    int header = encodingIndex & 0x03; // bits 0-1: encoding
    boolean bigSize = nameSize > FIELD_NAME_SIZE_THRESHOLD;
    if (bigSize) {
      header |= (FIELD_NAME_SIZE_THRESHOLD << 2); // bits 2-5: max value means varint follows
    } else {
      header |= ((nameSize - 1) << 2); // bits 2-5: name size - 1
    }
    if (field.nullable()) {
      header |= 0x40; // bit 6: nullable
    }
    buffer.writeByte(header);

    if (bigSize) {
      buffer.writeVarUint32Small7(nameSize - FIELD_NAME_SIZE_THRESHOLD);
    }
    buffer.writeBytes(nameBytes);

    writeType(buffer, field.type());
  }

  private static Field readField(MemoryBuffer buffer) {
    int header = buffer.readByte() & 0xFF;
    int encodingIndex = header & 0x03;
    int nameSizeMinus1 = (header >> 2) & 0x0F;
    boolean nullable = (header & 0x40) != 0;

    int nameSize;
    if (nameSizeMinus1 == FIELD_NAME_SIZE_THRESHOLD) {
      nameSize = buffer.readVarUint32Small7() + FIELD_NAME_SIZE_THRESHOLD;
    } else {
      nameSize = nameSizeMinus1 + 1;
    }

    byte[] nameBytes = buffer.readBytes(nameSize);
    MetaString.Encoding encoding = FIELD_NAME_ENCODINGS[encodingIndex];
    String name = FIELD_NAME_DECODER.decode(nameBytes, encoding);

    DataType type = readType(buffer);
    return new Field(name, type, nullable);
  }

  private static void writeType(MemoryBuffer buffer, DataType type) {
    int typeId = type.typeId();
    buffer.writeByte(typeId);

    if (type instanceof DataTypes.DecimalType) {
      DataTypes.DecimalType decimalType = (DataTypes.DecimalType) type;
      buffer.writeByte(decimalType.precision());
      buffer.writeByte(decimalType.scale());
    } else if (type instanceof DataTypes.ListType) {
      DataTypes.ListType listType = (DataTypes.ListType) type;
      writeField(buffer, listType.valueField());
    } else if (type instanceof DataTypes.MapType) {
      DataTypes.MapType mapType = (DataTypes.MapType) type;
      writeField(buffer, mapType.keyField());
      writeField(buffer, mapType.itemField());
    } else if (type instanceof DataTypes.StructType) {
      DataTypes.StructType structType = (DataTypes.StructType) type;
      buffer.writeVarUint32Small7(structType.numFields());
      for (Field field : structType.fields()) {
        writeField(buffer, field);
      }
    }
  }

  private static DataType readType(MemoryBuffer buffer) {
    int typeId = buffer.readByte() & 0xFF;

    switch (typeId) {
      case Types.BOOL:
        return DataTypes.bool();
      case Types.INT8:
        return DataTypes.int8();
      case Types.INT16:
        return DataTypes.int16();
      case Types.INT32:
        return DataTypes.int32();
      case Types.INT64:
        return DataTypes.int64();
      case Types.FLOAT16:
        return DataTypes.float16();
      case Types.FLOAT32:
        return DataTypes.float32();
      case Types.FLOAT64:
        return DataTypes.float64();
      case Types.STRING:
        return DataTypes.utf8();
      case Types.BINARY:
        return DataTypes.binary();
      case Types.DURATION:
        return DataTypes.duration();
      case Types.TIMESTAMP:
        return DataTypes.timestamp();
      case Types.DATE:
        return DataTypes.date32();
      case Types.DECIMAL:
        int precision = buffer.readByte() & 0xFF;
        int scale = buffer.readByte() & 0xFF;
        return DataTypes.decimal(precision, scale);
      case Types.LIST:
        Field valueField = readField(buffer);
        return new DataTypes.ListType(valueField);
      case Types.MAP:
        Field keyField = readField(buffer);
        Field itemField = readField(buffer);
        return new DataTypes.MapType(keyField.type(), itemField.type());
      case Types.STRUCT:
        int numFields = buffer.readVarUint32Small7();
        List<Field> fields = new ArrayList<>(numFields);
        for (int i = 0; i < numFields; i++) {
          fields.add(readField(buffer));
        }
        return new DataTypes.StructType(fields);
      default:
        throw new IllegalArgumentException("Unknown type id: " + typeId);
    }
  }
}
