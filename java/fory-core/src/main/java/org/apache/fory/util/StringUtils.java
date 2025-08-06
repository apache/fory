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

package org.apache.fory.util;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.apache.fory.memory.Platform;

public class StringUtils {
  // A long mask used to clear all-higher bits of char in a super-word way.
  public static final long MULTI_CHARS_NON_LATIN_MASK;

  public static final long MULTI_CHARS_NON_ASCII_MASK;

  private static final char[] BASE16_CHARS2 = {
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
  };

  static {
    if (Platform.IS_LITTLE_ENDIAN) {
      // latin chars will be 0xXX,0x00;0xXX,0x00 in byte order;
      // Using 0x00,0xff(0xff00) to clear latin bits.
      MULTI_CHARS_NON_LATIN_MASK = 0xff00ff00ff00ff00L;
      MULTI_CHARS_NON_ASCII_MASK = 0xff80ff80ff80ff80L;
    } else {
      // latin chars will be 0x00,0xXX;0x00,0xXX in byte order;
      // Using 0x00,0xff(0x00ff) to clear latin bits.
      MULTI_CHARS_NON_LATIN_MASK = 0x00ff00ff00ff00ffL;
      MULTI_CHARS_NON_ASCII_MASK = 0x80ff80ff80ff80ffL;
    }
  }

  /** Converts a bytes array into a hexadecimal string. */
  public static String encodeHexString(final byte[] data) {
    StringBuilder result = new StringBuilder(data.length * 2);
    for (byte b : data) {
      result.append(BASE16_CHARS2[(b >>> 4) & 0xF]).append(BASE16_CHARS2[b & 0xF]);
    }
    return result.toString();
  }

  /** Format a string template by replacing all `${xxx}` into provided values. */
  public static String format(String str, Object... args) {
    // TODO(chaokunyang) optimize performance.
    // TODO(chaokunyang) support `$xxx`.
    StringBuilder builder = new StringBuilder(str);
    if (args.length % 2 != 0) {
      throw new IllegalArgumentException(
          "args length must be multiple of 2, but get " + args.length);
    }
    Map<String, String> values = new HashMap<>();
    for (int i = 0; i < args.length; i += 2) {
      values.put(args[i].toString(), args[i + 1].toString());
    }

    for (Map.Entry<String, String> entry : values.entrySet()) {
      int start;
      String pattern = "${" + entry.getKey() + "}";
      String value = entry.getValue();

      // Replace every occurrence of %(key) with value
      while ((start = builder.indexOf(pattern)) != -1) {
        builder.replace(start, start + pattern.length(), value);
      }
    }

    return builder.toString();
  }

  public static String stripBlankLines(String str) {
    StringBuilder builder = new StringBuilder();
    String[] split = str.split("\n");
    for (String s : split) {
      if (!s.trim().isEmpty()) {
        builder.append(s).append('\n');
      }
    }
    if (builder.charAt(builder.length() - 1) == '\n') {
      return builder.toString();
    } else {
      return builder.substring(0, builder.length() - 1);
    }
  }

  public static String random(int size) {
    return random(size, new Random());
  }

  public static String random(int size, int rand) {
    return random(size, new Random(rand));
  }

  public static String random(int size, Random random) {
    char[] chars = new char[size];
    char start = ' ';
    char end = 'z' + 1;
    int gap = end - start;
    for (int i = 0; i < size; i++) {
      chars[i] = (char) (start + random.nextInt(gap));
    }
    return new String(chars);
  }

  /**
   * Capitalizes a String changing the first character to title case as per {@link
   * Character#toTitleCase(int)}. No other characters are changed.
   */
  // Copied from `org.apache.commons.lang3.StringUtils` to avoid introducing a new dependency.
  public static String capitalize(final String str) {
    int strLen;
    if (str == null || (strLen = str.length()) == 0) {
      return str;
    }

    final int firstCodepoint = str.codePointAt(0);
    final int newCodePoint = Character.toTitleCase(firstCodepoint);
    if (firstCodepoint == newCodePoint) {
      // already capitalized
      return str;
    }

    final int[] newCodePoints = new int[strLen]; // cannot be longer than the char array
    int outOffset = 0;
    newCodePoints[outOffset++] = newCodePoint; // copy the first codepoint
    for (int inOffset = Character.charCount(firstCodepoint); inOffset < strLen; ) {
      final int codepoint = str.codePointAt(inOffset);
      newCodePoints[outOffset++] = codepoint; // copy the remaining ones
      inOffset += Character.charCount(codepoint);
    }
    return new String(newCodePoints, 0, outOffset);
  }

