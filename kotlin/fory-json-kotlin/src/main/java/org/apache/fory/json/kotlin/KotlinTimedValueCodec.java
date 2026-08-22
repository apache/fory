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

package org.apache.fory.json.kotlin;

import java.util.List;
import kotlin.time.TimedValue;
import org.apache.fory.annotation.Internal;
import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.codec.CompositeJsonCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldNameHash;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.apache.fory.reflect.TypeRef;
import org.apache.fory.serializer.GraphMemoryEstimates;

/** Exact two-field composite owner for {@link TimedValue}. */
@Internal
final class KotlinTimedValueCodec implements CompositeJsonCodec<TimedValue<?>> {
  private static final int VALUE_FIELD = 0;
  private static final int DURATION_FIELD = 1;
  private static final int ALL_FIELDS = 3;
  private static final byte DELEGATE_NULL = 0;
  private static final byte ACCEPT_NULL = 1;
  private static final byte REJECT_NULL = 2;
  private static final long VALUE_HASH = JsonFieldNameHash.hash("value");
  private static final long DURATION_HASH = JsonFieldNameHash.hash("duration");
  private static final int OWNER_BYTES = GraphMemoryEstimates.shallowObjectBytes(TimedValue.class);

  // JsonTypeResolver publishes this codec shell before binding the recursive child. The same
  // resolver transaction writes these ordinary slots exactly once or rolls the whole graph back.
  // Hot dispatch must not re-query mutable JsonTypeInfo capability slots.
  private JsonTypeInfo valueTypeInfo;
  private byte valueNullAction;
  private StringWriterCodec<Object> valueStringWriter;
  private Utf8WriterCodec<Object> valueUtf8Writer;
  private Latin1ReaderCodec<Object> valueLatin1Reader;
  private Utf16ReaderCodec<Object> valueUtf16Reader;
  private Utf8ReaderCodec<Object> valueUtf8Reader;

  @Override
  public void resolveTypes(TypeRef<?> type, JsonTypeResolver resolver) {
    if (valueStringWriter != null) {
      throw new IllegalStateException("Kotlin TimedValue child is already resolved");
    }
    List<TypeRef<?>> arguments = type.getTypeArguments();
    if (arguments.size() != 1) {
      throw new ForyJsonException("Kotlin TimedValue requires one exact value type argument");
    }
    valueTypeInfo = resolver.getTypeInfo(arguments.get(0));
    valueNullAction =
        valueTypeInfo.nullable()
            ? ACCEPT_NULL
            : valueTypeInfo.rejectsNull() ? REJECT_NULL : DELEGATE_NULL;
    valueStringWriter = valueTypeInfo.stringWriter();
    valueUtf8Writer = valueTypeInfo.utf8Writer();
    valueLatin1Reader = valueTypeInfo.latin1Reader();
    valueUtf16Reader = valueTypeInfo.utf16Reader();
    valueUtf8Reader = valueTypeInfo.utf8Reader();
  }

  @Override
  public void writeString(StringJsonWriter writer, TimedValue<?> value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    StringWriterCodec<Object> valueWriter = valueStringWriter;
    writer.writeObjectStart();
    writer.writeFieldName("value");
    Object child = value.getValue();
    if (!writeValueNull(writer, child)) {
      valueWriter.writeString(writer, child);
    }
    writeDurationName(writer);
    KotlinTemporalCodecs.writeDurationRaw(
        writer, KotlinTemporalCodecs.INSTANCE.timedDurationRaw(value));
    writer.writeObjectEnd();
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, TimedValue<?> value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    Utf8WriterCodec<Object> valueWriter = valueUtf8Writer;
    writer.writeObjectStart();
    writer.writeFieldName("value");
    Object child = value.getValue();
    if (!writeValueNull(writer, child)) {
      valueWriter.writeUtf8(writer, child);
    }
    writeDurationName(writer);
    KotlinTemporalCodecs.writeDurationRaw(
        writer, KotlinTemporalCodecs.INSTANCE.timedDurationRaw(value));
    writer.writeObjectEnd();
  }

