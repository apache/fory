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

import org.apache.fory.annotation.Internal;
import org.apache.fory.json.meta.JsonAsciiToken;
import org.apache.fory.json.writer.Utf8JsonWriter;

/**
 * Common UTF-8 representation for a closed enumeration whose values have fixed string names.
 *
 * <p>Names come from the declared schema, never from input values. Subclasses retain their own
 * membership and read semantics. Arrays can reserve a bounded group of these tokens and separators
 * without calling a writer or updating its cursor for every element. Subclass writer
 * specializations must preserve these names and the JSON null representation.
 *
 * @param <T> enum value type
 */
@Internal
public abstract class StringEnumCodec<T> implements JsonValueCodec<T> {
  private final String[] enumNames;
  final long[] utf8Tokens;
  final int maxUtf8Length;

  protected StringEnumCodec(String[] names) {
    enumNames = names.clone();
    utf8Tokens = new long[(names.length + 1) * 2];
    // Slot zero is null plus its separator, independent of any member's string spelling.
    utf8Tokens[0] = 0x2c6c6c756eL;
    utf8Tokens[1] = 5L << 56;
    int maximum = 5;
    boolean allPacked = true;
    for (int i = 0; i < names.length; i++) {
      String name = names[i];
      String token = '"' + name + "\",";
      boolean packed = JsonAsciiToken.isLongPackable(token);
      for (int j = 0; packed && j < name.length(); j++) {
        char ch = name.charAt(j);
        packed = ch >= 0x20 && ch < 0x7f && ch != '"' && ch != '\\';
      }
      if (packed) {
        int index = (i + 1) * 2;
        utf8Tokens[index] = JsonAsciiToken.prefix(token);
        // Include the array separator so its store and cursor update can be fused with the token.
        // The last byte of a sixteen-byte token is always a comma; store its length there instead.
        utf8Tokens[index + 1] =
            (JsonAsciiToken.suffixLong(token) & 0x00ffffffffffffffL)
                | ((long) token.length() << 56);
        maximum = Math.max(maximum, token.length());
      } else {
        allPacked = false;
      }
    }
    maxUtf8Length = allPacked ? maximum : 0;
  }

  /** Returns the schema index of a non-null member. */
  protected abstract int valueIndex(T value);

  @Override
  public void writeUtf8(Utf8JsonWriter writer, T value) {
    if (value == null) {
      writer.writeNull();
      return;
    }
    int index = valueIndex(value);
    int token = (index + 1) * 2;
    long suffix = utf8Tokens[token + 1];
    int length = (int) (suffix >>> 56);
    if (length != 0) {
      writer.writeRawValue(
          utf8Tokens[token], (suffix & 0x00ffffffffffffffL) | 0x2c00000000000000L, length - 1);
    } else {
      writer.writeString(enumNames[index]);
    }
  }
}
