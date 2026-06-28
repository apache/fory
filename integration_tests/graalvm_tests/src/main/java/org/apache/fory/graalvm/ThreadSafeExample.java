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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.collection.Collections;
import org.apache.fory.util.Preconditions;

public class ThreadSafeExample {
  static ThreadSafeFory fory;

  static {
    fory =
        new ThreadLocalFory(
            classLoader -> {
              Fory f =
                  Fory.builder()
                      .withName(ThreadSafeExample.class.getName())
                      .withXlang(false)
                      .requireClassRegistration(true)
                      .withCompatible(false)
                      .build();
              // register and generate serializer code.
              f.register(Foo.class);
              f.ensureSerializersCompiled();
              return f;
            });
    System.out.println("Init fory at build time");
  }

  public static void main(String[] args) throws Throwable {
    test(fory);
    System.out.println("ThreadSafeExample succeed");
  }

  static void test(ThreadSafeFory fory) throws Throwable {
    ThreadSafeExample threadSafeExample = new ThreadSafeExample();
    threadSafeExample.test();
    System.out.println("single thread works");
    ExecutorService service = Executors.newFixedThreadPool(10);
    System.out.println("Start to submit tasks");
    for (int i = 0; i < 1000; i++) {
      service.submit(
          () -> {
            try {
              threadSafeExample.test();
            } catch (Throwable t) {
              threadSafeExample.throwable = t;
            }
          });
    }
    Thread.sleep(1000);
    service.shutdown();
    service.awaitTermination(10, TimeUnit.SECONDS);
    System.out.println("tasks finished");
    if (threadSafeExample.throwable != null) {
      throw threadSafeExample.throwable;
    }
    System.out.println("tasks finished succeed");
  }

  private volatile Throwable throwable;

  private void test() {
    Preconditions.checkArgument("abc".equals(fory.deserialize(fory.serialize("abc"))));
    Preconditions.checkArgument(
        List.of(1, 2, 3).equals(fory.deserialize(fory.serialize(List.of(1, 2, 3)))));
    List<String> list = Collections.ofArrayList("a", "b", "c");
    Preconditions.checkArgument(list.equals(fory.deserialize(fory.serialize(list))));
    Map<String, Integer> map = Collections.ofHashMap("k1", 1, "k2", 2);
    Preconditions.checkArgument(map.equals(fory.deserialize(fory.serialize(map))));
    map = Map.of("k1", 1, "k2", 2);
    Preconditions.checkArgument(map.equals(fory.deserialize(fory.serialize(map))));
    Foo foo = new Foo(10, "abc", List.of("str1", "str2"), Map.of("k1", 10L, "k2", 20L));
    Object o = fory.deserialize(fory.serialize(foo));
    Preconditions.checkArgument(foo.equals(o));
  }
}
