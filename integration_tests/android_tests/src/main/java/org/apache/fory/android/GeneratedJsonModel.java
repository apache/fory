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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.apache.fory.json.annotation.JsonAnyProperty;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.annotation.JsonCreator;
import org.apache.fory.json.annotation.JsonProperty;
import org.apache.fory.json.annotation.JsonSubTypes;
import org.apache.fory.json.annotation.JsonType;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.codec.MapKeyCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

abstract class GeneratedJsonBase<F, P> {
  public F declared;

  private P inheritedProperty;

  public P getInheritedProperty() {
    return inheritedProperty;
  }

  public void setInheritedProperty(P inheritedProperty) {
    this.inheritedProperty = inheritedProperty;
  }
}

/** Processor-generated declaration-codec and R8 rule fixture. */
@JsonType
@JsonSubTypes(
    property = "kind",
    value = {@JsonSubTypes.Type(value = GeneratedJsonSubtype.class, name = "generated")})
public abstract class GeneratedJsonModel
    extends GeneratedJsonBase<
        GeneratedJsonModel.DeclaredValue, GeneratedJsonModel.InheritedPropertyValue> {
  private static final AtomicInteger CODEC_CALLS = new AtomicInteger();
  private static final AtomicInteger KEY_CODEC_CALLS = new AtomicInteger();

  @JsonCodec(elementCodec = ValueCodec.class)
  public List<Value> values = new ArrayList<>();

  @JsonCodec(ValueCodec.class)
  public Value rootValue;

  @JsonCodec(elementCodec = ValueCodec.class)
  public Value[] array;

  @JsonCodec(elementCodec = ValueCodec.class)
  public AtomicReferenceArray<Value> atomicArray;

  @JsonCodec(keyCodec = KeyCodec.class, valueCodec = ValueCodec.class)
  public Map<Key, Value> byName = new LinkedHashMap<>();

  @JsonCodec(contentCodec = ValueCodec.class)
  public Optional<Value> optional;

  @JsonCodec(contentCodec = ValueCodec.class)
  public AtomicReference<Value> atomic;

  @JsonAnyProperty
  @JsonCodec(valueCodec = ValueCodec.class)
  public Map<String, Value> extra = new LinkedHashMap<>();

  private Value rootProperty;
  private Value parameterProperty;

  public GeneratedJsonModel() {}

  @JsonCodec(ValueCodec.class)
  public Value getRootProperty() {
    return rootProperty;
  }

  public void setRootProperty(Value rootProperty) {
    this.rootProperty = rootProperty;
  }

  public Value getParameterProperty() {
    return parameterProperty;
  }

  public void setParameterProperty(@JsonCodec(ValueCodec.class) Value parameterProperty) {
    this.parameterProperty = parameterProperty;
  }

  public static void resetCodecCalls() {
    CODEC_CALLS.set(0);
    KEY_CODEC_CALLS.set(0);
  }

  public static int codecCalls() {
    return CODEC_CALLS.get();
  }

  public static int keyCodecCalls() {
    return KEY_CODEC_CALLS.get();
  }

  public static final class Value {
    public final String text;

    public Value(String text) {
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

  public static final class Key {
    public final String text;

    public Key(String text) {
      this.text = text;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key && Objects.equals(text, ((Key) other).text);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(text);
    }
  }

  public static final class KeyCodec implements MapKeyCodec {
    public KeyCodec() {}

    @Override
    public String toName(Object key) {
      KEY_CODEC_CALLS.incrementAndGet();
      return "generated-key:" + ((Key) key).text;
    }

    @Override
    public Object fromName(String name) {
      KEY_CODEC_CALLS.incrementAndGet();
      return new Key(name.substring("generated-key:".length()));
    }
  }

  @JsonCodec(DeclaredValueCodec.class)
  public static final class DeclaredValue {
    public final String text;

    public DeclaredValue(String text) {
      this.text = text;
    }
  }

  public static final class DeclaredValueCodec implements JsonValueCodec<DeclaredValue> {
    public DeclaredValueCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, DeclaredValue value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "declared:" + value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, DeclaredValue value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "declared:" + value.text);
    }

    @Override
    public DeclaredValue readLatin1(Latin1JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new DeclaredValue(reader.readString());
    }

    @Override
    public DeclaredValue readUtf16(Utf16JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new DeclaredValue(reader.readString());
    }

    @Override
    public DeclaredValue readUtf8(Utf8JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new DeclaredValue(reader.readString());
    }
  }

  @JsonCodec(InheritedPropertyValueCodec.class)
  public static final class InheritedPropertyValue {
    public final String text;

    public InheritedPropertyValue(String text) {
      this.text = text;
    }
  }

  public static final class InheritedPropertyValueCodec
      implements JsonValueCodec<InheritedPropertyValue> {
    public InheritedPropertyValueCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, InheritedPropertyValue value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "inherited:" + value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, InheritedPropertyValue value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "inherited:" + value.text);
    }

    @Override
    public InheritedPropertyValue readLatin1(Latin1JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new InheritedPropertyValue(reader.readString());
    }

    @Override
    public InheritedPropertyValue readUtf16(Utf16JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new InheritedPropertyValue(reader.readString());
    }

    @Override
    public InheritedPropertyValue readUtf8(Utf8JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new InheritedPropertyValue(reader.readString());
    }
  }

  @JsonType
  public static final class CreatedValue {
    public final List<Value> values;
    public final Value direct;

    @JsonCreator
    public CreatedValue(
        @JsonProperty("values") @JsonCodec(elementCodec = ValueCodec.class) List<Value> values,
        @JsonProperty("direct") @JsonCodec(ValueCodec.class) Value direct) {
      this.values = values;
      this.direct = direct;
    }
  }
}
