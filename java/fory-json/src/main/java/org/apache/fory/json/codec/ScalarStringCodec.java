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

package org.apache.fory.json.codec;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Field-format codec which quotes the existing boolean or numeric token representation. */
@Internal
public class ScalarStringCodec implements JsonValueCodec<Object> {
  private final JsonTypeInfo scalar;
  private final boolean alreadyQuoted;
  private final boolean floating;

  private ScalarStringCodec(JsonTypeInfo scalar) {
    this.scalar = scalar;
    alreadyQuoted = ScalarCodecs.LongAsStringCodec.class.isInstance(scalar.stringWriter());
    Class<?> type = scalar.rawType();
    floating =
        type == float.class || type == Float.class || type == double.class || type == Double.class;
  }

  /** Selects the writer once while retaining the existing scalar readers. */
  public static ScalarStringCodec create(JsonTypeInfo scalar) {
    Class<?> type = scalar.rawType();
    return type == boolean.class || type == Boolean.class
        ? new BooleanCodec(scalar)
        : new ScalarStringCodec(scalar);
  }

  private static final class BooleanCodec extends ScalarStringCodec {
    private BooleanCodec(JsonTypeInfo scalar) {
      super(scalar);
    }

    @Override
    public void writeString(StringJsonWriter writer, Object value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBooleanAsString((Boolean) value);
      }
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Object value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeBooleanAsString((Boolean) value);
      }
    }
  }

  public static boolean supports(Class<?> type) {
    return type == boolean.class
        || type == Boolean.class
        || type == byte.class
        || type == Byte.class
        || type == short.class
        || type == Short.class
        || type == int.class
        || type == Integer.class
        || type == long.class
        || type == Long.class
        || type == float.class
        || type == Float.class
        || type == double.class
        || type == Double.class
        || type == BigInteger.class
        || type == BigDecimal.class
        || type.getName().equals("scala.math.BigInt")
        || type.getName().equals("scala.math.BigDecimal");
  }

  @Override
  public void writeString(StringJsonWriter writer, Object value) {
    if (value == null
        || alreadyQuoted
        || floating && !Double.isFinite(((Number) value).doubleValue())) {
      scalar.stringWriter().writeString(writer, value);
      return;
    }
    // Non-finite floats and configured Longs are already strings. Other values use the scalar
    // writer itself to retain numeric spelling and size limits without temporary decimal strings.
    writer.writeRawValue("\"");
    scalar.stringWriter().writeString(writer, value);
    writer.writeRawValue("\"");
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, Object value) {
    if (value == null
        || alreadyQuoted
        || floating && !Double.isFinite(((Number) value).doubleValue())) {
      scalar.utf8Writer().writeUtf8(writer, value);
      return;
    }
    writer.writeRawValue("\"");
    scalar.utf8Writer().writeUtf8(writer, value);
    writer.writeRawValue("\"");
  }

  @Override
  public Object readLatin1(Latin1JsonReader reader) {
    return scalar.latin1Reader().readLatin1(reader);
  }

  @Override
  public Object readUtf16(Utf16JsonReader reader) {
    return scalar.utf16Reader().readUtf16(reader);
  }

  @Override
  public Object readUtf8(Utf8JsonReader reader) {
    return scalar.utf8Reader().readUtf8(reader);
  }
}
