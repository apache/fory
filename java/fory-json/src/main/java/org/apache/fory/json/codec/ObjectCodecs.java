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

import org.apache.fory.json.reader.Latin1ObjectReader;
import org.apache.fory.json.reader.ObjectReader;
import org.apache.fory.json.reader.Utf16ObjectReader;
import org.apache.fory.json.reader.Utf8ObjectReader;
import org.apache.fory.json.writer.StringObjectWriter;
import org.apache.fory.json.writer.Utf8ObjectWriter;

public final class ObjectCodecs {
  private final StringObjectWriter stringWriter;
  private final Utf8ObjectWriter utf8Writer;
  private final ObjectReader reader;
  private final Latin1ObjectReader latin1Reader;
  private final Utf16ObjectReader utf16Reader;
  private final Utf8ObjectReader utf8Reader;

  public ObjectCodecs(
      StringObjectWriter stringWriter,
      Utf8ObjectWriter utf8Writer,
      ObjectReader reader,
      Latin1ObjectReader latin1Reader,
      Utf16ObjectReader utf16Reader,
      Utf8ObjectReader utf8Reader) {
    this.stringWriter = stringWriter;
    this.utf8Writer = utf8Writer;
    this.reader = reader;
    this.latin1Reader = latin1Reader;
    this.utf16Reader = utf16Reader;
    this.utf8Reader = utf8Reader;
  }

  public StringObjectWriter stringWriter() {
    return stringWriter;
  }

  public Utf8ObjectWriter utf8Writer() {
    return utf8Writer;
  }

  public ObjectReader reader() {
    return reader;
  }

  public Latin1ObjectReader latin1Reader() {
    return latin1Reader;
  }

  public Utf16ObjectReader utf16Reader() {
    return utf16Reader;
  }

  public Utf8ObjectReader utf8Reader() {
    return utf8Reader;
  }
}
