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

package org.apache.fory.serializer;

import static org.testng.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import org.apache.fory.ForyTestBase;
import org.testng.annotations.Test;

public class StringEncodingUtilsTest extends ForyTestBase {
  @Test
  public void testUTF8ToUTF16() {
    String input = "jbmbmner8 jhk hj \n \t üäßß@µ你好";
    byte[] utf8 = input.getBytes(StandardCharsets.UTF_8);
    char[] utf16Chars = new char[utf8.length * 2];
    int readLen = StringEncodingUtils.convertUTF8ToUTF16(utf8, 0, utf8.length, utf16Chars);
    String result = new String(utf16Chars, 0, readLen);
    assertEquals(result, input);

    byte[] utf16Bytes = new byte[utf8.length * 4];
    readLen = StringEncodingUtils.convertUTF8ToUTF16(utf8, 0, utf8.length, utf16Bytes);
    result = new String(utf16Bytes, 0, readLen, StandardCharsets.UTF_16LE);
    assertEquals(result, input);
  }

  @Test
  public void testUTF16ToUTF8() {
    String input = "jbmbmner8 jhk hj \n \t üäßß@µ你好";
    char[] utf16 = new char[input.length()];
    byte[] utf8 = new byte[input.length() * 3];
    input.getChars(0, input.length(), utf16, 0);
    int readLen = StringEncodingUtils.convertUTF16ToUTF8(utf16, utf8, 0);
    String result = new String(utf8, 0, readLen, StandardCharsets.UTF_8);
    assertEquals(result, input);

    byte[] utf16Bytes = input.getBytes(StandardCharsets.UTF_16LE);
    readLen = StringEncodingUtils.convertUTF16ToUTF8(utf16Bytes, utf8, 0);
    result = new String(utf8, 0, readLen, StandardCharsets.UTF_8);
    assertEquals(result, input);
  }
}
