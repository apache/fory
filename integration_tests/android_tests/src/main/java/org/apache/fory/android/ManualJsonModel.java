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

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** Declaration-codec fixture retained by application-authored exact R8 rules. */
public final class ManualJsonModel {
  private static final AtomicInteger CODEC_CALLS = new AtomicInteger();

  public DirectValue direct;

  @JsonCodec(MemberValueCodec.class)
  public MemberValue declaredField;

  private MemberValue declaredProperty;

  public ManualJsonModel() {}

  @JsonCodec(MemberValueCodec.class)
  public MemberValue getDeclaredProperty() {
    return declaredProperty;
  }

  public void setDeclaredProperty(MemberValue declaredProperty) {
    this.declaredProperty = declaredProperty;
  }

  public static void resetCodecCalls() {
    CODEC_CALLS.set(0);
  }

  public static int codecCalls() {
    return CODEC_CALLS.get();
  }

  public interface TextValue {
    String text();
  }

  @JsonCodec(DirectValueCodec.class)
  public static final class DirectValue implements TextValue {
    public final String text;

    public DirectValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  public static final class MemberValue implements TextValue {
    public final String text;

    public MemberValue(String text) {
      this.text = text;
    }

    @Override
    public String text() {
      return text;
    }
  }

  public abstract static class CountingCodec<T extends TextValue> implements JsonValueCodec<T> {
    protected abstract T create(String text);

    @Override
    public void writeString(StringJsonWriter writer, T value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "manual:" + value.text());
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, T value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "manual:" + value.text());
    }

    @Override
    public T readLatin1(Latin1JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : create(reader.readString());
    }

    @Override
    public T readUtf16(Utf16JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : create(reader.readString());
    }

    @Override
    public T readUtf8(Utf8JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : create(reader.readString());
    }
  }

  public static final class DirectValueCodec extends CountingCodec<DirectValue> {
    public DirectValueCodec() {}

    @Override
    protected DirectValue create(String text) {
      return new DirectValue(text);
    }
  }

  public static final class MemberValueCodec extends CountingCodec<MemberValue> {
    public MemberValueCodec() {}

    @Override
    protected MemberValue create(String text) {
      return new MemberValue(text);
    }
  }
}
