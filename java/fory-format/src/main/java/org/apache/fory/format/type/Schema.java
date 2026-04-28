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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.fory.format.row.ArrayData;
import org.apache.fory.format.row.Getters;
import org.apache.fory.format.row.MapData;
import org.apache.fory.format.row.Row;
import org.apache.fory.format.row.Setters;
import org.apache.fory.util.StringUtils;

/**
 * Schema: a collection of named fields defining a row structure.
 *
 * <p>For repeated row access by name, create typed field handles once with methods such as {@link
 * #int32Field(String)} and reuse those handles for every row. Building a handle performs the schema
 * name lookup, Java lower-camel fallback, and type validation once; subsequent get/set calls use
 * its resolved ordinal directly.
 */
public class Schema {
  private final List<Field> fields;
  private final Map<String, Integer> nameToIndex;
  private final Map<String, String> metadata;

  public Schema(List<Field> fields) {
    this(fields, Collections.emptyMap());
  }

  public Schema(List<Field> fields, Map<String, String> metadata) {
    this.fields = new ArrayList<>(fields);
    this.nameToIndex = new HashMap<>();
    for (int i = 0; i < fields.size(); i++) {
      nameToIndex.put(fields.get(i).name(), i);
    }
    this.metadata = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();
  }

  public int numFields() {
    return fields.size();
  }

  public Field field(int i) {
    return (i >= 0 && i < fields.size()) ? fields.get(i) : null;
  }

  public List<Field> fields() {
    return Collections.unmodifiableList(fields);
  }

  /** Returns the list of field names in order. */
  public List<String> fieldNames() {
    List<String> names = new ArrayList<>(fields.size());
    for (Field f : fields) {
      names.add(f.name());
    }
    return names;
  }

  /** Returns the field with the given name, or null if not found. */
  public Field getFieldByName(String name) {
    Integer idx = nameToIndex.get(name);
    return idx != null ? fields.get(idx) : null;
  }

  /** Returns the index of the field with the given name, or -1 if not found. */
  public int getFieldIndex(String name) {
    Integer idx = nameToIndex.get(name);
    return idx != null ? idx : -1;
  }

  /** Creates a typed field handle for a boolean field. */
  public BoolField boolField(String name) {
    return new BoolField(typedField(name, DataTypes.TYPE_BOOL, DataTypes.bool().name()));
  }

  /** Creates a typed field handle for an int8 field. */
  public Int8Field int8Field(String name) {
    return new Int8Field(typedField(name, DataTypes.TYPE_INT8, DataTypes.int8().name()));
  }

  /** Creates a typed field handle for an int16 field. */
  public Int16Field int16Field(String name) {
    return new Int16Field(typedField(name, DataTypes.TYPE_INT16, DataTypes.int16().name()));
  }

  /** Creates a typed field handle for an int32 field. */
  public Int32Field int32Field(String name) {
    return new Int32Field(typedField(name, DataTypes.TYPE_INT32, DataTypes.int32().name()));
  }

  /** Creates a typed field handle for an int64 field. */
  public Int64Field int64Field(String name) {
    return new Int64Field(typedField(name, DataTypes.TYPE_INT64, DataTypes.int64().name()));
  }

  /** Creates a typed field handle for a float32 field. */
  public Float32Field float32Field(String name) {
    return new Float32Field(typedField(name, DataTypes.TYPE_FLOAT32, DataTypes.float32().name()));
  }

  /** Creates a typed field handle for a float64 field. */
  public Float64Field float64Field(String name) {
    return new Float64Field(typedField(name, DataTypes.TYPE_FLOAT64, DataTypes.float64().name()));
  }

  /** Creates a typed field handle for a date field. */
  public DateField dateField(String name) {
    return new DateField(typedField(name, DataTypes.TYPE_LOCAL_DATE, DataTypes.date32().name()));
  }

