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

package org.apache.fory.platform;

import org.apache.fory.annotation.Internal;

/** Android runtime detection that is safe to load before Unsafe-backed platform classes. */
@Internal
public final class AndroidSupport {
  private static final String ANDROID_ENABLED_ENV = "FORY_ANDROID_ENABLED";

  public static final boolean IS_ANDROID = isAndroid();

  private AndroidSupport() {}

  private static boolean isAndroid() {
    if ("Dalvik".equals(System.getProperty("java.vm.name", ""))
        || System.getProperty("java.runtime.name", "").contains("Android")) {
      return true;
    }
    String androidEnabled = System.getenv(ANDROID_ENABLED_ENV);
    if (androidEnabled != null && !androidEnabled.isEmpty()) {
      return parseAndroidEnabled(androidEnabled);
    }
    return false;
  }

  private static boolean parseAndroidEnabled(String value) {
    if ("1".equals(value) || "true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("0".equals(value) || "false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException(
        ANDROID_ENABLED_ENV + " must be 1, true, 0, or false, but was " + value);
  }
}
