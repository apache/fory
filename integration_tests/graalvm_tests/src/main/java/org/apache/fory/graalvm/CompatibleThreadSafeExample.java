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

import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;

public class CompatibleThreadSafeExample {
  static ThreadSafeFory fory;

  static {
    fory =
        new ThreadLocalFory(
            classLoader -> {
              Fory f =
                  Fory.builder()
                      .withName(CompatibleThreadSafeExample.class.getName())
                      .requireClassRegistration(true)
                      .withCompatible(true)
                      .build();
              // register and generate serializer code.
              f.register(Foo.class);
              f.ensureSerializersCompiled();
              return f;
            });
    System.out.println("Init fory at build time");
  }

  public static void main(String[] args) throws Throwable {
    ThreadSafeExample.test(fory);
    System.out.println("CompatibleThreadSafeExample succeed");
  }
}
