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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.type.Types;
import org.apache.fory.util.DecimalUtils;
import org.apache.fory.util.Preconditions;

/**
 * Type system for Fory row format.
 *
 * <p>This class defines the schema types used by the Fory row format for binary serialization. The
 * type system includes:
 *
 * <ul>
 *   <li>Primitive types: bool, int8/16/32/64, float16/32/64
 *   <li>Variable-width types: string (utf8), binary
 *   <li>Temporal types: timestamp, duration, date32
 *   <li>Decimal type with configurable precision and scale
 *   <li>Composite types: list, map, struct
 * </ul>
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * Schema schema = DataTypes.schema(Arrays.asList(
 *     DataTypes.field("id", DataTypes.int64()),
 *     DataTypes.field("name", DataTypes.utf8()),
 *     DataTypes.field("scores", DataTypes.list(DataTypes.float32()))
 * ));
 * }</pre>
 */
public class DataTypes {

  // Type IDs - use constants from Types class for consistency
  public static final int TYPE_BOOL = Types.BOOL;
  public static final int TYPE_INT8 = Types.INT8;
  public static final int TYPE_INT16 = Types.INT16;
  public static final int TYPE_INT32 = Types.INT32;
  public static final int TYPE_INT64 = Types.INT64;
  public static final int TYPE_FLOAT16 = Types.FLOAT16;
  public static final int TYPE_FLOAT32 = Types.FLOAT32;
  public static final int TYPE_FLOAT64 = Types.FLOAT64;
  public static final int TYPE_STRING = Types.STRING;
  public static final int TYPE_STRUCT = Types.STRUCT;
  public static final int TYPE_LIST = Types.LIST;
  public static final int TYPE_MAP = Types.MAP;
  public static final int TYPE_DURATION = Types.DURATION;
  public static final int TYPE_TIMESTAMP = Types.TIMESTAMP;
  public static final int TYPE_LOCAL_DATE = Types.DATE;
  public static final int TYPE_DECIMAL = Types.DECIMAL;
  public static final int TYPE_BINARY = Types.BINARY;

  // Array item field default name
  public static final String ARRAY_ITEM_NAME = "item";

  // Map key and value field names
  public static final String MAP_KEY_NAME = "key";
  public static final String MAP_VALUE_NAME = "value";

  // Pre-built primitive array fields for convenience
  public static final Field PRIMITIVE_BOOLEAN_ARRAY_FIELD =
      primitiveArrayField(bool(), ARRAY_ITEM_NAME, false);
  public static final Field PRIMITIVE_BYTE_ARRAY_FIELD =
      primitiveArrayField(int8(), ARRAY_ITEM_NAME, false);
  public static final Field PRIMITIVE_SHORT_ARRAY_FIELD =
      primitiveArrayField(int16(), ARRAY_ITEM_NAME, false);
  public static final Field PRIMITIVE_INT_ARRAY_FIELD =
      primitiveArrayField(int32(), ARRAY_ITEM_NAME, false);
  public static final Field PRIMITIVE_LONG_ARRAY_FIELD =
      primitiveArrayField(int64(), ARRAY_ITEM_NAME, false);
  public static final Field PRIMITIVE_FLOAT_ARRAY_FIELD =
      primitiveArrayField(float32(), ARRAY_ITEM_NAME, false);
  public static final Field PRIMITIVE_DOUBLE_ARRAY_FIELD =
      primitiveArrayField(float64(), ARRAY_ITEM_NAME, false);

  // ============================================================================
  // Fixed-width primitive types
  // ============================================================================

  /** Boolean type (stored as 8-bit value). */
  public static class BooleanType extends FixedWidthType {
    public static final BooleanType INSTANCE = new BooleanType();

    private BooleanType() {
      super(TYPE_BOOL, 8);
    }

    @Override
    public String name() {
      return "bool";
    }
  }

  /** Signed 8-bit integer. */
  public static class Int8Type extends FixedWidthType {
    public static final Int8Type INSTANCE = new Int8Type();

    private Int8Type() {
      super(TYPE_INT8, 8);
    }

    @Override
    public String name() {
      return "int8";
    }
  }

  /** Signed 16-bit integer. */
  public static class Int16Type extends FixedWidthType {
    public static final Int16Type INSTANCE = new Int16Type();

    private Int16Type() {
      super(TYPE_INT16, 16);
    }

