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

package org.apache.fory.graalvm;

import java.util.List;
import java.util.Map;
import org.apache.fory.Fory;
import org.apache.fory.util.Preconditions;

public class XlangExample {
  static Fory fory;

  static {
    fory = Fory.builder().withName(XlangExample.class.getName()).withXlang(true).build();
    // register and generate serializer code.
    fory.register(Foo.class);
    fory.ensureSerializersCompiled();
  }

  static void test(Fory fory) {
    Preconditions.checkArgument("abc".equals(fory.deserialize(fory.serialize("abc"))));
    Preconditions.checkArgument(
        List.of(1, 2, 3).equals(fory.deserialize(fory.serialize(List.of(1, 2, 3)))));
    Map<String, Integer> map = Map.of("k1", 1, "k2", 2);
    Preconditions.checkArgument(map.equals(fory.deserialize(fory.serialize(map))));
    Foo foo = new Foo(10, "abc", List.of("str1", "str2"), Map.of("k1", 10L, "k2", 20L));
    byte[] bytes = fory.serialize(foo);
    System.out.println(fory.getTypeResolver().getSerializer(Foo.class));
    Object o = fory.deserialize(bytes);
    System.out.println(foo);
    System.out.println(o);
    Preconditions.checkArgument(foo.equals(o));
  }

  public static void main(String[] args) {
    test(fory);
    System.out.println("XlangExample succeed");
  }
}