  @Override
  public TimedValue<?> readLatin1(Latin1JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    reader.enterDepth();
    reader.expectNextToken('{');
    Object value = null;
    long duration = 0;
    int seen = 0;
    Latin1ReaderCodec<Object> valueReader = valueLatin1Reader;
    if (!reader.consumeNextToken('}')) {
      do {
        int field = readField(reader, seen);
        seen |= 1 << field;
        if (field == VALUE_FIELD) {
          if (!readValueNull(reader)) {
            value = valueReader.readLatin1(reader);
          }
        } else {
          duration = KotlinTemporalCodecs.readDurationRaw(reader);
        }
      } while (reader.consumeNextToken(','));
      reader.expectNextToken('}');
    }
    requireFields(seen);
    return create(reader, value, duration);
  }

  @Override
  public TimedValue<?> readUtf16(Utf16JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    reader.enterDepth();
    reader.expectNextToken('{');
    Object value = null;
    long duration = 0;
    int seen = 0;
    Utf16ReaderCodec<Object> valueReader = valueUtf16Reader;
    if (!reader.consumeNextToken('}')) {
      do {
        int field = readField(reader, seen);
        seen |= 1 << field;
        if (field == VALUE_FIELD) {
          if (!readValueNull(reader)) {
            value = valueReader.readUtf16(reader);
          }
        } else {
          duration = KotlinTemporalCodecs.readDurationRaw(reader);
        }
      } while (reader.consumeNextToken(','));
      reader.expectNextToken('}');
    }
    requireFields(seen);
    return create(reader, value, duration);
  }

  @Override
  public TimedValue<?> readUtf8(Utf8JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    reader.enterDepth();
    reader.expectNextToken('{');
    Object value = null;
    long duration = 0;
    int seen = 0;
    Utf8ReaderCodec<Object> valueReader = valueUtf8Reader;
    if (!reader.consumeNextToken('}')) {
      do {
        int field = readField(reader, seen);
        seen |= 1 << field;
        if (field == VALUE_FIELD) {
          if (!readValueNull(reader)) {
            value = valueReader.readUtf8(reader);
          }
        } else {
          duration = KotlinTemporalCodecs.readDurationRaw(reader);
        }
      } while (reader.consumeNextToken(','));
      reader.expectNextToken('}');
    }
    requireFields(seen);
    return create(reader, value, duration);
  }

  private static void writeDurationName(JsonWriter writer) {
    writer.writeComma(1);
    writer.writeFieldName("duration");
  }

  private boolean writeValueNull(JsonWriter writer, Object value) {
    if (value != null) {
      return false;
    }
    byte action = valueNullAction;
    if (action == ACCEPT_NULL) {
      writer.writeNull();
      return true;
    }
    if (action == REJECT_NULL) {
      valueTypeInfo.rejectNullValue();
    }
    return false;
  }

  private boolean readValueNull(JsonReader reader) {
    byte action = valueNullAction;
    if (action == DELEGATE_NULL || !reader.tryReadNull()) {
      return false;
    }
    if (action == REJECT_NULL) {
      valueTypeInfo.rejectNullValue();
    }
    return true;
  }

  private static int readField(JsonReader reader, int seen) {
    long hash = reader.readFieldNameHash();
    int field;
    if (hash == VALUE_HASH) {
      field = VALUE_FIELD;
    } else if (hash == DURATION_HASH) {
      field = DURATION_FIELD;
    } else {
      throw unknownField();
    }
    if ((seen & (1 << field)) != 0) {
      throw duplicateField();
    }
    reader.expectNextToken(':');
    return field;
  }

  private static TimedValue<?> create(JsonReader reader, Object value, long duration) {
    // Complete the JSON composite before accounting for and constructing its result owner.
    reader.exitDepth();
    reader.reserveGraphMemory(OWNER_BYTES);
    return KotlinTemporalCodecs.INSTANCE.newTimedValue(value, duration);
  }

  private static void requireFields(int seen) {
    if (seen != ALL_FIELDS) {
      throw new ForyJsonException("Kotlin TimedValue JSON requires value and duration");
    }
  }

  private static ForyJsonException unknownField() {
    return new ForyJsonException("Unknown Kotlin TimedValue JSON field");
  }

  private static ForyJsonException duplicateField() {
    return new ForyJsonException("Duplicate Kotlin TimedValue JSON field");
  }
}
