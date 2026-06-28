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

package org.apache.fory.json.meta;

public final class JsonAsciiToken {
  private static final int MAX_SUFFIX_LENGTH = 3;

  private JsonAsciiToken() {}

  public static boolean isPackable(String token) {
    int length = token.length();
    if (length == 0 || suffixLength(length) > MAX_SUFFIX_LENGTH) {
      return false;
    }
    for (int i = 0; i < length; i++) {
      char ch = token.charAt(i);
      if (ch == 0 || ch > 0xFF) {
        return false;
      }
    }
    return true;
  }

  public static long prefix(String token) {
    int prefixLength = Math.min(token.length(), Long.BYTES);
    long value = 0;
    for (int i = 0; i < prefixLength; i++) {
      value |= (long) (token.charAt(i) & 0xFF) << (i << 3);
    }
    return value;
  }

  public static long prefixMask(int tokenLength) {
    int prefixLength = Math.min(tokenLength, Long.BYTES);
    return prefixLength == Long.BYTES ? -1L : (1L << (prefixLength << 3)) - 1;
  }

  public static int suffix(String token) {
    int suffixLength = suffixLength(token.length());
    int value = 0;
    for (int i = 0; i < suffixLength; i++) {
      value |= (token.charAt(i + Long.BYTES) & 0xFF) << (i << 3);
    }
    return value;
  }

  public static int suffixLength(int tokenLength) {
    return Math.max(0, tokenLength - Long.BYTES);
  }
}
