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

import java.nio.charset.StandardCharsets;
import org.apache.fory.Fory;
import org.apache.fory.memory.MemoryBuffer;
import org.apache.fory.platform.JdkVersion;
import org.apache.fory.serializer.StringSerializer;
import org.apache.fory.util.Preconditions;
import org.apache.fory.util.StringUtils;
import org.openjdk.jmh.Main;

public class NewJava11StringSuite {
  static String str = StringUtils.random(10);
  static byte[] strBytes = str.getBytes(StandardCharsets.ISO_8859_1);
  static byte coder = 0;

  static {
    if (JdkVersion.MAJOR_VERSION > 8) {
      Preconditions.checkArgument(new String(strBytes, StandardCharsets.ISO_8859_1).equals(str));
    }
  }

  private static Fory fory =
      Fory.builder()
          .withStringCompressed(true)
          .requireClassRegistration(false)
          .withCompatible(true)
          .build();
  private static StringSerializer stringSerializer = new StringSerializer(fory.getConfig());
  private static MemoryBuffer buffer = MemoryBuffer.newHeapBuffer(512);

  static {
    stringSerializer.writeString(buffer, str);
  }

  // @Benchmark
  public Object createJDK11StringByCopyStr() {
    return new String(str);
  }

  // @Benchmark
  public Object createJDK8StringByMethodHandle() {
    return StringSerializer.newBytesStringZeroCopy(coder, strBytes);
  }

  // @Benchmark
  public Object createJDK8StringByFory() {
    buffer.readerIndex(0);
    return stringSerializer.readString(buffer);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*NewJava11StringSuite.* -f 3 -wi 5 -i 3 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }
}
