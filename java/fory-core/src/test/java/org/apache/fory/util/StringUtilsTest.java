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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import org.apache.fory.ForyTestBase;
import org.apache.fory.serializer.StringEncodingUtils;
import org.testng.annotations.Test;

public class StringUtilsTest extends ForyTestBase {
  @Test
  public void testEncodeHexString() {
    assertEquals(
        StringUtils.encodeHexString(new byte[] {1, 2, 10, 20, 30, 40, 50}), "01020a141e2832");
  }

  @Test
  public void testRandom() {
    StringUtils.random(40);
    assertEquals(StringUtils.random(4, 7), "#,q7");
  }

  @Test
  public void testFormat() {
    assertEquals(StringUtils.format("${a}, ${b}", "a", 1, "b", "abc"), "1, abc");
  }

  @Test
  public void testStripBlankLines() {
    assertEquals(StringUtils.stripBlankLines("a\n \nb\n"), "a\nb\n");
  }

  @Test
  public void testCapitalize() {
    assertEquals(StringUtils.capitalize("abc"), "Abc");
    assertEquals(StringUtils.uncapitalize("Abc"), "abc");
  }

  @Test
  public void testIsBlank() {
    assertFalse(StringUtils.isBlank("abc"));
    assertTrue(StringUtils.isNotBlank("abc"));
    assertTrue(StringUtils.isBlank("   "));
    assertTrue(StringUtils.isBlank(null));
  }

  @Test
  public void testLowerUnderscoreToLowerCamelCase() {
    assertEquals(StringUtils.lowerUnderscoreToLowerCamelCase("some_variable"), "someVariable");
    assertEquals(
        StringUtils.lowerUnderscoreToLowerCamelCase("some_long_variable"), "someLongVariable");
    assertEquals(
        StringUtils.lowerUnderscoreToLowerCamelCase("some_123variable"), "some123variable");
    assertEquals(
        StringUtils.lowerUnderscoreToLowerCamelCase("some_variable123"), "someVariable123");
    assertEquals(
        StringUtils.lowerUnderscoreToLowerCamelCase("some_variable123"), "someVariable123");
    assertEquals(
        StringUtils.lowerUnderscoreToLowerCamelCase("some_123_variable"), "some123Variable");
    assertEquals(
        StringUtils.lowerUnderscoreToLowerCamelCase("some_variable_123"), "someVariable123");
  }

  @Test
  public void testLowerCamelToLowerUnderscore() {
    assertEquals(StringUtils.lowerCamelToLowerUnderscore("someVariable"), "some_variable");
    assertEquals(StringUtils.lowerCamelToLowerUnderscore("someLongVariable"), "some_long_variable");
    assertEquals(StringUtils.lowerCamelToLowerUnderscore("some123variable"), "some123variable");
    assertEquals(StringUtils.lowerCamelToLowerUnderscore("someVariable123"), "some_variable123");
  }

  @Test(dataProvider = "endian")
  public void testVectorizedLatinCheckAlgorithm(boolean endian) {
    // assertTrue(isLatin("Fory".toCharArray(), endian));
    // assertTrue(isLatin(StringUtils.random(8 * 10).toCharArray(), endian));
    // test unaligned
    assertTrue(isLatin((StringUtils.random(8 * 10) + "1").toCharArray(), endian));
    assertTrue(isLatin((StringUtils.random(8 * 10) + "12").toCharArray(), endian));
    assertTrue(isLatin((StringUtils.random(8 * 10) + "123").toCharArray(), endian));
    assertFalse(isLatin("你好, Fory".toCharArray(), endian));
    assertFalse(isLatin((StringUtils.random(8 * 10) + "你好").toCharArray(), endian));
    assertFalse(isLatin((StringUtils.random(8 * 10) + "1你好").toCharArray(), endian));
  }

  private boolean isLatin(char[] chars, boolean isLittle) {
    for (char c : chars) {
      if (c > 0xFF) {
        return false;
      }
    }
    return true;
  }

  @Test
  public void testLatinCheck() {
    assertTrue(StringEncodingUtils.isLatin("Fory".toCharArray()));
    assertTrue(StringEncodingUtils.isLatin(StringUtils.random(8 * 10).toCharArray()));
    // test unaligned
    assertTrue(StringEncodingUtils.isLatin((StringUtils.random(8 * 10) + "1").toCharArray()));
    assertTrue(StringEncodingUtils.isLatin((StringUtils.random(8 * 10) + "12").toCharArray()));
    assertTrue(StringEncodingUtils.isLatin((StringUtils.random(8 * 10) + "123").toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("你好, Fory".toCharArray()));
    assertFalse(StringEncodingUtils.isLatin((StringUtils.random(8 * 10) + "你好").toCharArray()));
    assertFalse(StringEncodingUtils.isLatin((StringUtils.random(8 * 10) + "1你好").toCharArray()));
    assertFalse(StringEncodingUtils.isLatin((StringUtils.random(11) + "你").toCharArray()));
    assertFalse(StringEncodingUtils.isLatin((StringUtils.random(10) + "你好").toCharArray()));
    assertFalse(StringEncodingUtils.isLatin((StringUtils.random(9) + "性能好").toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("\u1234".toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("a\u1234".toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("ab\u1234".toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("abc\u1234".toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("abcd\u1234".toCharArray()));
    assertFalse(StringEncodingUtils.isLatin("Javaone Keynote\u1234".toCharArray()));
  }
}