  /** Creates a typed field handle for a timestamp field. */
  public TimestampField timestampField(String name) {
    return new TimestampField(
        typedField(name, DataTypes.TYPE_TIMESTAMP, DataTypes.timestamp().name()));
  }

  /** Creates a typed field handle for a decimal field. */
  public DecimalField decimalField(String name) {
    return new DecimalField(typedField(name, DataTypes.TYPE_DECIMAL, DataTypes.decimal().name()));
  }

  /** Creates a typed field handle for a string field. */
  public StringField stringField(String name) {
    return new StringField(typedField(name, DataTypes.TYPE_STRING, DataTypes.utf8().name()));
  }

  /** Creates a typed field handle for a binary field. */
  public BinaryField binaryField(String name) {
    return new BinaryField(typedField(name, DataTypes.TYPE_BINARY, DataTypes.binary().name()));
  }

  /** Creates a typed field handle for a struct field. */
  public StructField structField(String name) {
    return new StructField(typedField(name, DataTypes.TYPE_STRUCT, "struct"));
  }

  /** Creates a typed field handle for an array field. */
  public ArrayField arrayField(String name) {
    return new ArrayField(typedField(name, DataTypes.TYPE_LIST, "list"));
  }

  /** Creates a typed field handle for a map field. */
  public MapField mapField(String name) {
    return new MapField(typedField(name, DataTypes.TYPE_MAP, "map"));
  }

  private ResolvedField typedField(String name, int typeId, String expectedType) {
    Integer ordinal = nameToIndex.get(name);
    if (ordinal == null) {
      ordinal = nameToIndex.get(StringUtils.lowerCamelToLowerUnderscore(name));
    }
    if (ordinal == null) {
      throw new IllegalArgumentException("Field " + name + " doesn't exist in schema");
    }
    Field field = fields.get(ordinal);
    if (field.type().typeId() != typeId) {
      throw new IllegalArgumentException(
          "Field " + name + " is " + field.type() + ", expected " + expectedType);
    }
    return new ResolvedField(ordinal, field);
  }

  private static final class ResolvedField {
    private final int ordinal;
    private final Field field;

    private ResolvedField(int ordinal, Field field) {
      this.ordinal = ordinal;
      this.field = field;
    }
  }

  /**
   * Typed row-field handle.
   *
   * <p>Callers should create and store a handle once from {@link Schema}, then reuse it across rows
   * of the same schema to avoid repeated name-to-index map lookups and repeated typed handle
   * construction. Accessors keep only the resolved ordinal and schema {@link Field}; {@code
   * get}/{@code set} delegate to ordinal row APIs.
   */
  public abstract static class RowField {
    private final int ordinal;
    private final Field field;

    private RowField(ResolvedField field) {
      this.ordinal = field.ordinal;
      this.field = field.field;
    }

    public final String name() {
      return field.name();
    }

    public final int ordinal() {
      return ordinal;
    }

    public final Field field() {
      return field;
    }

    public final boolean nullable() {
      return field.nullable();
    }

    public final boolean isNullAt(Getters row) {
      return row.isNullAt(ordinal);
    }
  }

  public static final class BoolField extends RowField {
    private BoolField(ResolvedField field) {
      super(field);
    }

    public boolean get(Getters row) {
      return row.getBoolean(ordinal());
    }

    public void set(Setters row, boolean value) {
      row.setBoolean(ordinal(), value);
    }
  }

  public static final class Int8Field extends RowField {
    private Int8Field(ResolvedField field) {
      super(field);
    }

    public byte get(Getters row) {
      return row.getByte(ordinal());
    }

    public void set(Setters row, byte value) {
      row.setByte(ordinal(), value);
    }
  }

  public static final class Int16Field extends RowField {
    private Int16Field(ResolvedField field) {
      super(field);
    }

    public short get(Getters row) {
      return row.getInt16(ordinal());
    }

    public void set(Setters row, short value) {
      row.setInt16(ordinal(), value);
    }
  }