    @Override
    public String name() {
      return "int16";
    }
  }

  /** Signed 32-bit integer. */
  public static class Int32Type extends FixedWidthType {
    public static final Int32Type INSTANCE = new Int32Type();

    private Int32Type() {
      super(TYPE_INT32, 32);
    }

    @Override
    public String name() {
      return "int32";
    }
  }

  /** Signed 64-bit integer. */
  public static class Int64Type extends FixedWidthType {
    public static final Int64Type INSTANCE = new Int64Type();

    private Int64Type() {
      super(TYPE_INT64, 64);
    }

    @Override
    public String name() {
      return "int64";
    }
  }

  /** 16-bit floating point (half precision). */
  public static class Float16Type extends FixedWidthType {
    public static final Float16Type INSTANCE = new Float16Type();

    private Float16Type() {
      super(TYPE_FLOAT16, 16);
    }

    @Override
    public String name() {
      return "float16";
    }
  }

  /** 32-bit floating point (single precision). */
  public static class Float32Type extends FixedWidthType {
    public static final Float32Type INSTANCE = new Float32Type();

    private Float32Type() {
      super(TYPE_FLOAT32, 32);
    }

    @Override
    public String name() {
      return "float";
    }
  }

  /** 64-bit floating point (double precision). */
  public static class Float64Type extends FixedWidthType {
    public static final Float64Type INSTANCE = new Float64Type();

    private Float64Type() {
      super(TYPE_FLOAT64, 64);
    }

    @Override
    public String name() {
      return "double";
    }
  }

  // ============================================================================
  // Variable-width types
  // ============================================================================

  /** UTF-8 encoded string (variable-width). */
  public static class StringType extends DataType {
    public static final StringType INSTANCE = new StringType();

    private StringType() {
      super(TYPE_STRING);
    }

    @Override
    public String name() {
      return "utf8";
    }
  }

  /** Raw binary data (variable-width). */
  public static class BinaryType extends DataType {
    public static final BinaryType INSTANCE = new BinaryType();

    private BinaryType() {
      super(TYPE_BINARY);
    }

    @Override
    public String name() {
      return "binary";
    }
  }

  // ============================================================================
  // Temporal types
  // ============================================================================

  /** Duration stored as 64-bit integer. */
  public static class DurationType extends FixedWidthType {
    public static final DurationType INSTANCE = new DurationType();

    private DurationType() {
      super(TYPE_DURATION, 64);
    }

    @Override
    public String name() {
      return "duration";
    }
  }

  /** Timestamp stored as 64-bit integer (microseconds since epoch). */
  public static class TimestampType extends FixedWidthType {
    public static final TimestampType INSTANCE = new TimestampType();

    private TimestampType() {
      super(TYPE_TIMESTAMP, 64);
    }

    @Override
    public String name() {
      return "timestamp";
    }
  }

  /** Date stored as 32-bit integer (days since epoch). */
  public static class LocalDateType extends FixedWidthType {
    public static final LocalDateType INSTANCE = new LocalDateType();

    private LocalDateType() {
      super(TYPE_LOCAL_DATE, 32);
    }

    @Override
    public String name() {
      return "date32";
    }
  }

  // ============================================================================
  // Decimal type
  // ============================================================================

  /** Decimal type with configurable precision and scale. */
  public static class DecimalType extends DataType {
    private final int precision;
    private final int scale;

    public DecimalType(int precision, int scale) {
      super(TYPE_DECIMAL);
      this.precision = precision;
      this.scale = scale;
    }

    @Override
    public String name() {
      return "decimal";
    }

    @Override
    public String toString() {
      return "decimal(" + precision + ", " + scale + ")";
    }

    public int precision() {
      return precision;
    }

    public int scale() {
      return scale;
    }

