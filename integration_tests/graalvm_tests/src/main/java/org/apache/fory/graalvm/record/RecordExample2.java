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

package org.apache.fory.graalvm.record;

import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.util.Preconditions;

public class RecordExample2 {
  private record Record(int f1, String f2, List<String> f3, Map<String, Long> f4) {}

  static Fory fory;

  static {
    fory = createFory();
  }

  private static Fory createFory() {
    Fory fory =
        Fory.builder()
            .withName(RecordExample2.class.getName())
            .withXlang(false)
            .requireClassRegistration(true)
            .withCompatible(false)
            .build();
    // register and generate serializer code.
    fory.register(Record.class);
    fory.register(Foo.class);
    fory.ensureSerializersCompiled();
    return fory;
  }

  public static void test(Fory fory) {
    Record record = new Record(10, "abc", List.of("str1", "str2"), Map.of("k1", 10L, "k2", 20L));
    byte[] bytes = fory.serialize(record);
    Object o = fory.deserialize(bytes);
    Preconditions.checkArgument(record.equals(o));
    Foo foo = new Foo(10, "abc", List.of("str1", "str2"), Map.of("k1", 10L, "k2", 20L));
    Object o2 = fory.deserialize(fory.serialize(foo));
    Preconditions.checkArgument(foo.equals(o2));
  }

  public static void main(String[] args) {
    System.out.println("RecordExample started");
    test(fory);
    System.out.println("RecordExample succeed 1/2");
    fory = createFory();
    test(fory);
    System.out.println("RecordExample2 succeed");
  }
}
