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

import java.util.List;
import org.apache.fory.annotation.Internal;
import org.apache.fory.format.row.binary.writer.CompactBinaryRowWriter;
import org.apache.fory.format.type.DataTypes;
import org.apache.fory.format.type.Field;
import org.apache.fory.format.type.Schema;

/**
 * Schema-derived layout for {@link CompactBinaryRow}, shared across rows. Read-only after
 * construction.
 */
@Internal
public final class CompactRowLayout {
  public final Schema schema;
  public final int[] fixedOffsets;
  public final int[] fixedWidths;
  public final boolean allFieldsNotNullable;
  public final int bitmapWidthInBytes;

  /**
   * Nested layout per field: struct row for struct fields, element row for {@code List<Struct>}.
   */
  public final CompactRowLayout[] childLayouts;

  public CompactRowLayout(Schema schema) {
    this.schema = schema;
    this.fixedOffsets = CompactBinaryRowWriter.fixedOffsets(schema);
    final List<Field> fields = schema.fields();
    this.fixedWidths = new int[fields.size()];
    this.childLayouts = new CompactRowLayout[fields.size()];
    for (int i = 0; i < fields.size(); i++) {
      final Field field = fields.get(i);
      fixedWidths[i] = CompactBinaryRowWriter.fixedWidthFor(field);
      if (field.type() instanceof DataTypes.StructType) {
        childLayouts[i] = new CompactRowLayout(DataTypes.createSchema(field));
      } else if (field.type() instanceof DataTypes.ListType) {
        final Field elementField = ((DataTypes.ListType) field.type()).valueField();
        if (elementField.type() instanceof DataTypes.StructType) {
          childLayouts[i] =
              new CompactRowLayout(
                  CompactBinaryRowWriter.sortSchema(DataTypes.createSchema(elementField)));
        }
      }
    }
    this.allFieldsNotNullable = CompactBinaryRowWriter.allNotNullable(fields);
    this.bitmapWidthInBytes = allFieldsNotNullable ? 0 : CompactBinaryRowWriter.headerBytes(schema);
  }

  public CompactBinaryRow newRow() {
    return new CompactBinaryRow(this);
  }
}
