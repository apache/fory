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

import org.apache.fory.json.ForyJsonException;
import org.apache.fory.json.reader.JsonReader;
import org.apache.fory.json.writer.JsonWriter;

/** Converts a Java map key to and from a JSON object member name. */
public interface MapKeyCodec {
  /** Converts a non-null Java map key to a non-null JSON object member name. */
  String toName(Object key);

  /** Converts a JSON object member name to a Java map key. */
  Object fromName(String name);

  /** Writes a Java map key as a JSON object member name. */
  default void writeName(JsonWriter writer, Object key) {
    String name = toName(key);
    if (name == null) {
      throw new ForyJsonException("JSON map key codec returned a null member name");
    }
    writer.writeFieldName(name);
  }

  /** Reads a Java map key from a JSON object member name. */
  default Object readName(JsonReader reader) {
    return fromName(reader.readString());
  }
}
