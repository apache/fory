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

package org.apache.fory.benchmark;

import org.apache.fory.memory.Platform;
import org.apache.fory.reflect.ReflectionUtils;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.util.StringUtils;
import org.openjdk.jmh.Main;

public class NewStringSuite {

  static String str = StringUtils.random(230);
  static char[] strData = str.toCharArray();
  static byte[] array = new byte[strData.length * 2];

  // @Benchmark
  public Object createJDK8StringByCopyCtr() {
    return new String(str);
  }

  // @Benchmark
  public Object createJDK8StringByCopy() {
    return new String(strData);
  }

  private static final long STRING_VALUE_FIELD_OFFSET =
      ReflectionUtils.getFieldOffset(String.class, "value");
  private static String stubStr = new String(new char[] {Character.MAX_VALUE, Character.MIN_VALUE});

  // @Benchmark
  public Object createJDK8StringByUnsafe() {
    String str = new String(stubStr);
    Platform.putObject(str, STRING_VALUE_FIELD_OFFSET, strData);
    return str;
  }

  // @Benchmark
  public Object createJDK8StringByMethodHandle() {
    return StringSerializer.newCharsStringZeroCopy(strData);
  }

  // @Benchmark
  public Object benchPutCharsSplit() {
    putCharsSplit(strData, strData.length, array, 0);
    return array;
  }

  // @Benchmark
  public Object benchPutChars() {
    putChars(strData, 0, array, 0, strData.length);
    return array;
  }

  static void putCharsSplit(char[] src, final int charLen, byte[] target, int targetOffset) {
    for (int i = 0; i < charLen; i++) {
      target[targetOffset + i << 1] = (byte) (src[i] >> 0);
    }
    for (int i = 0; i < charLen; i++) {
      target[targetOffset + (i << 1) + 1] = (byte) (src[i] >> 8);
    }
  }

  static void putChars(char[] str, int off, byte[] val, int index, int end) {
    while (off < end) {
      putChar(val, index++, str[off++]);
    }
  }

  static void putChar(byte[] val, int index, int c) {
    index <<= 1;
    // FIXME JDK11 utf16 string uses little-endian order
    val[index++] = (byte) (c >> 0);
    val[index] = (byte) (c >> 8);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*NewStringSuite.* -f 1 -wi 10 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
