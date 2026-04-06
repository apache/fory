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

import org.apache.fory.util.Utils;

/** Defines a series of log levels. */
public class LogLevel {
  public static final int ERROR_LEVEL = 0;

  public static final int WARN_LEVEL = 1;

  public static final int INFO_LEVEL = 2;

  public static final int DEBUG_LEVEL = 3;

  public static final int DEFAULT_LEVEL;

  static {
    if (Utils.DEBUG_OUTPUT_ENABLED) {
      DEFAULT_LEVEL = DEBUG_LEVEL;
    } else {
      DEFAULT_LEVEL = ERROR_LEVEL;
    }
  }

  public static String level2String(int level) {
    switch (level) {
      case ERROR_LEVEL:
        return "ERROR";
      case WARN_LEVEL:
        return "WARN";
      case INFO_LEVEL:
        return "INFO";
      case DEBUG_LEVEL:
        return "DEBUG";
      default:
        return "UNKNOWN";
    }
  }
}
