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

public class ScopedCompatibleExample {
  private static Fory fory;

  static {
    fory = createFory();
  }

  private static Fory createFory() {
    Fory fory =
        Fory.builder()
            .withName(ScopedCompatibleExample.class.getName())
            .requireClassRegistration(true)
            .withCompatible(true)
            .build();
    // register and generate serializer code.
    fory.register(Foo.class);
    fory.ensureSerializersCompiled();
    return fory;
  }

  public static void main(String[] args) {
    System.out.println("ScopedCompatibleExample started");
    Example.test(fory);
    System.out.println("ScopedCompatibleExample succeed 1/2");
    fory = createFory();
    Example.test(fory);
    System.out.println("ScopedCompatibleExample succeed");
  }
}