    @Override
    public boolean equals(Object obj) {
      if (!super.equals(obj)) {
        return false;
      }
      DecimalType other = (DecimalType) obj;
      return precision == other.precision && scale == other.scale;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), precision, scale);
    }
  }

  // ============================================================================
  // Composite types
  // ============================================================================

  /** Variable-length list of elements with uniform type. */
  public static class ListType extends DataType {
    private final Field valueField;

    public ListType(DataType valueType) {
      this(new Field(ARRAY_ITEM_NAME, valueType, true));
    }

    public ListType(Field valueField) {
      super(TYPE_LIST);
      this.valueField = valueField;
    }

    @Override
    public String name() {
      return "list";
    }

    @Override
    public String toString() {
      return "list<" + valueField.type().toString() + ">";
    }

    public DataType valueType() {
      return valueField.type();
    }

    public Field valueField() {
      return valueField;
    }

    @Override
    public int numFields() {
      return 1;
    }

    @Override
    public Field field(int i) {
      return i == 0 ? valueField : null;
    }

    @Override
    public List<Field> fields() {
      return Collections.singletonList(valueField);
    }

    @Override
    public boolean equals(Object obj) {
      if (!super.equals(obj)) {
        return false;
      }
      ListType other = (ListType) obj;
      return valueField.type().equals(other.valueField.type());
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), valueField.type());
    }
  }

  /** Struct type: a sequence of named fields (like a row or record). */
  public static class StructType extends DataType {
    private final List<Field> fields;
    private final Map<String, Integer> nameToIndex;

    public StructType(List<Field> fields) {
      super(TYPE_STRUCT);
      this.fields = new ArrayList<>(fields);
      this.nameToIndex = new HashMap<>();
      for (int i = 0; i < fields.size(); i++) {
        nameToIndex.put(fields.get(i).name(), i);
      }
    }

    @Override
    public String name() {
      return "struct";
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("struct<");
      for (int i = 0; i < fields.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(fields.get(i).toString());
      }
      sb.append(">");
      return sb.toString();
    }

    @Override
    public int numFields() {
      return fields.size();
    }

    @Override
    public Field field(int i) {
      return (i >= 0 && i < fields.size()) ? fields.get(i) : null;
    }

    @Override
    public List<Field> fields() {
      return Collections.unmodifiableList(fields);
    }

    public Field getFieldByName(String name) {
      Integer idx = nameToIndex.get(name);
      return idx != null ? fields.get(idx) : null;
    }

    public int getFieldIndex(String name) {
      Integer idx = nameToIndex.get(name);
      return idx != null ? idx : -1;
    }

    @Override
    public boolean equals(Object obj) {
      if (!super.equals(obj)) {
        return false;
      }
      StructType other = (StructType) obj;
      if (fields.size() != other.fields.size()) {
        return false;
      }
      for (int i = 0; i < fields.size(); i++) {
        if (!fields.get(i).equals(other.fields.get(i))) {
          return false;
        }
      }
      return true;
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), fields);
    }
  }

  /** Map type: a collection of key-value pairs. */
  public static class MapType extends DataType {
    private final Field keyField;
    private final Field itemField;
    private final boolean keysSorted;

    public MapType(DataType keyType, DataType itemType) {
      this(keyType, itemType, false);
    }

    public MapType(DataType keyType, DataType itemType, boolean keysSorted) {
      super(TYPE_MAP);
      // Keys must be non-nullable
      this.keyField = new Field(MAP_KEY_NAME, keyType, false);
      this.itemField = new Field(MAP_VALUE_NAME, itemType, true);
      this.keysSorted = keysSorted;
    }

    @Override
    public String name() {
      return "map";
    }

    @Override
    public String toString() {
      return "map<" + keyField.type().toString() + ", " + itemField.type().toString() + ">";
    }

    public DataType keyType() {
      return keyField.type();
    }

    public DataType itemType() {
      return itemField.type();
    }

    public Field keyField() {
      return keyField;
    }

    public Field itemField() {
      return itemField;
    }

    public boolean keysSorted() {
      return keysSorted;
    }

    @Override
    public int numFields() {
      return 2;
    }

    @Override
    public Field field(int i) {
      if (i == 0) {
        return keyField;
      }
      if (i == 1) {
        return itemField;
      }
      return null;
    }

    @Override
    public List<Field> fields() {
      return Arrays.asList(keyField, itemField);
    }

    @Override
    public boolean equals(Object obj) {
      if (!super.equals(obj)) {
        return false;
      }
      MapType other = (MapType) obj;
      return keyField.type().equals(other.keyField.type())
          && itemField.type().equals(other.itemField.type());
    }

    @Override
    public int hashCode() {
      return Objects.hash(super.hashCode(), keyField.type(), itemField.type());
    }
  }

  // ============================================================================
  // Factory functions for creating types
  // ============================================================================

  public static BooleanType bool() {
    return BooleanType.INSTANCE;
  }

  public static Int8Type int8() {
    return Int8Type.INSTANCE;
  }

  public static Int16Type int16() {
    return Int16Type.INSTANCE;
  }

  public static Int32Type int32() {
    return Int32Type.INSTANCE;
  }

  public static Int64Type int64() {
    return Int64Type.INSTANCE;
  }

  public static Float16Type float16() {
    return Float16Type.INSTANCE;
  }

  public static Float32Type float32() {
    return Float32Type.INSTANCE;
  }

  public static Float64Type float64() {
    return Float64Type.INSTANCE;
  }

  public static StringType utf8() {
    return StringType.INSTANCE;
  }

  public static BinaryType binary() {
    return BinaryType.INSTANCE;
  }

  public static DurationType duration() {
    return DurationType.INSTANCE;
  }

  public static TimestampType timestamp() {
    return TimestampType.INSTANCE;
  }

  public static LocalDateType date32() {
    return LocalDateType.INSTANCE;
  }

  public static DecimalType decimal() {
    return decimal(DecimalUtils.MAX_PRECISION, DecimalUtils.MAX_SCALE);
  }

  public static DecimalType decimal(int precision, int scale) {
    return new DecimalType(precision, scale);
  }

  public static DecimalType bigintDecimal() {
    return decimal(DecimalUtils.MAX_PRECISION, 0);
  }

  public static ListType list(DataType valueType) {
    return new ListType(valueType);
  }

  public static ListType list(Field valueField) {
    return new ListType(valueField);
  }

  public static StructType struct(List<Field> fields) {
    return new StructType(fields);
  }

  public static StructType struct(Field... fields) {
    return new StructType(Arrays.asList(fields));
  }

  public static MapType map(DataType keyType, DataType itemType) {
    return new MapType(keyType, itemType);
  }

  public static MapType map(DataType keyType, DataType itemType, boolean keysSorted) {
    return new MapType(keyType, itemType, keysSorted);
  }

  // ============================================================================
  // Factory functions for creating fields
  // ============================================================================

  public static Field field(String name, DataType type) {
    return new Field(name, type, true);
  }

  public static Field field(String name, DataType type, boolean nullable) {
    return new Field(name, type, nullable);
  }

  public static Field field(
      String name, DataType type, boolean nullable, Map<String, String> meta) {
    return new Field(name, type, nullable, meta);
  }

  public static Field notNullField(String name, DataType type) {
    return new Field(name, type, false);
  }

  // ============================================================================
  // Factory functions for creating schemas
  // ============================================================================

  public static Schema schema(List<Field> fields) {
    return new Schema(fields);
  }

  public static Schema schema(List<Field> fields, Map<String, String> metadata) {
    return new Schema(fields, metadata);
  }

  public static Schema schema(Field... fields) {
    return new Schema(Arrays.asList(fields));
  }

  // ============================================================================
  // Array field utility functions
  // ============================================================================

  private static Field primitiveArrayField(
      DataType elementType, String itemName, boolean nullable) {
    Field itemField = new Field(itemName, elementType, nullable);
    return new Field("", new ListType(itemField), true);
  }

  public static Field primitiveArrayField(DataType elementType) {
    return primitiveArrayField("", elementType);
  }

  public static Field primitiveArrayField(String name, DataType elementType) {
    Field itemField = new Field(ARRAY_ITEM_NAME, elementType, false);
    return new Field(name, new ListType(itemField), true);
  }

  public static Field arrayField(DataType elementType) {
    return arrayField("", elementType);
  }

  public static Field arrayField(String name, DataType elementType) {
    Field itemField = new Field(ARRAY_ITEM_NAME, elementType, true);
    return new Field(name, new ListType(itemField), true);
  }

  public static Field arrayField(Field valueField) {
    return arrayField("", valueField);
  }

  public static Field arrayField(String name, Field valueField) {
    return new Field(name, new ListType(valueField), true);
  }

  public static Field arrayElementField(Field arrayField) {
    ListType listType = (ListType) arrayField.type();
    return listType.valueField();
  }

  // ============================================================================
  // Map field utility functions
  // ============================================================================

  public static Field mapField(DataType keyType, DataType itemType) {
    return mapField("", keyType, itemType);
  }

  public static Field mapField(String name, DataType keyType, DataType itemType) {
    return new Field(name, new MapType(keyType, itemType), true);
  }

  public static Field mapField(String name, Field keyField, Field itemField) {
    Preconditions.checkArgument(!keyField.nullable(), "Map's keys must be non-nullable");
    DataType keyType = keyField.type();
    DataType itemType = itemField.type();
    return new Field(name, new MapType(keyType, itemType), true);
  }

  public static Field keyFieldForMap(Field mapField) {
    MapType mapType = (MapType) mapField.type();
    return mapType.keyField();
  }

  public static Field itemFieldForMap(Field mapField) {
    MapType mapType = (MapType) mapField.type();
    return mapType.itemField();
  }

  public static Field keyArrayFieldForMap(Field mapField) {
    return arrayField("keys", keyFieldForMap(mapField));
  }

  public static Field itemArrayFieldForMap(Field mapField) {
    return arrayField("items", itemFieldForMap(mapField));
  }

  // ============================================================================
  // Struct field utility functions
  // ============================================================================

  public static Schema schemaFromStructField(Field structField) {
    StructType structType = (StructType) structField.type();
    return new Schema(structType.fields(), structField.metadata());
  }

  public static Schema createSchema(Field field) {
    StructType structType = (StructType) field.type();
    return new Schema(structType.fields(), field.metadata());
  }

  public static Field structField(boolean nullable, Field... fields) {
    return structField("", nullable, fields);
  }

  public static Field structField(String name, boolean nullable, Field... fields) {
    return new Field(name, new StructType(Arrays.asList(fields)), nullable);
  }

  public static Field structField(String name, boolean nullable, List<Field> fields) {
    return new Field(name, new StructType(fields), nullable);
  }

  // ============================================================================
  // Type utility functions
  // ============================================================================

  public static Field fieldOfSchema(Schema schema, int index) {
    return schema.field(index);
  }

  /**
   * Returns the byte width of a type, or -1 for variable-width types.
   *
   * @param type the data type
   * @return byte width or -1
   */
  public static int getTypeWidth(DataType type) {
    int bitWidth = type.bitWidth();
    if (bitWidth > 0) {
      return (bitWidth + 7) / 8;
    }
    return -1;
  }

  /**
   * Returns the type ID for a data type.
   *
   * @param type the data type
   * @return type ID
   */
  public static int getTypeId(DataType type) {
    return type.typeId();
  }

  /**
   * Computes a hash for a schema based on field types.
   *
   * @param schema the schema
   * @return hash value
   */
  public static long computeSchemaHash(Schema schema) {
    long hash = 17;
    for (Field field : schema.fields()) {
      hash = computeHash(hash, field);
    }
    return hash;
  }

  private static long computeHash(long hash, Field field) {
    int typeId = field.type().typeId();
    while (true) {
      try {
        hash = Math.addExact(Math.multiplyExact(hash, 31), (long) typeId);
        break;
      } catch (ArithmeticException e) {
        hash = hash >> 2;
      }
    }
    List<Field> childFields = new ArrayList<>();
    DataType type = field.type();
    if (type instanceof ListType) {
      childFields.add(arrayElementField(field));
    } else if (type instanceof MapType) {
      childFields.add(keyFieldForMap(field));
      childFields.add(itemFieldForMap(field));
    } else if (type instanceof StructType) {
      childFields.addAll(type.fields());
    }
    for (Field child : childFields) {
      hash = computeHash(hash, child);
    }
    return hash;
  }

  // ============================================================================
  // Schema serialization
  // ============================================================================

  /**
   * Serializes a Fory Schema to bytes.
   *
   * @param schema the schema to serialize
   * @return serialized bytes
   */
  public static byte[] serializeSchema(Schema schema) {
    return SchemaEncoder.toBytes(schema);
  }

  /**
   * Serializes a Fory Schema to a MemoryBuffer.
   *
   * @param schema the schema to serialize
   * @param buffer the buffer to write to
   */
  public static void serializeSchema(Schema schema, org.apache.fory.memory.MemoryBuffer buffer) {
    SchemaEncoder.toBytes(schema, buffer);
  }

  /**
   * Deserializes a Fory Schema from bytes.
   *
   * @param bytes the serialized bytes
   * @return the deserialized schema
   */
  public static Schema deserializeSchema(byte[] bytes) {
    return SchemaEncoder.fromBytes(bytes);
  }
}