  public static final class Int32Field extends RowField {
    private Int32Field(ResolvedField field) {
      super(field);
    }

    public int get(Getters row) {
      return row.getInt32(ordinal());
    }

    public void set(Setters row, int value) {
      row.setInt32(ordinal(), value);
    }
  }

  public static final class Int64Field extends RowField {
    private Int64Field(ResolvedField field) {
      super(field);
    }

    public long get(Getters row) {
      return row.getInt64(ordinal());
    }

    public void set(Setters row, long value) {
      row.setInt64(ordinal(), value);
    }
  }

  public static final class Float32Field extends RowField {
    private Float32Field(ResolvedField field) {
      super(field);
    }

    public float get(Getters row) {
      return row.getFloat32(ordinal());
    }

    public void set(Setters row, float value) {
      row.setFloat32(ordinal(), value);
    }
  }

  public static final class Float64Field extends RowField {
    private Float64Field(ResolvedField field) {
      super(field);
    }

    public double get(Getters row) {
      return row.getFloat64(ordinal());
    }

    public void set(Setters row, double value) {
      row.setFloat64(ordinal(), value);
    }
  }

  public static final class DateField extends RowField {
    private DateField(ResolvedField field) {
      super(field);
    }

    public int get(Getters row) {
      return row.getDate(ordinal());
    }

    public void set(Setters row, int value) {
      row.setDate(ordinal(), value);
    }
  }

  public static final class TimestampField extends RowField {
    private TimestampField(ResolvedField field) {
      super(field);
    }

    public long get(Getters row) {
      return row.getTimestamp(ordinal());
    }

    public void set(Setters row, long value) {
      row.setTimestamp(ordinal(), value);
    }
  }

  public static final class DecimalField extends RowField {
    private DecimalField(ResolvedField field) {
      super(field);
    }

    public BigDecimal get(Getters row) {
      return row.getDecimal(ordinal());
    }

    public void set(Setters row, BigDecimal value) {
      row.setDecimal(ordinal(), value);
    }
  }

  public static final class StringField extends RowField {
    private StringField(ResolvedField field) {
      super(field);
    }

    public String get(Getters row) {
      return row.getString(ordinal());
    }

    public void set(Setters row, String value) {
      row.setString(ordinal(), value);
    }
  }

  public static final class BinaryField extends RowField {
    private BinaryField(ResolvedField field) {
      super(field);
    }

    public byte[] get(Getters row) {
      return row.getBinary(ordinal());
    }

    public void set(Setters row, byte[] value) {
      row.setBinary(ordinal(), value);
    }
  }

  public static final class StructField extends RowField {
    private final Schema schema;

    private StructField(ResolvedField field) {
      super(field);
      this.schema = DataTypes.schemaFromStructField(field.field);
    }

    public Row get(Getters row) {
      return row.getStruct(ordinal());
    }

    public void set(Setters row, Row value) {
      row.setStruct(ordinal(), value);
    }

    public Schema schema() {
      return schema;
    }
  }

  public static final class ArrayField extends RowField {
    private ArrayField(ResolvedField field) {
      super(field);
    }

    public ArrayData get(Getters row) {
      return row.getArray(ordinal());
    }

    public void set(Setters row, ArrayData value) {
      row.setArray(ordinal(), value);
    }
  }

  public static final class MapField extends RowField {
    private MapField(ResolvedField field) {
      super(field);
    }

    public MapData get(Getters row) {
      return row.getMap(ordinal());
    }

    public void set(Setters row, MapData value) {
      row.setMap(ordinal(), value);
    }
  }

  public Map<String, String> metadata() {
    return Collections.unmodifiableMap(metadata);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        sb.append("\n");
      }
      sb.append(fields.get(i).toString());
    }
    return sb.toString();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj == null || getClass() != obj.getClass()) {
      return false;
    }
    Schema other = (Schema) obj;
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
    return Objects.hash(fields);
  }
}
