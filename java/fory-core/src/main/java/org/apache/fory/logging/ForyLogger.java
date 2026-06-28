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

import static org.apache.fory.logging.LogLevel.DEBUG_LEVEL;
import static org.apache.fory.logging.LogLevel.ERROR_LEVEL;
import static org.apache.fory.logging.LogLevel.INFO_LEVEL;
import static org.apache.fory.logging.LogLevel.WARN_LEVEL;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ForyLogger implements Logger {
  private static final DateTimeFormatter dateTimeFormatter =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private final String name;

  public ForyLogger(Class<?> targetClass) {
    this.name = targetClass.getSimpleName();
  }

  // The implementation should not forward to other method, otherwise the fileNumber won't be right.
  @Override
  public void debug(String msg) {
    if (LoggerFactory.getLogLevel() >= DEBUG_LEVEL) {
      log(DEBUG_LEVEL, msg, new Object[0], false);
    }
  }

  @Override
  public void debug(String msg, Object arg) {
    if (LoggerFactory.getLogLevel() >= DEBUG_LEVEL) {
      log(DEBUG_LEVEL, msg, new Object[] {arg}, false);
    }
  }

  @Override
  public void debug(String msg, Object arg1, Object arg2) {
    if (LoggerFactory.getLogLevel() >= DEBUG_LEVEL) {
      log(DEBUG_LEVEL, msg, new Object[] {arg1, arg2}, false);
    }
  }

  @Override
  public void debug(String msg, Object... args) {
    if (LoggerFactory.getLogLevel() >= DEBUG_LEVEL) {
      log(DEBUG_LEVEL, msg, args, false);
    }
  }

  @Override
  public void info(String msg) {
    if (LoggerFactory.getLogLevel() >= INFO_LEVEL) {
      log(INFO_LEVEL, msg, new Object[0], false);
    }
  }

  @Override
  public void info(String msg, Object arg) {
    if (LoggerFactory.getLogLevel() >= INFO_LEVEL) {
      log(INFO_LEVEL, msg, new Object[] {arg}, false);
    }
  }

  @Override
  public void info(String msg, Object arg1, Object arg2) {
    if (LoggerFactory.getLogLevel() >= INFO_LEVEL) {
      log(INFO_LEVEL, msg, new Object[] {arg1, arg2}, false);
    }
  }

  @Override
  public void info(String msg, Object... args) {
    if (LoggerFactory.getLogLevel() >= INFO_LEVEL) {
      log(INFO_LEVEL, msg, args, false);
    }
  }

  @Override
  public void warn(String msg) {
    if (LoggerFactory.getLogLevel() >= WARN_LEVEL) {
      log(WARN_LEVEL, msg, new Object[0], false);
    }
  }

  @Override
  public void warn(String msg, Object arg) {
    if (LoggerFactory.getLogLevel() >= WARN_LEVEL) {
      log(WARN_LEVEL, msg, new Object[] {arg}, true);
    }
  }

  @Override
  public void warn(String msg, Object arg1, Object arg2) {
    if (LoggerFactory.getLogLevel() >= WARN_LEVEL) {
      log(WARN_LEVEL, msg, new Object[] {arg1, arg2}, true);
    }
  }

  @Override
  public void warn(String msg, Object... args) {
    if (LoggerFactory.getLogLevel() >= WARN_LEVEL) {
      log(WARN_LEVEL, msg, args, true);
    }
  }

  @Override
  public void warn(String msg, Throwable t) {
    if (LoggerFactory.getLogLevel() >= WARN_LEVEL) {
      log(WARN_LEVEL, msg, new Object[] {t}, true);
    }
  }

  @Override
  public void error(String msg) {
    if (LoggerFactory.getLogLevel() >= ERROR_LEVEL) {
      log(WARN_LEVEL, msg, new Object[0], false);
    }
  }

  @Override
  public void error(String msg, Object arg) {
    if (LoggerFactory.getLogLevel() >= ERROR_LEVEL) {
      log(ERROR_LEVEL, msg, new Object[] {arg}, true);
    }
  }

  @Override
  public void error(String msg, Object arg1, Object arg2) {
    if (LoggerFactory.getLogLevel() >= ERROR_LEVEL) {
      log(ERROR_LEVEL, msg, new Object[] {arg1, arg2}, true);
    }
  }

  @Override
  public void error(String msg, Object... args) {
    if (LoggerFactory.getLogLevel() >= ERROR_LEVEL) {
      log(ERROR_LEVEL, msg, args, true);
    }
  }

  @Override
  public void error(String msg, Throwable t) {
    if (LoggerFactory.getLogLevel() >= ERROR_LEVEL) {
      log(ERROR_LEVEL, msg, new Object[] {t}, true);
    }
  }

  private void log(int level, String msg, Object[] args, boolean mayPrintTrace) {
    StringBuilder builder = new StringBuilder(dateTimeFormatter.format(LocalDateTime.now()));
    builder.append(" ").append(LogLevel.level2String(level));
    builder.append("  ").append(name);
    int lineNumber = Thread.currentThread().getStackTrace()[3].getLineNumber();
    builder.append(":").append(lineNumber);
    builder.append(" [").append(Thread.currentThread().getName()).append(']');
    builder.append(" - ");
    if (msg == null) {
      msg = "null";
    }
    int len = msg.length();
    int argLen = args.length;
    if (argLen > 0 && args[argLen - 1] instanceof Throwable) {
      argLen -= 1;
    }
    int count = 0;
    for (int i = 0; i < len; i++) {
      char c = msg.charAt(i);
      if (c == '{' && msg.charAt(i + 1) == '}') {
        int cnt = count++;
        if (cnt < argLen) {
          builder.append(args[cnt]);
        } else {
          builder.append("{}");
        }
        i++;
      } else {
        builder.append(c);
      }
    }
    System.out.println(builder);
    int length = args.length;
    if (mayPrintTrace && length > 0) {
      Object o = args[length - 1];
      if (o instanceof Throwable) {
        ((Throwable) o).printStackTrace(System.out);
      }
    }
  }
}
