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

package org.apache.fory.json.resolver;

import org.apache.fory.json.codec.ClosedSubtypeCodec;
import org.apache.fory.json.codec.Latin1ReaderCodec;
import org.apache.fory.json.codec.ObjectCodec;
import org.apache.fory.json.codec.StringWriterCodec;
import org.apache.fory.json.codec.Utf16ReaderCodec;
import org.apache.fory.json.codec.Utf8ReaderCodec;
import org.apache.fory.json.codec.Utf8WriterCodec;
import org.apache.fory.json.meta.JsonFieldTable;

/** Android resolver without JVM code generation state or capability-map work. */
final class AndroidJsonTypeResolver extends JsonTypeResolver {
  static JsonTypeResolver newResolver(JsonSharedRegistry sharedRegistry) {
    return new AndroidJsonTypeResolver(sharedRegistry);
  }

  private AndroidJsonTypeResolver(JsonSharedRegistry sharedRegistry) {
    super(sharedRegistry, null, null);
  }

  @Override
  public void lockJIT() {}

  @Override
  public void unlockJIT() {}

  @Override
  public <T> StringWriterCodec<T> stringWriter(ObjectCodec<T> codec) {
    return codec;
  }

  @Override
  public <T> Utf8WriterCodec<T> utf8Writer(ObjectCodec<T> codec) {
    return codec;
  }

  @Override
  public <T> Latin1ReaderCodec<T> latin1Reader(ObjectCodec<T> codec) {
    return codec;
  }

  @Override
  public <T> Utf16ReaderCodec<T> utf16Reader(ObjectCodec<T> codec) {
    return codec;
  }

  @Override
  public <T> Utf8ReaderCodec<T> utf8Reader(ObjectCodec<T> codec) {
    return codec;
  }

  @Override
  public void resolveInlineAnyReaders(
      ClosedSubtypeCodec parent, int index, ObjectCodec<?> codec, JsonFieldTable readTable) {}
}
