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
import org.apache.fory.json.meta.JsonSubtypeScanInfo;
import org.apache.fory.json.writer.JsonStringEscaper;

/** Shared, fully validated closed-subtype definition for one ForyJson runtime. */
final class JsonSubTypesInfo {
  final boolean wrapperObject;
  final Class<?>[] classes;
  final String[] names;
  final JsonSubtypeScanInfo scanInfo;
  final byte[][] stringNamePrefixes;
  final byte[][] stringUtf16NamePrefixes;
  final byte[][] utf8NamePrefixes;
  final byte[][] stringDiscriminators;
  final byte[][] stringUtf16Discriminators;
  final byte[][] utf8Discriminators;

  JsonSubTypesInfo(boolean wrapperObject, String property, Class<?>[] classes, String[] names) {
    this.wrapperObject = wrapperObject;
    this.classes = classes;
    this.names = names;
    scanInfo = new JsonSubtypeScanInfo(property, names);
    stringNamePrefixes = wrapperObject ? new byte[names.length][] : null;
    stringUtf16NamePrefixes = wrapperObject ? new byte[names.length][] : null;
    utf8NamePrefixes = wrapperObject ? new byte[names.length][] : null;
    stringDiscriminators = wrapperObject ? null : new byte[names.length][];
    stringUtf16Discriminators = wrapperObject ? null : new byte[names.length][];
    utf8Discriminators = wrapperObject ? null : new byte[names.length][];
    byte[] stringProperty =
        wrapperObject
            ? null
            : JsonStringEscaper.escapedNamePrefix(property, true)
                .getBytes(StandardCharsets.ISO_8859_1);
    byte[] utf8Property =
        wrapperObject
            ? null
            : JsonStringEscaper.escapedNamePrefix(property, false).getBytes(StandardCharsets.UTF_8);
    for (int i = 0; i < names.length; i++) {
      if (wrapperObject) {
        String namePrefix = JsonStringEscaper.escapedNamePrefix(names[i], true);
        stringNamePrefixes[i] = namePrefix.getBytes(StandardCharsets.ISO_8859_1);
        stringUtf16NamePrefixes[i] = toUtf16(stringNamePrefixes[i]);
        utf8NamePrefixes[i] =
            JsonStringEscaper.escapedNamePrefix(names[i], false).getBytes(StandardCharsets.UTF_8);
      } else {
        stringDiscriminators[i] = join(stringProperty, JsonStringEscaper.stringValue(names[i]));
        stringUtf16Discriminators[i] = toUtf16(stringDiscriminators[i]);
        utf8Discriminators[i] = join(utf8Property, JsonStringEscaper.utf8Value(names[i]));
      }
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
