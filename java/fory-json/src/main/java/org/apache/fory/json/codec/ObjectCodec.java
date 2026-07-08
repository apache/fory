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

import org.apache.fory.json.meta.JsonFieldInfo;
import org.apache.fory.json.reader.Latin1ObjectReader;
import org.apache.fory.json.reader.ObjectReader;
import org.apache.fory.json.reader.Utf16ObjectReader;
import org.apache.fory.json.reader.Utf8ObjectReader;
import org.apache.fory.json.writer.StringObjectWriter;
import org.apache.fory.json.writer.Utf8ObjectWriter;
import org.apache.fory.reflect.ObjectInstantiator;

public final class ObjectCodec extends BaseObjectCodec {
  ObjectCodec(
      Class<?> type,
      JsonFieldInfo[] writeFields,
      JsonFieldInfo[] readFields,
      ObjectInstantiator<?> instantiator) {
    super(type, writeFields, readFields, instantiator);
  }

  public GeneratedObjectCodec withGenerated(
      StringObjectWriter stringWriter,
      Utf8ObjectWriter utf8Writer,
      ObjectReader reader,
      Latin1ObjectReader latin1Reader,
      Utf16ObjectReader utf16Reader,
      Utf8ObjectReader utf8Reader) {
    return new GeneratedObjectCodec(
        this, stringWriter, utf8Writer, reader, latin1Reader, utf16Reader, utf8Reader);
  }
}
