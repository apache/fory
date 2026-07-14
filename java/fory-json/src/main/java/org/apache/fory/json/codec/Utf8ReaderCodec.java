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

import org.apache.fory.json.reader.Utf8JsonReader;

/**
 * Reads one complete resolved Java value, including JSON {@code null}, through the concrete {@link
 * Utf8JsonReader} path.
 *
 * <p>The reader retains its resolver and the resolved capability already owns its declared type;
 * neither resolver nor {@code JsonTypeInfo} is passed through this hot call.
 */
public interface Utf8ReaderCodec<T> {
  T readUtf8(Utf8JsonReader reader);

  /** Reads an inline object whose discriminator remains visible in the object input. */
  default T readUtf8(Utf8JsonReader reader, long discriminatorHash) {
    return readUtf8(reader);
  }
}
