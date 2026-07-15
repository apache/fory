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

package org.apache.fory.android.json.jar;

import org.apache.fory.json.codec.JsonValueCodec;
import org.apache.fory.json.reader.Latin1JsonReader;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.reader.Utf8JsonReader;
import org.apache.fory.json.writer.StringJsonWriter;
import org.apache.fory.json.writer.Utf8JsonWriter;

public final class JarCodedValueCodec implements JsonValueCodec<JarCodedValue> {
  public JarCodedValueCodec() {}

  @Override
  public void writeString(StringJsonWriter writer, JarCodedValue value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    writer.writeString("coded:" + value.value);
  }

  @Override
  public void writeUtf8(Utf8JsonWriter writer, JarCodedValue value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    writer.writeString("coded:" + value.value);
  }

  @Override
  public JarCodedValue readLatin1(Latin1JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    return decode(reader.readString());
  }

  @Override
  public JarCodedValue readUtf16(Utf16JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    return decode(reader.readString());
  }

  @Override
  public JarCodedValue readUtf8(Utf8JsonReader reader) {
    if (reader.tryReadNullToken()) {
      return null;
    }
    return decode(reader.readString());
  }

  private static JarCodedValue decode(String value) {
    if (!value.startsWith("coded:")) {
      throw new IllegalArgumentException("Expected coded value but got " + value);
    }
    return new JarCodedValue(value.substring("coded:".length()));
  }
}
