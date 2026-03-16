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

package org.apache.fory.config;

/**
 * Encoding option for long. Default encoding is fory SLI(Small long as int) encoding: {@link
 * #TAGGED}.
 */
public enum LongEncoding {
  /**
   * Fory Tagged int64 Encoding:
   * <li>If long is in [0xc0000000, 0x3fffffff], encode as 4 bytes int: {@code | little-endian:
   *     ((int) value) << 1 |}
   * <li>Otherwise write as 9 bytes: {@code | 0b1 | little-endian 8bytes long |}.
   *
   *     <p>Faster than {@link #VARINT}, but compression is not good as {@link #VARINT} such as for
   *     ints in short range.
   */
  TAGGED,
  /**
   * Fory Progressive Variable-length Long Encoding:
   * <li>positive long format: first bit in every byte indicate whether has next byte, then next
   *     byte should be read util first bit is unset.
   * <li>Negative number will be converted to positive number by {@code (v << 1) ^ (v >> 63)} to
   *     reduce cost of small negative numbers.
   */
  VARINT,
  /** Write long as little endian 8bytes, no compression. */
  FIXED,
}
