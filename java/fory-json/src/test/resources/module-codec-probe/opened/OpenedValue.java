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

package opened;

import org.apache.fory.json.annotation.JsonCodec;
import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

@JsonCodec(OpenedValue.Codec.class)
public final class OpenedValue {
  public final String text;

  public OpenedValue(String text) {
    this.text = text;
  }

  public static final class Codec implements JsonValueCodec<OpenedValue> {
    @Override
    public void writeString(StringJsonWriter writer, OpenedValue value) {
      writer.writeString(value.text);
    }

    @Override
    public void writeUtf8(Utf8JsonWriter writer, OpenedValue value) {
      writer.writeString(value.text);
    }

    @Override
    public OpenedValue readLatin1(Latin1JsonReader reader) {
      return new OpenedValue(reader.readString());
    }

    @Override
    public OpenedValue readUtf16(Utf16JsonReader reader) {
      return new OpenedValue(reader.readString());
    }

    @Override
    public OpenedValue readUtf8(Utf8JsonReader reader) {
      return new OpenedValue(reader.readString());
    }
  }
}
