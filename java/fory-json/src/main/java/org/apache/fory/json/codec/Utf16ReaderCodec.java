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

import org.apache.fory.json.meta.JsonFieldAccessor;
import org.apache.fory.json.reader.Utf16JsonReader;
import org.apache.fory.json.resolver.JsonTypeInfo;
import org.apache.fory.json.resolver.JsonTypeResolver;

/** Reads one resolved Java type through {@link Utf16JsonReader}. */
public interface Utf16ReaderCodec {
  Object readUtf16(Utf16JsonReader reader, JsonTypeInfo typeInfo, JsonTypeResolver resolver);

  default void readUtf16Field(
      Utf16JsonReader reader,
      Object object,
      JsonFieldAccessor accessor,
      JsonTypeInfo typeInfo,
      JsonTypeResolver resolver) {
    accessor.putObject(object, readUtf16(reader, typeInfo, resolver));
  }
}
