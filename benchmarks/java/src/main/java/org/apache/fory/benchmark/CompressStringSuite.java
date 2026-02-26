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

import java.nio.ByteBuffer;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.memory.Platform;
import org.apache.fory.util.StringUtils;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;

public class CompressStringSuite {
  private static byte[] heapBuffer = new byte[256];
  private static MemoryBuffer directBuffer =
      MemoryBuffer.fromByteBuffer(ByteBuffer.allocateDirect(256));
  private static String utf16Str = "你好,你好,你好,你好,你好, Fory" + StringUtils.random(64);
  private static char[] utf16StrChars = utf16Str.toCharArray();
  private static String latinStr = StringUtils.random(utf16StrChars.length, 0);
  private static char[] latinStrChars = latinStr.toCharArray();

  @Benchmark
  public Object compressLatinCharsToHeap() {
    byte[] heapBuffer = CompressStringSuite.heapBuffer;
    char[] latinStrChars = CompressStringSuite.latinStrChars;
    for (int i = 0; i < latinStrChars.length; i++) {
      heapBuffer[i] = (byte) (latinStrChars[i]);
    }
    directBuffer.writerIndex(0);
    directBuffer.writePrimitiveArray(heapBuffer, Platform.BYTE_ARRAY_OFFSET, latinStrChars.length);
    return directBuffer;
  }

  @Benchmark
  public Object compressLatinCharsToOffHeap() {
    MemoryBuffer directBuffer = CompressStringSuite.directBuffer;
    char[] latinStrChars = CompressStringSuite.latinStrChars;
    for (int i = 0; i < latinStrChars.length; i++) {
      directBuffer.putByte(i, (byte) (latinStrChars[i]));
    }
    return directBuffer;
  }

  @Benchmark
  public Object compressUTF16CharsToHeap() {
    byte[] heapBuffer = CompressStringSuite.heapBuffer;
    char[] utf16StrChars = CompressStringSuite.utf16StrChars;
    for (int i = 0; i < utf16StrChars.length; i++) {
      int index = i << 1;
      char c = utf16StrChars[i];
      heapBuffer[index++] = (byte) (c);
      heapBuffer[index] = (byte) (c >> 8);
    }
    directBuffer.writerIndex(0);
    directBuffer.writePrimitiveArray(
        heapBuffer, Platform.BYTE_ARRAY_OFFSET, utf16StrChars.length << 1);
    return directBuffer;
  }

  @Benchmark
  public Object compressUTF16CharsToOffHeap() {
    MemoryBuffer directBuffer = CompressStringSuite.directBuffer;
    char[] utf16StrChars = CompressStringSuite.utf16StrChars;
    for (int i = 0; i < utf16StrChars.length; i++) {
      int index = i << 1;
      char c = utf16StrChars[i];
      directBuffer.putByte(index++, (byte) (c));
      directBuffer.putByte(index, (byte) (c >> 8));
    }
    return directBuffer;
  }

  @Benchmark
  public Object latinScalarCheck() {
    char[] chars = latinStrChars;
    boolean isLatin = true;
    for (char c : chars) {
      if (c > 0xFF) {
        isLatin = false;
        break;
      }
    }
    return isLatin;
  }

  @Benchmark
  public Object latinSuperWordCheck() {
    return StringUtils.isLatin(latinStrChars);
  }

  public static void main(String[] args) throws Exception {
    System.out.printf("utf16StrChars length %s\n", utf16StrChars.length);
    System.out.printf("latinStrChars length %s\n", latinStrChars.length);
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*CompressStringSuite.latin* -f 1 -wi 3 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
