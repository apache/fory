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

package org.apache.fory.json;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

import java.nio.charset.StandardCharsets;
import org.apache.fory.json.codec.AbstractJsonValueCodec;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;
import org.testng.annotations.Test;

public class AbstractJsonValueCodecTest {
  @Test
  public void bridges() {
    TextValueCodec codec = new TextValueCodec();
    TextValue value = new TextValue("你好");

    StringJsonWriter stringWriter = JsonTestSupport.newStringWriter();
    codec.writeString(stringWriter, value);
    assertEquals(stringWriter.toJson(), "\"你好\"");

    Utf8JsonWriter utf8Writer = JsonTestSupport.newUtf8Writer();
    codec.writeUtf8(utf8Writer, value);
    assertEquals(new String(utf8Writer.toJsonBytes(), StandardCharsets.UTF_8), "\"你好\"");

    assertEquals(
        codec.readLatin1(
                JsonTestSupport.newLatin1Reader("\"latin1\"".getBytes(StandardCharsets.ISO_8859_1)))
            .text,
        "latin1");
    assertEquals(codec.readUtf16(JsonTestSupport.newUtf16Reader("\"你好\"")).text, "你好");
    assertEquals(
        codec.readUtf8(JsonTestSupport.newUtf8Reader("\"utf8\"".getBytes(StandardCharsets.UTF_8)))
            .text,
        "utf8");
  }

  @Test
  public void representations() {
    ForyJson json = newJson();
    TextValue value = new TextValue("你好");
    assertEquals(json.toJson(value), "\"你好\"");
    assertEquals(new String(json.toJsonBytes(value), StandardCharsets.UTF_8), "\"你好\"");
    assertEquals(json.fromJson("\"latin1\"", TextValue.class).text, "latin1");
    assertEquals(json.fromJson("\"你好\"", TextValue.class).text, "你好");
    assertEquals(
        json.fromJson("\"utf8\"".getBytes(StandardCharsets.UTF_8), TextValue.class).text, "utf8");
  }

  @Test
  public void nullValue() {
    ForyJson json = newJson();
    assertEquals(json.toJson(null, TextValue.class), "null");
    assertEquals(
        new String(json.toJsonBytes(null, TextValue.class), StandardCharsets.UTF_8), "null");
    assertNull(json.fromJson("null", TextValue.class));
    assertNull(json.fromJson("null".getBytes(StandardCharsets.UTF_8), TextValue.class));
  }

  private static ForyJson newJson() {
    return ForyJson.builder()
        .registerCodec(TextValue.class, new TextValueCodec())
        .withAsyncCompilation(false)
        .build();
  }

  private static final class TextValueCodec extends AbstractJsonValueCodec<TextValue> {
    @Override
    public void write(JsonWriter writer, TextValue value) {
      if (value == null) {
        writer.writeNull();
      } else {
        writer.writeString(value.text);
      }
    }

    @Override
    public TextValue read(JsonReader reader) {
      return reader.tryReadNullToken() ? null : new TextValue(reader.readString());
    }
  }

  private static final class TextValue {
    private final String text;

    private TextValue(String text) {
      this.text = text;
    }
  }
}
