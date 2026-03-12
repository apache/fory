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

import java.util.ArrayList;
import java.util.List;
import org.apache.fory.Fory;
import org.apache.fory.logging.Logger;
import org.apache.fory.logging.LoggerFactory;
import org.openjdk.jmh.Main;
import org.openjdk.jmh.annotations.Benchmark;

/** Test suite for collection. */
public class CollectionSuite {
  private static final Logger LOG = LoggerFactory.getLogger(CollectionSuite.class);

  public static void main(String[] args) throws Exception {
    if (args.length == 0) {
      String commandLine =
          "org.apache.fory.*CollectionSuite.* -f 3 -wi 5 -i 5 -t 1 -w 2s -r 2s -rf csv";
      System.out.println(commandLine);
      args = commandLine.split(" ");
    }
    Main.main(args);
  }

  private static Fory fory = Fory.builder().build();
  private static List<Integer> list1 = new ArrayList<>(1024);
  private static byte[] list1Bytes;

  static {
    for (int i = 0; i < 1024; i++) {
      list1.add(i % 255);
    }
    list1Bytes = fory.serialize(list1);
    LOG.info("Size: {}", list1Bytes.length);
  }

  @Benchmark
  public Object serializeArrayList() {
    return fory.serialize(list1);
  }

  @Benchmark
  public Object deserializeArrayList() {
    return fory.deserialize(list1Bytes);
  }
  // Benchmark                              Mode  Cnt       Score        Error  Units
  // CollectionSuite.deserializeArrayList  thrpt    3  175281.624 ± 142913.891  ops/s
  // CollectionSuite.serializeArrayList    thrpt    3  137648.540 ± 158192.786  ops/s
}
