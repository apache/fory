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

package org.apache.fory.logging;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.testng.annotations.Test;

public class Slf4jLoggerTest {

  @Test
  public void testInfo() {
    Slf4jLogger logger = new Slf4jLogger((Slf4jLoggerTest.class));
    ForyLogger foryLogger = new ForyLogger((Slf4jLoggerTest.class));
    logger.info("testInfo");
    logger.info("testInfo {}", "placeHolder");
    logger.infoOnce("testInfo {}", "placeHolder");
    logger.warn("testInfo {}", "placeHolder");
    logger.warnOnce("testInfo {}", "placeHolder");
    foryLogger.info("testInfo");
    foryLogger.info("testInfo {}", "placeHolder");
    foryLogger.infoOnce("testInfo {}", "placeHolder");
    foryLogger.warn("testInfo {}", "placeHolder");
    foryLogger.warnOnce("testInfo {}", "placeHolder");
    int previousLogLevel = LoggerFactory.getLogLevel();
    try {
      LoggerFactory.disableLogging();
      logger.error("testInfo {}", "placeHolder", new Exception("test log"));
      logger.error("testInfo {}", "placeHolder", new Exception("test log"));
      foryLogger.error("testInfo {}", "placeHolder", new Exception("test log"));
      foryLogger.error(null, new Exception("test log"));
      foryLogger.error("test log {} {}", new Exception("test log {} {}"));
    } finally {
      LoggerFactory.setLogLevel(previousLogLevel);
    }
  }

  @Test
  public void testLogOnce() throws Exception {
    ForyLogger logger = new ForyLogger(Slf4jLoggerTest.class);
    int previousLogLevel = LoggerFactory.getLogLevel();
    PrintStream previousOut = System.out;
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      LoggerFactory.setLogLevel(LogLevel.INFO_LEVEL);
      System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8.name()));
      logger.infoOnce("arg-once {}", "alpha");
      logger.infoOnce("arg-once {}", "alpha");
      logger.infoOnce("arg-once {}", "beta");
      logger.infoOnce("level-once {}", "alpha");
      logger.warnOnce("level-once {}", "alpha");
      logger.warnOnce("level-once {}", "alpha");
      logger.warnOnce("class-once {}", Slf4jLoggerTest.class);
      logger.warnOnce("class-once {}", Slf4jLoggerTest.class);
      logger.warnOnce("method-once {}", String.class.getMethod("trim"));
      logger.warnOnce("method-once {}", String.class.getMethod("trim"));
      LoggerFactory.disableLogging();
      logger.warnOnce("disabled-once {}", "alpha");
      LoggerFactory.setLogLevel(LogLevel.WARN_LEVEL);
      logger.warnOnce("disabled-once {}", "alpha");
    } finally {
      System.setOut(previousOut);
      LoggerFactory.setLogLevel(previousLogLevel);
    }
    String logs = out.toString(StandardCharsets.UTF_8.name());
    assertEquals(count(logs, " - arg-once alpha"), 1);
    assertEquals(count(logs, " - arg-once beta"), 1);
    assertEquals(count(logs, " - level-once alpha"), 2);
    assertEquals(count(logs, " - class-once class org.apache.fory.logging.Slf4jLoggerTest"), 1);
    assertEquals(count(logs, " - method-once "), 1);
    assertEquals(count(logs, " - disabled-once alpha"), 1);
  }

  @Test
  public void testDefaultLogLevel() {
    assertEquals(LoggerFactory.getLogLevel(), LogLevel.DEFAULT_LEVEL);
    String envLogLevel = System.getenv("FORY_LOG_LEVEL");
    boolean debugOutputEnabled = "1".equals(System.getenv("ENABLE_FORY_DEBUG_OUTPUT"));
    assertEquals(
        LogLevel.DEFAULT_LEVEL, LogLevel.getDefaultLogLevel(envLogLevel, debugOutputEnabled));
    if (envLogLevel == null) {
      assertEquals(
          LogLevel.DEFAULT_LEVEL, debugOutputEnabled ? LogLevel.INFO_LEVEL : LogLevel.WARN_LEVEL);
    }
    assertEquals(LogLevel.getDefaultLogLevel(null, false), LogLevel.WARN_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("", false), LogLevel.WARN_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel(null, true), LogLevel.INFO_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("", true), LogLevel.INFO_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("error", true), LogLevel.ERROR_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("WARN", true), LogLevel.WARN_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("Info", false), LogLevel.INFO_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("DEBUG", false), LogLevel.DEBUG_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("unknown", false), LogLevel.WARN_LEVEL);
    assertEquals(LogLevel.getDefaultLogLevel("unknown", true), LogLevel.INFO_LEVEL);
  }

  private static int count(String text, String pattern) {
    int count = 0;
    int from = 0;
    while (true) {
      int index = text.indexOf(pattern, from);
      if (index < 0) {
        return count;
      }
      count++;
      from = index + pattern.length();
    }
  }
}
