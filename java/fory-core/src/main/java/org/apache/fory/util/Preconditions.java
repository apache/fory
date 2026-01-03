/*
 * Copyright (C) 2007 The Guava Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.fory.util;

/**
 * Check utils.
 *
 * <p>derived from
 * https://github.com/google/guava/blob/master/guava/src/com/google/common/base/Preconditions.java
 */
public class Preconditions {
  public static <T> T checkNotNull(T o) {
    if (o == null) {
      throw new NullPointerException();
    }
    return o;
  }

  public static <T> T checkNotNull(T o, String errorMessage) {
    if (o == null) {
      throw new NullPointerException(errorMessage);
    }
    return o;
  }

  public static void checkState(boolean expression, String errorMessage) {
    if (!expression) {
      throw new IllegalStateException(errorMessage);
    }
  }

  public static void checkState(boolean expression) {
    if (!expression) {
      throw new IllegalStateException();
    }
  }

  public static void checkArgument(boolean b) {
    if (!b) {
      throw new IllegalArgumentException();
    }
  }

  public static void checkArgument(boolean b, Object errorMessage) {
    if (!b) {
      throw new IllegalArgumentException(String.valueOf(errorMessage));
    }
  }

  public static void checkArgument(boolean b, String errorMessage) {
    if (!b) {
      throw new IllegalArgumentException(errorMessage);
    }
  }

  /**
   * Ensures the truth of an expression involving one or more parameters to the calling method.
   * Workaround for guava before 20.0.
   */
  public static void checkArgument(
      boolean b, String errorMessageTemplate, Object errorMessageArg0, Object... errorMessageArgs) {
    if (!b) {
      Object[] args;
      if (errorMessageArgs != null) {
        args = new Object[errorMessageArgs.length + 1];
        args[0] = errorMessageArg0;
        System.arraycopy(errorMessageArgs, 0, args, 1, errorMessageArgs.length);
      } else {
        args = new Object[] {errorMessageArg0};
      }
      throw new IllegalArgumentException(String.format(errorMessageTemplate, args));
    }
  }
}
