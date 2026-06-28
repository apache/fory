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

package org.apache.fory.json.writer;

import java.nio.charset.StandardCharsets;
import org.apache.fory.json.ForyJsonException;

public final class JsonStringEscaper {
  private JsonStringEscaper() {}

  public static String escapedNamePrefix(String name, boolean escapeNonLatin1) {
    StringBuilder builder = new StringBuilder(name.length() + 3);
    appendQuoted(builder, name, escapeNonLatin1);
    builder.append(':');
    return builder.toString();
  }

  public static byte[] stringValue(String value) {
    return escapedString(value, true).getBytes(StandardCharsets.ISO_8859_1);
  }

  public static byte[] utf8Value(String value) {
    return escapedString(value, false).getBytes(StandardCharsets.UTF_8);
  }

  private static String escapedString(String value, boolean escapeNonLatin1) {
    StringBuilder builder = new StringBuilder(value.length() + 2);
    appendQuoted(builder, value, escapeNonLatin1);
    return builder.toString();
  }

  private static void appendQuoted(StringBuilder builder, String value, boolean escapeNonLatin1) {
    builder.append('"');
    int length = value.length();
    for (int i = 0; i < length; i++) {
      char ch = value.charAt(i);
      switch (ch) {
        case '"':
          builder.append("\\\"");
          break;
        case '\\':
          builder.append("\\\\");
          break;
        case '\b':
          builder.append("\\b");
          break;
        case '\f':
          builder.append("\\f");
          break;
        case '\n':
          builder.append("\\n");
          break;
        case '\r':
          builder.append("\\r");
          break;
        case '\t':
          builder.append("\\t");
          break;
        default:
          if (Character.isHighSurrogate(ch)) {
            if (i + 1 >= length) {
              throw new ForyJsonException("Unpaired high surrogate in string");
            }
            char low = value.charAt(++i);
            if (!Character.isLowSurrogate(low)) {
              throw new ForyJsonException("Unpaired high surrogate in string");
            }
            if (escapeNonLatin1) {
              appendUnicodeEscape(builder, ch);
              appendUnicodeEscape(builder, low);
            } else {
              builder.append(ch);
              builder.append(low);
            }
          } else if (Character.isLowSurrogate(ch)) {
            throw new ForyJsonException("Unpaired low surrogate in string");
          } else if (ch < 0x20 || escapeNonLatin1 && ch > 0xff) {
            appendUnicodeEscape(builder, ch);
          } else {
            builder.append(ch);
          }
      }
    }
    builder.append('"');
  }

  private static void appendUnicodeEscape(StringBuilder builder, char ch) {
    builder.append("\\u");
    builder.append(hex((ch >>> 12) & 0xF));
    builder.append(hex((ch >>> 8) & 0xF));
    builder.append(hex((ch >>> 4) & 0xF));
    builder.append(hex(ch & 0xF));
  }

  private static char hex(int value) {
    return (char) (value < 10 ? '0' + value : 'a' + value - 10);
  }
}
