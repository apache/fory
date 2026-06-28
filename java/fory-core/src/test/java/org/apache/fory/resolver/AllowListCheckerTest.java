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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.fory.Fory;
import org.apache.fory.ThreadSafeFory;
import org.apache.fory.config.Language;
import org.apache.fory.exception.InsecureException;
import org.apache.fory.logging.LogLevel;
import org.apache.fory.logging.LoggerFactory;
import org.testng.annotations.Test;

public class AllowListCheckerTest {

  @Test
  public void testCheckClass() {
    {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
      fory.getTypeResolver().setTypeChecker(checker);
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      checker.allowClass(AllowListCheckerTest.class.getName());
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass(AllowListCheckerTest.class.getName());
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
    {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.WARN);
      fory.getTypeResolver().setTypeChecker(checker);
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass(AllowListCheckerTest.class.getName());
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
  }

  @Test
  public void testCheckClassWildcard() {
    {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
      fory.getTypeResolver().setTypeChecker(checker);
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      checker.allowClass("org.apache.fory.*");
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass("org.apache.fory.*");
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
    {
      Fory fory =
          Fory.builder()
              .withXlang(false)
              .requireClassRegistration(false)
              .withCompatible(false)
              .build();
      AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.WARN);
      fory.getTypeResolver().setTypeChecker(checker);
      byte[] bytes = fory.serialize(new AllowListCheckerTest());
      checker.disallowClass("org.apache.fory.*");
      assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
      assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
    }
  }

  @Test
  public void testBuilderConfiguredChecker() {
    AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
    checker.allowClass("org.apache.fory.*");
    Fory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withTypeChecker(checker)
            .withCompatible(false)
            .build();
    byte[] bytes = fory.serialize(new AllowListCheckerTest());
    checker.disallowClass("org.apache.fory.*");
    assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
    assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
  }

  @Test
  public void testBuilderConfiguredCheckerSuppressesStartupWarning() {
    synchronized (AllowListCheckerTest.class) {
      PrintStream previousOut = System.out;
      int previousLogLevel = LoggerFactory.getLogLevel();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (PrintStream capture = new PrintStream(output, true, StandardCharsets.UTF_8.name())) {
        System.setOut(capture);
        LoggerFactory.setLogLevel(LogLevel.INFO_LEVEL);

        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withCompatible(false)
            .build();
        assertTrue(
            new String(output.toByteArray(), StandardCharsets.UTF_8)
                .contains("Class registration isn't forced"));

        output.reset();
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withTypeChecker((resolver, className) -> true)
            .withCompatible(false)
            .build();
        assertFalse(
            new String(output.toByteArray(), StandardCharsets.UTF_8)
                .contains("Class registration isn't forced"));
      } catch (Exception e) {
        throw new AssertionError(e);
      } finally {
        LoggerFactory.setLogLevel(previousLogLevel);
        System.setOut(previousOut);
      }
    }
  }

  @Test
  public void testTypeCheckerSingleCheck() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    AtomicInteger checks = new AtomicInteger();
    fory.getTypeResolver()
        .setTypeChecker(
            (resolver, className) -> {
              if (className.equals(CheckedType.class.getName())) {
                checks.incrementAndGet();
              }
              return true;
            });

    byte[] bytes = fory.serialize(new CheckedType());
    CheckedType result = (CheckedType) fory.deserialize(bytes);

    assertEquals(result.value, "test");
    assertEquals(checks.get(), 1);
  }

  @Test
  public void testTypeCheckerCacheClearedByDisallow() {
    Fory fory = Fory.builder().withLanguage(Language.JAVA).requireClassRegistration(false).build();
    AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.WARN);
    fory.getTypeResolver().setTypeChecker(checker);

    byte[] bytes = fory.serialize(new CheckedType());
    checker.disallowClass(CheckedType.class.getName());

    assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
  }

  @Test
  public void testTypeCheckerCacheSharedByRegistry() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    Fory writer =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withSharedRegistry(sharedRegistry)
            .build();
    Fory reader =
        Fory.builder()
            .withLanguage(Language.JAVA)
            .requireClassRegistration(false)
            .withSharedRegistry(sharedRegistry)
            .build();
    AtomicInteger checks = new AtomicInteger();
    TypeChecker checker =
        (resolver, className) -> {
          if (className.equals(CheckedType.class.getName())) {
            checks.incrementAndGet();
          }
          return true;
        };
    writer.getTypeResolver().setTypeChecker(checker);
    reader.getTypeResolver().setTypeChecker(checker);

    byte[] bytes = writer.serialize(new CheckedType());
    CheckedType result = (CheckedType) reader.deserialize(bytes);

    assertEquals(result.value, "test");
    assertEquals(checks.get(), 1);
  }

  @Test
  public void testTypeCheckerCacheLimit() {
    SharedRegistry sharedRegistry = new SharedRegistry();
    for (int i = 0; i < 8192; i++) {
      String className = "test.C" + i;
      sharedRegistry.markTypeAccepted(className);
      assertTrue(sharedRegistry.isTypeAccepted(className));
    }
    sharedRegistry.markTypeAccepted("test.Overflow");

    assertFalse(sharedRegistry.isTypeAccepted("test.Overflow"));
  }

  @Test
  public void testThreadSafeFory() {
    AllowListChecker checker = new AllowListChecker(AllowListChecker.CheckLevel.STRICT);
    ThreadSafeFory fory =
        Fory.builder()
            .withXlang(false)
            .requireClassRegistration(false)
            .withTypeChecker(checker)
            .withCompatible(false)
            .buildThreadSafeFory();
    checker.allowClass("org.apache.fory.*");
    byte[] bytes = fory.serialize(new AllowListCheckerTest());
    checker.disallowClass("org.apache.fory.*");
    assertThrows(InsecureException.class, () -> fory.serialize(new AllowListCheckerTest()));
    assertThrows(InsecureException.class, () -> fory.deserialize(bytes));
  }

  public static class CheckedType {
    public String value = "test";
  }
}
