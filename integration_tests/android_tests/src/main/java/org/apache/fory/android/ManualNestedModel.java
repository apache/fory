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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

/** No-JsonType fixture whose nested codec is intentionally unavailable on Android. */
public final class ManualNestedModel {
  private static final AtomicInteger CODEC_CALLS = new AtomicInteger();

  public List<@JsonCodec(NestedValueCodec.class) NestedValue> values = new ArrayList<>();

  public ManualNestedModel() {}

  public static void resetCodecCalls() {
    CODEC_CALLS.set(0);
  }

  public static int codecCalls() {
    return CODEC_CALLS.get();
  }

  public static final class NestedValue {
    public String text;

    public NestedValue() {}

    public NestedValue(String text) {
      this.text = text;
    }
  }

  public static final class NestedValueCodec implements JsonValueCodec<NestedValue> {
    public NestedValueCodec() {}

    @Override
    public void writeString(StringJsonWriter writer, NestedValue value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "nested:" + value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, NestedValue value) {
      CODEC_CALLS.incrementAndGet();
      writer.writeString(value == null ? null : "nested:" + value.text);
    }

    @Override
    public NestedValue readLatin1(Latin1JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new NestedValue(reader.readString());
    }

    @Override
    public NestedValue readUtf16(Utf16JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new NestedValue(reader.readString());
    }

    @Override
    public NestedValue readUtf8(Utf8JsonReader reader) {
      CODEC_CALLS.incrementAndGet();
      return reader.tryReadNullToken() ? null : new NestedValue(reader.readString());
    }
  }
}
