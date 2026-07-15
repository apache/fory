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

package org.apache.fory.android;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Processor-generated nested codec and R8 metadata fixture. */
@JsonType
@JsonSubTypes(
    property = "kind",
    value = {@JsonSubTypes.Type(value = GeneratedJsonSubtype.class, name = "generated")})
public abstract class GeneratedJsonModel extends GeneratedJsonParent
    implements GeneratedJsonContract, SuppressedJsonContract {
  private static final AtomicInteger CODEC_CALLS = new AtomicInteger();
  private static final AtomicInteger SUPPRESSED_CODEC_CALLS = new AtomicInteger();

  public List<@JsonCodec(ValueCodec.class) Value> values = new ArrayList<>();
  public GeneratedJsonModel.@JsonCodec(ValueCodec.class) Value rootValue;
  public GeneratedJsonModel.@JsonCodec(ValueCodec.class) Value[] array;
  public Map<String, @JsonCodec(ValueCodec.class) Value> byName = new LinkedHashMap<>();

  @JsonAnyProperty
  public Map<String, @JsonCodec(ValueCodec.class) Value> extra = new LinkedHashMap<>();

  private List<Value> interfaceValues = new ArrayList<>();
  private List<PlainValue> suppressedValues = new ArrayList<>();
  private Value rootProperty;

  public GeneratedJsonModel() {}

  @Override
  public List<Value> interfaceValuesStorage() {
    return interfaceValues;
  }

  public void replaceInterfaceValues(List<Value> values) {
    interfaceValues = values;
  }

  @Override
  public List<PlainValue> getSuppressedValues() {
    return suppressedValues;
  }

  public void replaceSuppressedValues(List<PlainValue> values) {
    suppressedValues = values;
  }

  public GeneratedJsonModel.@JsonCodec(ValueCodec.class) Value getRootProperty() {
    return rootProperty;
  }

  public void setRootProperty(Value rootProperty) {
    this.rootProperty = rootProperty;
  }

  public static void resetCodecCalls() {
    CODEC_CALLS.set(0);
    SUPPRESSED_CODEC_CALLS.set(0);
  }

  public static int codecCalls() {
    return CODEC_CALLS.get();
  }

  public static int suppressedCodecCalls() {
    return SUPPRESSED_CODEC_CALLS.get();
  }

  public static final class Value {
    public final String text;

    public Value(String text) {
      this.text = text;
    }
  }

  @JsonType
  public static final class PlainValue {
    public String text;

    public PlainValue() {}

    public PlainValue(String text) {
      this.text = text;
    }
  }

  public static final class ValueCodec implements JsonValueCodec<Value> {
    public ValueCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, Value value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "generated:" + value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, Value value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "generated:" + value.text);
    }

    @Override
    public Value readLatin1(Latin1JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new Value(reader.readString());
    }

    @Override
    public Value readUtf16(Utf16JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new Value(reader.readString());
    }

    @Override
    public Value readUtf8(Utf8JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new Value(reader.readString());
    }
  }

  public static final class SuppressedValueCodec implements JsonValueCodec<PlainValue> {
    public SuppressedValueCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, PlainValue value) {
      SUPPRESSED_CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "suppressed:" + value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, PlainValue value) {
      SUPPRESSED_CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "suppressed:" + value.text);
    }

    @Override
    public PlainValue readLatin1(Latin1JsonReader reader) {
      SUPPRESSED_CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new PlainValue(reader.readString());
    }

    @Override
    public PlainValue readUtf16(Utf16JsonReader reader) {
      SUPPRESSED_CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new PlainValue(reader.readString());
    }

    @Override
    public PlainValue readUtf8(Utf8JsonReader reader) {
      SUPPRESSED_CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new PlainValue(reader.readString());
    }
  }

  @JsonType
  public static final class CreatedValue {
    public final List<Value> values;
    public final Value direct;

    @JsonCreator
    public CreatedValue(
        @JsonProperty("values") List<@JsonCodec(ValueCodec.class) Value> values,
        @JsonProperty("direct") GeneratedJsonModel.@JsonCodec(ValueCodec.class) Value direct) {
      this.values = values;
      this.direct = direct;
    }
  }
}