  /** Uncapitalizes a String, changing the first character to lower case. */
  // Copied from `org.apache.commons.lang3.StringUtils` to avoid introducing a new dependency.
  public static String uncapitalize(final String str) {
    int strLen;
    if (str == null || (strLen = str.length()) == 0) {
      return str;
    }

    final int firstCodepoint = str.codePointAt(0);
    final int newCodePoint = Character.toLowerCase(firstCodepoint);
    if (firstCodepoint == newCodePoint) {
      // already capitalized
      return str;
    }

    final int[] newCodePoints = new int[strLen]; // cannot be longer than the char array
    int outOffset = 0;
    newCodePoints[outOffset++] = newCodePoint; // copy the first codepoint
    for (int inOffset = Character.charCount(firstCodepoint); inOffset < strLen; ) {
      final int codepoint = str.codePointAt(inOffset);
      newCodePoints[outOffset++] = codepoint; // copy the remaining ones
      inOffset += Character.charCount(codepoint);
    }
    return new String(newCodePoints, 0, outOffset);
  }

  /**
   * Checks if a CharSequence is empty (""), null or whitespace only. Whitespace is defined by
   * Character.isWhitespace(char).
   */
  // Copied from `org.apache.commons.lang3.StringUtils` to avoid introducing a new dependency.
  public static boolean isBlank(final CharSequence cs) {
    int strLen;
    if (cs == null || (strLen = cs.length()) == 0) {
      return true;
    }
    for (int i = 0; i < strLen; i++) {
      if (!Character.isWhitespace(cs.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  // Copied from `org.apache.commons.lang3.StringUtils` to avoid introducing a new dependency.
  public static boolean isNotBlank(final CharSequence cs) {
    return !isBlank(cs);
  }

  public static void shuffle(StringBuilder sb) {
    shuffle(sb, 7);
  }

  public static void shuffle(StringBuilder sb, int seed) {
    Random rand = new Random(seed);
    for (int i = sb.length() - 1; i > 1; i--) {
      int swapWith = rand.nextInt(i);
      char tmp = sb.charAt(swapWith);
      sb.setCharAt(swapWith, sb.charAt(i));
      sb.setCharAt(i, tmp);
    }
  }

  public static String repeat(String str, int numRepeat) {
    StringBuilder builder = new StringBuilder(numRepeat * str.length());
    for (int i = 0; i < numRepeat; i++) {
      builder.append(str);
    }
    return builder.toString();
  }

  // example: "variable_name" -> "variableName"
  public static String lowerUnderscoreToLowerCamelCase(String lowerUnderscore) {
    StringBuilder builder = new StringBuilder();
    int length = lowerUnderscore.length();

    int index;
    int fromIndex = 0;
    while ((index = lowerUnderscore.indexOf('_', fromIndex)) != -1) {
      builder.append(lowerUnderscore, fromIndex, index);

      if (length >= index + 1) {
        char symbol = lowerUnderscore.charAt(index + 1);
        if (symbol >= 'a' && symbol <= 'z') {
          builder.append(Character.toUpperCase(symbol));
          fromIndex = index + 2;
          continue;
        }
      }

      fromIndex = index + 1;
    }

    if (fromIndex < length) {
      builder.append(lowerUnderscore, fromIndex, length);
    }

    return builder.toString();
  }

  // example: "variableName" -> "variable_name"
  public static String lowerCamelToLowerUnderscore(String lowerCamel) {
    StringBuilder builder = new StringBuilder();
    int length = lowerCamel.length();

    int fromIndex = 0;

    for (int i = 0; i < length; i++) {
      char symbol = lowerCamel.charAt(i);
      if (symbol >= 'A' && symbol <= 'Z') {
        builder.append(lowerCamel, fromIndex, i).append('_').append(Character.toLowerCase(symbol));
        fromIndex = i + 1;
      }
    }

    if (fromIndex < length) {
      builder.append(lowerCamel, fromIndex, length);
    }

    return builder.toString();
  }

  public static boolean isLatin(char[] chars) {
    return isLatin(chars, 0);
  }

  public static boolean isLatin(char[] chars, int start) {
    if (start > chars.length) {
      return false;
    }
    int byteOffset = start << 1;
    int numChars = chars.length;
    int vectorizedLen = numChars >> 2;
    int vectorizedChars = vectorizedLen << 2;
    int endOffset = Platform.CHAR_ARRAY_OFFSET + (vectorizedChars << 1);
    boolean isLatin = true;
    for (int offset = Platform.CHAR_ARRAY_OFFSET + byteOffset; offset < endOffset; offset += 8) {
      // check 4 chars in a vectorized way, 4 times faster than scalar check loop.
      // See benchmark in CompressStringSuite.latinSuperWordCheck.
      long multiChars = Platform.getLong(chars, offset);
      if ((multiChars & MULTI_CHARS_NON_LATIN_MASK) != 0) {
        isLatin = false;
        break;
      }
    }
    if (isLatin) {
      for (int i = vectorizedChars; i < numChars; i++) {
        if (chars[i] > 0xFF) {
          isLatin = false;
          break;
        }
      }
    }
    return isLatin;
  }

  // 这是一个辅助方法，用于判断字符串是否只包含Latin-1字符
  public static boolean isLatin1(String s) {
    for (int i = 0; i < s.length(); i++) {
      // char 是16位的，Latin-1 字符的码点都在 255 (0xFF) 以内
      if (s.charAt(i) > '\u00FF') {
        return false;
      }
    }
    return true;
  }
}
