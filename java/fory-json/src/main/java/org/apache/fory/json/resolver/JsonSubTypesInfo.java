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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.apache.fory.json.annotation.JsonSubTypes.Inclusion;
import org.apache.fory.json.meta.JsonSubtypeScanInfo;
import org.apache.fory.json.writer.JsonStringEscaper;

/** Shared, fully validated closed-subtype definition for one ForyJson runtime. */
final class JsonSubTypesInfo {
  final Inclusion inclusion;
  final Class<?>[] classes;
  final JsonSubtypeScanInfo scanInfo;
  // PROPERTY prefixes contain the complete discriminator member; its member writer receives an
  // initial count of one and owns the following comma. Wrapper prefixes include ':' or ',' before
  // the complete subtype value. No writer adds a separate separator branch on the hot path.
  final byte[][] stringSubtypePrefixes;
  final byte[][] stringUtf16SubtypePrefixes;
  final byte[][] utf8SubtypePrefixes;

  JsonSubTypesInfo(Inclusion inclusion, String property, Class<?>[] classes, String[] names) {
    this.inclusion = inclusion;
    this.classes = classes;
    scanInfo = new JsonSubtypeScanInfo(property, names);
    stringSubtypePrefixes = new byte[names.length][];
    stringUtf16SubtypePrefixes = new byte[names.length][];
    utf8SubtypePrefixes = new byte[names.length][];
    byte[] stringProperty =
        inclusion == Inclusion.PROPERTY
            ? JsonStringEscaper.escapedNamePrefix(property, true)
                .getBytes(StandardCharsets.ISO_8859_1)
            : null;
    byte[] utf8Property =
        inclusion == Inclusion.PROPERTY
            ? JsonStringEscaper.escapedNamePrefix(property, false).getBytes(StandardCharsets.UTF_8)
            : null;
    for (int i = 0; i < names.length; i++) {
      if (inclusion == Inclusion.PROPERTY) {
        stringSubtypePrefixes[i] = join(stringProperty, JsonStringEscaper.stringValue(names[i]));
        utf8SubtypePrefixes[i] = join(utf8Property, JsonStringEscaper.utf8Value(names[i]));
      } else if (inclusion == Inclusion.WRAPPER_OBJECT) {
        stringSubtypePrefixes[i] =
            JsonStringEscaper.escapedNamePrefix(names[i], true)
                .getBytes(StandardCharsets.ISO_8859_1);
        utf8SubtypePrefixes[i] =
            JsonStringEscaper.escapedNamePrefix(names[i], false).getBytes(StandardCharsets.UTF_8);
      } else {
        stringSubtypePrefixes[i] = appendComma(JsonStringEscaper.stringValue(names[i]));
        utf8SubtypePrefixes[i] = appendComma(JsonStringEscaper.utf8Value(names[i]));
      }
      stringUtf16SubtypePrefixes[i] = toUtf16(stringSubtypePrefixes[i]);
    }
  }

  int classIndex(Class<?> type) {
    for (int i = 0; i < classes.length; i++) {
      if (classes[i] == type) {
        return i;
      }
    }
    return -1;
  }

  private static byte[] join(byte[] left, byte[] right) {
    byte[] result = new byte[left.length + right.length];
    System.arraycopy(left, 0, result, 0, left.length);
    System.arraycopy(right, 0, result, left.length, right.length);
    return result;
  }

  private static byte[] appendComma(byte[] value) {
    byte[] result = Arrays.copyOf(value, value.length + 1);
    result[value.length] = ',';
    return result;
  }

  private static byte[] toUtf16(byte[] bytes) {
    byte[] result = new byte[bytes.length << 1];
    boolean littleEndian = org.apache.fory.memory.NativeByteOrder.IS_LITTLE_ENDIAN;
    int byteOffset = littleEndian ? 0 : 1;
    for (int i = 0; i < bytes.length; i++) {
      result[(i << 1) + byteOffset] = bytes[i];
    }
    return result;
  }
}
