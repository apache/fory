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

/** Exact packed-word predicates used after the printable-ASCII common path rejects a word. */
final class JsonAsciiWordExactPredicates {
  static final long ASCII_CONTROL_OFFSET = 0x6060606060606060L;
  static final long QUOTE_BYTES_COMPLEMENT = ~0x2222222222222222L;

  private static final long HIGH_BITS = 0x8080808080808080L;
  private static final int INT_HIGH_BITS = 0x80808080;
  private static final int SHORT_HIGH_BITS = 0x8080;
  private static final int INT_ASCII_CONTROL_OFFSET = 0x60606060;
  private static final int SHORT_ASCII_CONTROL_OFFSET = 0x6060;
  private static final long ONE_BYTES = 0x0101010101010101L;
  private static final int INT_ONE_BYTES = 0x01010101;
  private static final int SHORT_ONE_BYTES = 0x0101;
  private static final int INT_QUOTE_BYTES_COMPLEMENT = ~0x22222222;
  private static final int SHORT_QUOTE_BYTES_COMPLEMENT = ~0x2222;
  private static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;

  private JsonAsciiWordExactPredicates() {}

  static boolean isWord(long word) {
    long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    return (((word + ASCII_CONTROL_OFFSET) & ~word) & HIGH_BITS) == HIGH_BITS
        && (((word ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS) == HIGH_BITS
        && notBackslashMask == HIGH_BITS;
  }

  static boolean isWords(long word0, long word1, long notBackslashMask) {
    return ((word0 + ASCII_CONTROL_OFFSET)
            & (word1 + ASCII_CONTROL_OFFSET)
            & ((word0 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ QUOTE_BYTES_COMPLEMENT) + ONE_BYTES)
            & notBackslashMask)
        == HIGH_BITS;
  }

  static boolean isInt(int word, int notBackslashMask) {
    return (((word + INT_ASCII_CONTROL_OFFSET) & ~word) & INT_HIGH_BITS) == INT_HIGH_BITS
        && (((word ^ INT_QUOTE_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS) == INT_HIGH_BITS
        && notBackslashMask == INT_HIGH_BITS;
  }

  static boolean isShort(int word, int notBackslashMask) {
    return (((word + SHORT_ASCII_CONTROL_OFFSET) & ~word) & SHORT_HIGH_BITS) == SHORT_HIGH_BITS
        && (((word ^ SHORT_QUOTE_BYTES_COMPLEMENT) + SHORT_ONE_BYTES) & SHORT_HIGH_BITS)
            == SHORT_HIGH_BITS
        && notBackslashMask == SHORT_HIGH_BITS;
  }
}
