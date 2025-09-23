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

package org.apache.fory.resolver;

import static org.testng.Assert.*;

import org.apache.fory.Fory;
import org.apache.fory.ThreadLocalFory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.exception.InsecureException;
import org.testng.annotations.Test;

public class AllowListCheckerTest {

  @Test
  public void testCheckClass() {
    {
      Fory fory = Fory.builder().requireClassRegistration(false).build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
      fory.getClassResolver().setTypeChecker(checker);
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      checker.allowClass(AllowListCheckerTest.class.getName());
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.addListener(fory.getClassResolver());
      checker.disallowClass(AllowListCheckerTest.class.getName());
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
    {
      Fory fory = Fory.builder().requireClassRegistration(false).build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.WARN);
      fory.getClassResolver().setTypeChecker(checker);
      checker.addListener(fory.getClassResolver());
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass(AllowListCheckerTest.class.getName());
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
  }

  @Test
  public void testCheckClassWildcard() {
    {
      Fory fory = Fory.builder().requireClassRegistration(false).build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
      fory.getClassResolver().setTypeChecker(checker);
      checker.addListener(fory.getClassResolver());
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      checker.allowClass("org.apache.fory.*");
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass("org.apache.fory.*");
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
    {
      Fory fory = Fory.builder().requireClassRegistration(false).build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.WARN);
      fory.getClassResolver().setTypeChecker(checker);
      checker.addListener(fory.getClassResolver());
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass("org.apache.fory.*");
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
  }

  @Test
  public void testThreadSafeFory() {
    AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
    ThreadSafeFory fory =
        new ThreadLocalFory(
            classLoader -> {
              Fory f =
                  Fory.builder()
                      .requireClassRegistration(false)
                      .withClassLoader(classLoader)
                      .build();
              f.getClassResolver().setTypeChecker(checker);
              checker.addListener(f.getClassResolver());
              return f;
            });
    checker.allowClass("org.apache.fory.*");
    byte[] bytes = fory.serialize(new AllowListCheckerTest());
    checker.disallowClass("org.apache.fory.*");
    assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
    assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
  }
}
