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

/**
 * Packed-word predicates for ASCII bytes that do not require JSON escaping.
 *
 * <p>The probability-first {@code 0x5D} printable-ASCII fast path is based in part on WAST's escape
 * predicate:
 * https://github.com/wycst/wast/blob/7d3d85579c831647b91af3356d4225cf65be6ea3/src/main/java/io/github/wycst/wast/json/JSONGeneral.java#L744-L815
 */
final class JsonAsciiWordPredicates {
  // Utf8JsonWriter source-inlines its fixed-length fast check. Keep these constants package-visible
  // so that path can share the predicate constants without adding another helper call.
  static final long HIGH_BITS = 0x8080808080808080L;
  static final long ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D5D5D5D5DL;
  static final long ONE_BYTES = 0x0101010101010101L;
  static final long BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C5C5C5C5CL;

  private static final int INT_HIGH_BITS = 0x80808080;
  private static final int SHORT_HIGH_BITS = 0x8080;
  private static final int INT_ASCII_GT_QUOTE_OFFSET = 0x5D5D5D5D;
  private static final int SHORT_ASCII_GT_QUOTE_OFFSET = 0x5D5D;
  private static final int INT_ONE_BYTES = 0x01010101;
  private static final int SHORT_ONE_BYTES = 0x0101;
  private static final int INT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C5C5C;
  private static final int SHORT_BACKSLASH_BYTES_COMPLEMENT = ~0x5C5C;

  private JsonAsciiWordPredicates() {}

  // Keep the exact uncommon fallback outside these per-word predicates. Folding it back in makes
  // the standalone predicates too large for C2 to inline into the compact and short-string paths.
  static boolean isJsonAsciiWord(long word) {
    long notBackslashMask = ((word ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES) & HIGH_BITS;
    if ((notBackslashMask & (word + ASCII_GT_QUOTE_OFFSET)) == HIGH_BITS) {
      return true;
    }
    return JsonAsciiWordExactPredicates.isWord(word);
  }

  // Aggregate every exact rejection mask before branching. Splitting this back into per-word
  // calls adds one common-path branch for each eight bytes written.
  static boolean isJsonAsciiWords(long word0, long word1) {
    long notBackslashMask =
        ((word0 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & ((word1 ^ BACKSLASH_BYTES_COMPLEMENT) + ONE_BYTES)
            & HIGH_BITS;
    if ((notBackslashMask & (word0 + ASCII_GT_QUOTE_OFFSET) & (word1 + ASCII_GT_QUOTE_OFFSET))
        == HIGH_BITS) {
      return true;
    }
    return JsonAsciiWordExactPredicates.isWords(word0, word1, notBackslashMask);
  }

  static boolean isJsonAsciiInt(int word) {
    int notBackslashMask =
        ((word ^ INT_BACKSLASH_BYTES_COMPLEMENT) + INT_ONE_BYTES) & INT_HIGH_BITS;
    if ((notBackslashMask & (word + INT_ASCII_GT_QUOTE_OFFSET)) == INT_HIGH_BITS) {
      return true;
    }
    return JsonAsciiWordExactPredicates.isInt(word, notBackslashMask);
  }

  static boolean isJsonAsciiShort(int word) {
    int notBackslashMask =
        ((word ^ SHORT_BACKSLASH_BYTES_COMPLEMENT) + SHORT_ONE_BYTES) & SHORT_HIGH_BITS;
    if ((notBackslashMask & (word + SHORT_ASCII_GT_QUOTE_OFFSET)) == SHORT_HIGH_BITS) {
      return true;
    }
    return JsonAsciiWordExactPredicates.isShort(word, notBackslashMask);
  }
}
