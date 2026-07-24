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

/**
 * Logger interface in fory. Note that this logger doesn't support configure logging level, please
 * don't big string using this interface. And the format template only support `{}` placeholder. If
 * more sophisticated logger are needed, please config fory to use slf4j by {@link
 * LoggerFactory#useSlf4jLogging} .
 *
 * <p>This logger is used to reduce dependency conflict and reduce jar size.
 */
public interface Logger {
  void debug(String msg);

  void debug(String msg, Object arg);

  void debug(String msg, Object arg1, Object arg2);

  void debug(String msg, Object... args);

  void info(String msg);

  void info(String msg, Object arg);

  void info(String msg, Object arg1, Object arg2);

  void info(String msg, Object... args);

  /** Logs an info message once per logger for the same template and argument values. */
  void infoOnce(String msg);

  void infoOnce(String msg, Object arg);

  void infoOnce(String msg, Object arg1, Object arg2);

  void infoOnce(String msg, Object... args);

  void warn(String msg);

  void warn(String msg, Object arg);

  void warn(String msg, Object... args);

  void warn(String msg, Object arg1, Object arg2);

  void warn(String msg, Throwable t);

  /** Logs a warning once per logger for the same template and argument values. */
  void warnOnce(String msg);

  void warnOnce(String msg, Object arg);

  void warnOnce(String msg, Object... args);

  void warnOnce(String msg, Object arg1, Object arg2);

  void warnOnce(String msg, Throwable t);

  void error(String msg);

  void error(String msg, Object arg);

  void error(String msg, Object arg1, Object arg2);

  void error(String msg, Object... args);

  void error(String msg, Throwable t);
}
